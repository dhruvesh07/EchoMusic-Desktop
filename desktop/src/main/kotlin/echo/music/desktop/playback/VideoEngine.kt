package echo.music.desktop.playback

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import echo.music.desktop.playback.StreamResolver.VideoResolved
import java.io.InputStream
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Video playback engine — the visual half of [FfmpegPlayer], decoding frames off the audio clock.
 *
 * Why ffmpeg again: neither the JVM nor Compose decodes H.264/VP9, and shipping a second decoder
 * sidecar would be exactly the duplication upstream avoids by leaning on `FFmpegKit` for both
 * audio and video. The same system binary that feeds [FfmpegPlayer] is repurposed here.
 *
 * Pipeline:  `ffmpeg [-ss t] -i <url> -an -vf scale=W:H,showinfo -f rawvideo -pix_fmt rgba pipe:1`
 *            → raw RGBA frames + per-frame `pts_time` → skia `Image.makeRaster` → `ImageBitmap`
 *              → [frame]
 *
 * **Audio is the master clock, and this loop treats it as a *smooth* one.** ffmpeg's `-progress`
 * stats land periodically (FfmpegPlayer asks for every 100 ms), so between reports the raw
 * `positionMs` freezes — a pacer that read it raw stalled ~250 ms and then burst-dropped a
 * backlog: exactly the judder users see as "laggy video". [clockPos] therefore extrapolates the
 * position forward between reports (audio is monotonic while playing, so the estimate is exact
 * to ~100 ms). Each frame's media time comes from **`showinfo`'s `pts_time`** — ffmpeg's own
 * timestamp for that exact output frame, paired by frame number — so nothing drifts when the
 * container's fps metadata is off (the old `seekMs + index/fps` estimate accumulates error and
 * slides the picture out of sync with the audio over a long track). The fps fallback remains
 * only for a frame whose line never arrives.
 *
 * Pacing: wait until `frameTime − 80 ms` in **25 ms slices** that re-check jump/track/stop every
 * time (a blind 250 ms sleep delayed seeks), then paint — unless the frame is more than 150 ms
 * late, in which case it is decoded but dropped to catch up. A raw position jump of more than
 * 1200 ms tears the process down and respawns it with an input-side `-ss`, mirroring
 * `FfmpegPlayer.seek`. A track change unwinds the engine entirely — the full player restarts it
 * for the next video, since it owns that lifecycle.
 *
 * `-ss` goes BEFORE `-i` for the same reason it does in [FfmpegPlayer]: googlevideo answers Range
 * requests, so the seek lands on the server instead of downloading from byte 0. The scale filter
 * takes explicit even `W:H` (computed from the format's own dimensions, clamped to 480p) rather
 * than `-2`, so the raw frame stride is known exactly — a reader that guessed the row count would
 * desync on the first odd-rounded frame and tear every frame after it.
 *
 * Nothing here may throw onto the UI thread: failures land in [error] and the child process is
 * always destroyed on the way out.
 */
object VideoEngine {

    /** Latest decoded frame; read by the player surface, so every write invalidates it. */
    val frame: MutableState<ImageBitmap?> = mutableStateOf<ImageBitmap?>(null)

    private val _error = MutableStateFlow<String?>(null)

    /** Non-null when the engine gave up — the UI falls back to the cover art. */
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Pacing windows against the audio clock, in ms. */
    private const val LATE_MS = 150 // more than this late → drop the frame instead of painting
    private const val LEAD_MS = 80 // paint this far ahead of the frame's due time
    private const val JUMP_MS = 1200
    private const val CLOCK_STALE_MS = 1200L // interpolation cap if progress stops arriving
    private const val PTS_WAIT_MS = 120L // how long to wait for showinfo's line for frame n

    /** Ceiling on the decoded frame height — the surface is ~460.dp at most, 480p is plenty. */
    private const val MAX_HEIGHT = 480

    @Volatile private var running = false
    @Volatile private var process: Process? = null
    @Volatile private var readerThread: Thread? = null
    @Volatile private var lastFfmpegError = ""
    @Volatile private var pendingSeekMs: Long? = null

    val isRunning: Boolean get() = running

    fun notifySeek(positionMs: Long) {
        pendingSeekMs = positionMs
    }

    /** Starts (or replaces) playback of [resolved] at [startMs]. Safe to call from the UI. */
    @Synchronized
    fun start(videoId: String, resolved: VideoResolved, startMs: Long = 0L) {
        stopInternal() // guard against a double start leaking the previous decoder

        val ffmpeg = FfmpegPlayer.findFfmpeg() ?: run {
            _error.value = FfmpegPlayer.missingFfmpegMessage()
            return
        }
        val (width, height) = outputSize(resolved)
        if (width <= 0 || height <= 0) {
            _error.value = "Video stream has no usable dimensions"
            return
        }
        val fps = if (resolved.fps in 1..60) resolved.fps else 30

        lastFfmpegError = ""
        _error.value = null
        running = true
        // created stopped so the ownership check inside decodeLoop cannot race the assignment below
        val worker =
            thread(start = false, isDaemon = true, name = "video-decode") {
                decodeLoop(ffmpeg, videoId, resolved, width, height, fps, startMs.coerceAtLeast(0L))
            }
        readerThread = worker
        worker.start()
    }

    @Synchronized
    fun stop() = stopInternal()

    private fun stopInternal() {
        running = false
        readerThread = null
        val p = process
        process = null
        runCatching { p?.destroy() }
        runCatching { p?.waitFor() }
        frame.value = null
    }

    // ---------------------------------------------------------------- decode loop

    private fun decodeLoop(
        ffmpeg: String,
        videoId: String,
        resolved: VideoResolved,
        width: Int,
        height: Int,
        fps: Int,
        startMs: Long,
    ) {
        val self = Thread.currentThread()
        val buf = ByteArray(width * height * 4)
        var owned: Process? = null
        var seekMs = startMs
        var frameIndex = 0L
        var exhausted = false

        // ---- interpolated audio clock
        var clockReported = Playback.state.value.positionMs
        var clockAt = System.nanoTime()
        var clockPlaying = Playback.state.value.isPlaying
        fun clockPos(): Long {
            val s = Playback.state.value
            val now = System.nanoTime()
            if (s.positionMs != clockReported || s.isPlaying != clockPlaying) {
                clockReported = s.positionMs
                clockPlaying = s.isPlaying
                clockAt = now
            }
            if (!clockPlaying) return clockReported
            val elapsedMs = (now - clockAt) / 1_000_000L
            return clockReported + elapsedMs.coerceAtMost(CLOCK_STALE_MS)
        }

        try {
            while (running && readerThread === self && !self.isInterrupted) {
                val ptsQueue = ArrayDeque<FramePts>()
                val proc = spawn(ffmpeg, resolved, seekMs, ptsQueue) ?: break
                owned = proc
                val input = proc.inputStream

                var framesRead = 0L
                var nextSeek: Long? = null
                var eof = false
                var unwind = false
                var firstPts: Double? = null

                var lastWall = System.currentTimeMillis()
                var lastRaw = Playback.state.value.positionMs
                pendingSeekMs = null

                fun checkSeek(): Long? {
                    val pSeek = pendingSeekMs
                    if (pSeek != null) {
                        pendingSeekMs = null
                        return pSeek
                    }
                    val currentRaw = Playback.state.value.positionMs
                    val now = System.currentTimeMillis()
                    val wallElapsed = now - lastWall
                    val rawDelta = currentRaw - lastRaw
                    // If stream position jumped backwards or forward discontinuously relative to wall clock
                    if (rawDelta < -800L || rawDelta > wallElapsed + 2500L) {
                        return currentRaw
                    }
                    lastWall = now
                    lastRaw = currentRaw
                    return null
                }

                while (running && !unwind) {
                    val state = Playback.state.value
                    if (state.current?.videoId != videoId) {
                        unwind = true
                        break
                    }
                    val seekTarget = checkSeek()
                    if (seekTarget != null) {
                        nextSeek = seekTarget
                        break
                    }
                    if (!state.isPlaying) {
                        sleepQuietly(25)
                        continue
                    }
                    if (!fill(input, buf)) {
                        eof = true
                        break
                    }
                    framesRead++

                    val pts = nextPts(ptsQueue, frameIndex)
                    if (firstPts == null && pts != null) {
                        firstPts = pts
                    }
                    val fPts = firstPts
                    val frameTime =
                        if (pts != null && fPts != null) seekMs + ((pts - fPts) * 1000.0).toLong()
                        else seekMs + frameIndex * 1000 / fps

                    // ---- wait until due, in ≤25 ms slices
                    var due = false
                    while (running) {
                        val st = Playback.state.value
                        if (st.current?.videoId != videoId) {
                            unwind = true
                            break
                        }
                        val sTarget = checkSeek()
                        if (sTarget != null) {
                            nextSeek = sTarget
                            break
                        }
                        if (!st.isPlaying) {
                            sleepQuietly(25)
                            continue
                        }
                        val pos = clockPos()
                        if (pos >= frameTime - LEAD_MS) {
                            due = true
                            break
                        }
                        sleepQuietly(((frameTime - LEAD_MS) - pos).coerceIn(1L, 25L))
                    }
                    if (unwind || nextSeek != null || !running) break
                    if (!due) continue

                    val pos = clockPos()
                    // Paint immediately if no frame exists yet (don't leave UI blank) or if on time
                    if (frame.value == null || frameTime >= pos - LATE_MS) {
                        if (!paint(buf, width, height)) {
                            unwind = true
                            break
                        }
                        frameIndex++
                    } else {
                        // behind by more than LATE_MS -> drop and loop around immediately to catch up
                        frameIndex++
                    }
                }

                // ---- reap this process: normal exit, seek respawn, or unwind
                if (process === proc) process = null
                owned = null
                runCatching { proc.destroy() }
                runCatching { proc.waitFor() }

                if (unwind || !running) return

                if (nextSeek != null) {
                    seekMs = nextSeek.coerceAtLeast(0L)
                    frameIndex = 0
                    lastRaw = seekMs
                    exhausted = false
                    continue
                }

                if (eof) {
                    // ffmpeg exiting with a complaint (expired URL, 403 …) is a real failure;
                    // a silent exit just means the video ran out before the audio did.
                    if (framesRead == 0L && lastFfmpegError.isNotEmpty()) {
                        _error.value = "Video stream ended: $lastFfmpegError"
                        return
                    }
                    exhausted = framesRead == 0L
                    val anchor = Playback.state.value.positionMs
                    val resumeAt = awaitSeek(videoId, anchor, exhausted) ?: return
                    seekMs = resumeAt.coerceAtLeast(0L)
                    frameIndex = 0
                    lastRaw = seekMs
                    exhausted = false
                }
            }
        } finally {
            val p = owned
            if (p != null) {
                if (process === p) process = null
                runCatching { p.destroy() }
                runCatching { p.waitFor() }
            }
            if (readerThread === self) {
                running = false
                readerThread = null
            }
        }
    }

    /** Blocks until the user seeks (or the track changes, which answers null). */
    private fun awaitSeek(videoId: String, anchorMs: Long, onlyBackwards: Boolean): Long? {
        while (running && !Thread.currentThread().isInterrupted) {
            val state = Playback.state.value
            if (state.current?.videoId != videoId) return null
            val pos = state.positionMs
            if (abs(pos - anchorMs) > JUMP_MS && (!onlyBackwards || pos < anchorMs)) return pos
            sleepQuietly(100)
        }
        return null
    }

    /** One ffmpeg process producing raw RGBA; null means "start failed" (error already published). */
    private fun spawn(
        ffmpeg: String,
        resolved: VideoResolved,
        seekMs: Long,
        ptsQueue: ArrayDeque<FramePts>,
    ): Process? {
        // a superseded worker must never publish its process over the current one's
        if (readerThread !== Thread.currentThread()) return null
        val (width, height) = outputSize(resolved)
        val command = mutableListOf(
            ffmpeg, "-hide_banner", "-nostdin",
            // info level so `showinfo`'s per-frame pts_time lines reach stderr (the pacer's
            // exact timestamps); -nostats keeps the `frame=…` counter from polluting them.
            "-loglevel", "info", "-nostats",
            "-user_agent", resolved.client.userAgent,
            // Same latency contract as FfmpegPlayer: ffmpeg's default probe waits for 5 MB /
            // 5 s of media before the first frame, which is most of a video seek's stall.
            // 256 kB + 0.4 s still covers SPS/PPS + a GOP's worth of analysis, and a 16 kB
            // short-seek threshold stops the http layer draining the old response before
            // reopening with a Range request (audio seek fix — same mechanism, video side).
            "-probesize", "262144",
            "-analyzeduration", "400000",
            "-fflags", "nobuffer",
            "-short_seek_size", "16384",
        )
        if (seekMs > 0) command += listOf("-ss", (seekMs / 1000.0).toString())
        command += listOf(
            "-i", resolved.url,
            "-an",
            "-vf", "scale=$width:$height,showinfo",
            "-f", "rawvideo", "-pix_fmt", "rgba",
            "pipe:1",
        )

        val proc =
            try {
                ProcessBuilder(command).redirectErrorStream(false).start()
            } catch (e: Exception) {
                _error.value = "Could not start video decoder: ${e.message}"
                return null
            }
        process = proc
        if (!running || readerThread !== Thread.currentThread()) { // stop()/start() raced us
            if (process === proc) process = null
            runCatching { proc.destroy() }
            return null
        }
        drainStderr(proc, ptsQueue)
        return proc
    }

    /**
     * stderr must be drained or a burst of errors blocks ffmpeg mid-frame. Two kinds of lines
     * matter: `showinfo`'s `n: … pts_time:…` (the exact media time of each output frame — pushed
     * to [ptsQueue] keyed by frame number), and everything else (kept as [lastFfmpegError],
     * the diagnostic used when a spawn exits without ever producing a frame).
     */
    private fun drainStderr(proc: Process, ptsQueue: ArrayDeque<FramePts>) {
        thread(name = "video-stderr", isDaemon = true) {
            try {
                proc.errorStream.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val text = line ?: continue
                        val marker = text.indexOf("pts_time:")
                        if (marker >= 0) {
                            val n = parseShowinfoN(text.substring(0, marker))
                            val pts =
                                text
                                    .substring(marker + "pts_time:".length)
                                    .takeWhile { it.isDigit() || it == '.' || it == '-' || it == 'e' || it == 'E' }
                                    .toDoubleOrNull()
                            if (n != null && pts != null) {
                                synchronized(ptsQueue) { ptsQueue.addLast(FramePts(n, pts)) }
                            }
                            continue
                        }
                        val trimmed = text.trim()
                        if (trimmed.isNotEmpty()) lastFfmpegError = trimmed
                    }
                }
            } catch (_: Exception) {
                // process torn down (seek/stop) — expected
            }
        }
    }

    /** `n:  12 pts: …` → 12 (the frame number showinfo assigns in output order). */
    private fun parseShowinfoN(prefix: String): Long? {
        val idx = prefix.indexOf("n:")
        if (idx < 0) return null
        var i = idx + 2
        while (i < prefix.length && (prefix[i] == ' ' || prefix[i] == '\t')) i++
        val start = i
        while (i < prefix.length && prefix[i].isDigit()) i++
        if (start == i) return null
        return prefix.substring(start, i).toLongOrNull()
    }

    /**
     * The pts (seconds) of output frame [n] as reported by showinfo, paired by frame number so a
     * missing/duplicated line can never shift every later frame. The line is written *before* the
     * frame reaches stdout, so this only ever waits a scheduler tick; 120 ms of silence means the
     * line was lost and the caller falls back to fps math for this frame alone.
     */
    private fun nextPts(ptsQueue: ArrayDeque<FramePts>, n: Long): Double? {
        val deadline = System.currentTimeMillis() + PTS_WAIT_MS
        while (true) {
            synchronized(ptsQueue) {
                while (ptsQueue.isNotEmpty() && ptsQueue.first().n < n) ptsQueue.removeFirst()
                val head = ptsQueue.firstOrNull()
                if (head != null && head.n == n) {
                    ptsQueue.removeFirst()
                    return head.pts
                }
            }
            if (System.currentTimeMillis() >= deadline) return null
            sleepQuietly(4)
        }
    }

    /** One showinfo observation: output frame [n] has media time [pts] (seconds). */
    private data class FramePts(val n: Long, val pts: Double)

    // ---------------------------------------------------------------- helpers

    /**
     * Reads exactly one frame: a single `read()` on a pipe rarely fills a buffer, so loop until
     * it is whole. False means EOF or a broken pipe — both end this spawn.
     */
    private fun fill(input: InputStream, buf: ByteArray): Boolean {
        var offset = 0
        return try {
            while (offset < buf.size) {
                val read = input.read(buf, offset, buf.size - offset)
                if (read < 0) break
                offset += read
            }
            offset == buf.size
        } catch (_: Exception) {
            false
        }
    }

    /** RGBA bytes → [frame]. False (and a published [error]) when conversion fails or we stopped. */
    private fun paint(buf: ByteArray, width: Int, height: Int): Boolean {
        if (!running) return false
        val bitmap =
            runCatching {
                org.jetbrains.skia.Image
                    .makeRaster(
                        org.jetbrains.skia.ImageInfo(
                            width,
                            height,
                            org.jetbrains.skia.ColorType.RGBA_8888,
                            org.jetbrains.skia.ColorAlphaType.UNPREMUL,
                        ),
                        buf,
                        width * 4,
                    )
                    .toComposeImageBitmap()
            }.onFailure { _error.value = "Video frame conversion failed: ${it.message}" }
                .getOrNull()
        if (bitmap == null) return false
        frame.value = bitmap
        return true
    }

    /** Exact even output size for `scale=W:H`, clamped to [MAX_HEIGHT]. */
    private fun outputSize(resolved: VideoResolved): Pair<Int, Int> {
        val sourceWidth = resolved.width
        val sourceHeight = resolved.height
        if (sourceWidth <= 0 || sourceHeight <= 0) return 0 to 0
        var height = min(sourceHeight, MAX_HEIGHT)
        if (height % 2 != 0) height -= 1
        var width = (sourceWidth.toLong() * height / sourceHeight).toInt()
        if (width % 2 != 0) width -= 1
        if (width < 2 || height < 2) return 0 to 0
        return width to height
    }

    private fun sleepQuietly(ms: Long) {
        if (ms <= 0) return
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
