package echo.music.desktop.playback

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread

/**
 * Audio playback engine backed by the system `ffmpeg` binary (already on this machine via scoop).
 *
 * Why ffmpeg: YouTube serves adaptive audio as webm/opus (itag 251) or m4a/AAC (itag 140).
 * Neither `javax.sound.sampled` nor the JVM decodes those natively, and shipping a decoder
 * sidecar is exactly what upstream does too (`FFmpegKit` powers its `AudioExportService`).
 *
 * Pipeline:  ffmpeg -i <url> -vn -f s16le pipe:1  →  PCM bytes  →  SourceDataLine
 *
 *  - **Pause**: stop draining the pipe. The OS pipe buffer fills (≈64 KiB), ffmpeg blocks on
 *    write, which stalls its network read too — a real pause, not just silence. The line is
 *    stopped so buffered audio doesn't drain out.
 *  - **Seek**: input-side `-ss` on a respawned process (googlevideo answers Range requests).
 *    Latency tuning: `-probesize 128k` / `-analyzeduration 0.2s` / `-fflags nobuffer` cut the
 *    per-respawn probe from ffmpeg's default **5 MB / 5 s of media** down to the webm header +
 *    a whisker of media — that default probe was the multi-second stall behind every seek.
 *  - **Progress**: `-progress pipe:2` key=value blocks on stderr, `out_time_us` added to the
 *    seek base (input `-ss` rebases output timestamps to zero).
 *  - **Start callback**: [onStarted] fires on the FIRST PCM chunk written to the line, so the
 *    UI's buffering spinner covers exactly the real wait (spawn → network → probe → first
 *    audio) instead of being cleared the moment the process is created.
 *  - **End**: stdout EOF → [onEnded] → controller advances the queue; an EOF *before any audio
 *    arrived* is a failure, not a track end (it must not silently skip to the next song).
 *
 * Every worker thread is stamped with a **run id** ([runSeq]): a respawn invalidates the previous
 * id, so a stale reader/progress thread from a superseded seek can never publish progress, fire
 * [onStarted] or auto-advance the queue for the new one (that race used to restart playback from
 * 0:00 right after a seek).
 */
class FfmpegPlayer(
    private val onProgress: (positionMs: Long, durationMs: Long?) -> Unit,
    private val onEnded: () -> Unit,
    private val onFailed: (message: String) -> Unit,
    private val onStarted: () -> Unit = {},
    private val onInterrupted: (positionMs: Long) -> Unit = {},
) {

    data class Source(val url: String, val headers: Map<String, String>, val durationMs: Long?)

    @Volatile private var process: Process? = null
    @Volatile private var line: SourceDataLine? = null
    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var seekBaseMs = 0L
    @Volatile private var currentPositionMs = 0L
    @Volatile private var streamDurationMs: Long? = null

    /** Bumped on every spawn/stop; worker threads carrying an older id must stay silent. */
    private val runSeq = java.util.concurrent.atomic.AtomicLong(0)

    private var readerThread: Thread? = null
    private var progressThread: Thread? = null

    val isRunning: Boolean get() = running

    private fun isCurrent(myRun: Long): Boolean = running && runSeq.get() == myRun

    /** Starts (or replaces) playback of [source] at [startMs]. */
    @Synchronized
    fun play(source: Source, startMs: Long = 0L, volume: Float = 1f) {
        val myRun = runSeq.incrementAndGet() // invalidate previous workers first…
        stopInternal(keepLine = false) // …then tear the old pipeline down

        val ffmpeg = findFfmpeg() ?: run {
            onFailed("ffmpeg not found — audio playback is unavailable")
            return
        }
        seekBaseMs = startMs
        currentPositionMs = startMs
        streamDurationMs = source.durationMs

        val command = mutableListOf(
            ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "error",
            "-user_agent", source.headers["User-Agent"] ?: DEFAULT_USER_AGENT,
            // ---- seek/startup latency: defaults are 5 MB probe + 5 s of media analysis,
            // which meant seconds of download before the first sample on every (re)spawn.
            "-probesize", "131072",
            "-analyzeduration", "200000",
            "-fflags", "nobuffer",
            // ---- THE seek fix: with the default threshold, ffmpeg's http layer "soft-seeks"
            // by draining the entire rest of the open response (~1 MB/s on googlevideo →
            // 3–16 s stall on every timeline jump) before reopening. A 16 kB threshold forces
            // the immediate reopen-with-Range path instead (measured 15 s → 0.78 s at -ss 30).
            "-short_seek_size", "16384",
            // ---- smoother clock: -progress blocks land every 500 ms by default, so the raw
            // positionMs (seekbar thumb, mini ring, VideoEngine's clock) only refreshed twice a
            // second. 100 ms keeps the thumb gliding and lets the video pacer extrapolate tightly.
            "-stats_period", "0.1",
        )
        // ---- network auto-reconnect: if the googlevideo CDN drops the connection mid-track,
        // ffmpeg automatically reconnects at the same byte offset instead of dying prematurely.
        if (source.url.startsWith("http://") || source.url.startsWith("https://")) {
            command += listOf(
                "-reconnect", "1",
                "-reconnect_streamed", "1",
                "-reconnect_delay_max", "5",
                "-reconnect_on_network_error", "1",
                "-reconnect_on_http_error", "4xx,5xx",
            )
        }
        if (startMs > 0) command += listOf("-ss", (startMs / 1000.0).toString())
        command += listOf(
            "-i", source.url,
            "-vn", "-map", "0:a:0",
            "-f", "s16le", "-acodec", "pcm_s16le", "-ar", "44100", "-ac", "2",
            "-progress", "pipe:2", "-nostats",
            "pipe:1",
        )

        val builder = ProcessBuilder(command).redirectErrorStream(false)
        val proc =
            try {
                builder.start()
            } catch (e: Exception) {
                onFailed("Could not start ffmpeg: ${e.message}")
                return
            }
        process = proc
        running = true
        paused = false

        openLine(volume)
        startAudioThread(proc, myRun)
        startProgressThread(proc, myRun)
    }

    @Synchronized
    fun setPaused(value: Boolean) {
        if (!running) return
        paused = value
        if (value) {
            line?.stop()
        } else {
            line?.start()
        }
    }

    @Synchronized
    fun seek(source: Source, positionMs: Long) {
        if (!running) {
            play(source, positionMs)
            return
        }
        val volume = currentVolume
        play(source, positionMs, volume) // respawn with input-side -ss
    }

    @Volatile private var currentVolume: Float = 1f

    @Synchronized
    fun setVolume(value: Float) {
        currentVolume = value
        val l = line ?: return
        runCatching {
        val control =
            l.getControl(javax.sound.sampled.FloatControl.Type.MASTER_GAIN)
                as? javax.sound.sampled.FloatControl ?: return
        val db =
            (20.0 * kotlin.math.log10(value.coerceIn(0.0001f, 1f).toDouble()))
                .toFloat()
                .coerceIn(control.minimum, control.maximum)
        control.setValue(db)
        }
    }

    @Synchronized
    fun stop() {
        runSeq.incrementAndGet() // no worker may fire callbacks after an explicit stop
        stopInternal(keepLine = false)
    }

    private fun stopInternal(keepLine: Boolean) {
        running = false
        paused = false
        val p = process
        process = null
        p?.destroy()
        runCatching { p?.waitFor() }
        if (!keepLine) {
            val l = line
            line = null
            l?.let { runCatching { it.close() } }
        }
    }

    private fun openLine(volume: Float) {
        runCatching {
            val existing = line
            if (existing != null && existing.isOpen) {
                existing.close()
            }
            val format = AudioFormat(44100f, 16, 2, true, false)
            val newLine = AudioSystem.getSourceDataLine(format)
            // ~250 ms buffer: large enough to ride out network jitter, small enough for snappy seeks.
            newLine.open(format, 44100 * 2 * 2 / 4)
            newLine.start()
            line = newLine
            setVolume(volume)
        }.onFailure {
            onFailed("No audio output device available: ${it.message}")
        }
    }

    private fun startAudioThread(proc: Process, myRun: Long) {
        readerThread =
            thread(name = "ffmpeg-pcm", isDaemon = true) {
                val out = proc.inputStream
                val buf = ByteArray(16 * 1024)
                var received = false
                try {
                    while (isCurrent(myRun) && !Thread.currentThread().isInterrupted) {
                        while (paused && isCurrent(myRun)) Thread.sleep(40)
                        if (!isCurrent(myRun)) break
                        val n = out.read(buf)
                        if (n < 0) break
                        val target = line ?: break
                        target.write(buf, 0, n)
                        if (!received) {
                            received = true
                            // First real audio: clear the buffering state in the controller.
                            if (isCurrent(myRun)) onStarted()
                        }
                    }
                } catch (_: Exception) {
                    // stream torn down (seek/stop) — expected
                }
                val endedCleanly = isCurrent(myRun) && !paused
                if (endedCleanly) {
                    running = false
                    val duration = streamDurationMs ?: 0L
                    val pos = currentPositionMs
                    // Genuine track end: must have received audio AND reached near the expected track duration
                    // (within 3.5 seconds). If duration is unknown, at least 45 seconds must have played.
                    val isActuallyFinished =
                        (duration > 0L && pos >= duration - 3_500L) || (duration <= 0L && pos >= 45_000L)

                    if (received && isActuallyFinished) {
                        // EOF with playback still active and track genuinely finished
                        onEnded()
                    } else if (received) {
                        // Stream disconnected prematurely mid-track: auto-resume instead of skipping!
                        onInterrupted(pos)
                    } else {
                        // Never got a single sample (expired URL, blocked stream): surface an
                        // error instead of silently skipping to the next track.
                        onFailed("Stream ended before any audio arrived")
                    }
                }
            }
    }

    private fun startProgressThread(proc: Process, myRun: Long) {
        progressThread =
            thread(name = "ffmpeg-progress", isDaemon = true) {
                // Only the current run may publish position — a superseded seek's ffmpeg keeps
                // emitting progress on its way down and would otherwise drag the thumb back.
                fun emit(ms: Long) {
                    if (ms >= 0 && isCurrent(myRun)) {
                        currentPositionMs = ms
                        onProgress(ms, streamDurationMs)
                    }
                }
                fun report(rawMicros: String) {
                    val micros = rawMicros.trim().toLongOrNull() ?: return
                    if (micros < 0) return
                    emit(seekBaseMs + micros / 1_000L)
                }
                try {
                    BufferedReader(InputStreamReader(proc.errorStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            val l = line ?: continue
                            when {
                                l.startsWith("out_time_us=") ->
                                    report(l.substringAfter('='))
                                l.startsWith("out_time_ms=") ->
                                    report(l.substringAfter('=')) // ffmpeg quirk: ms is µs here
                                l.startsWith("out_time=") ->
                                    parseClock(l.substringAfter('='))?.let { ms ->
                                        emit(seekBaseMs + ms)
                                    }
                                l.startsWith("duration=") ->
                                    parseClock(l.substringAfter('='))?.let { d ->
                                        if (d > 0 && isCurrent(myRun)) streamDurationMs = d
                                    }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // stream torn down — expected
                }
            }
    }

    private fun parseClock(value: String): Long? {
        // HH:MM:SS.micro  or  -00:00:00.N/A
        val parts = value.trim().split(":")
        if (parts.size != 3) return null
        val hours = parts[0].toLongOrNull() ?: return null
        val minutes = parts[1].toLongOrNull() ?: return null
        val seconds = parts[2].substringBefore('.').toLongOrNull() ?: return null
        val micros = parts[2].substringAfter('.', "0").take(6).padEnd(6, '0').toLongOrNull() ?: 0L
        return hours * 3_600_000 + minutes * 60_000 + seconds * 1000 + micros / 1000
    }

    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/18.0 Safari/605.1.15"

        private var cachedFfmpeg: String? = null

        /** Resolve ffmpeg once: FFMPEG_HOME → PATH → the scoop shim this machine ships. */
        fun findFfmpeg(): String? {
            cachedFfmpeg?.let { return it }
            val candidates = mutableListOf<String>()
            System.getenv("FFMPEG_HOME")?.let { candidates += File(it, "bin/ffmpeg.exe").path }
            System.getenv("PATH")
                ?.split(File.pathSeparator)
                ?.orEmpty()
                ?.forEach { dir ->
                    candidates += File(dir, "ffmpeg.exe").path
                    candidates += File(dir, "ffmpeg").path
                }
            candidates += """C:\Users\dhruv\scoop\shims\ffmpeg.exe"""
            val found =
                candidates.firstOrNull { p ->
                    val f = File(p)
                    f.isAbsolute && f.exists()
                } ?: runCatching {
                    // scoop shims live on PATH; ask the shell as a last resort
                    val proc = ProcessBuilder("where.exe", "ffmpeg").start()
                    proc.inputStream.bufferedReader().readLine()?.trim()?.takeIf { it.isNotEmpty() }
                }.getOrNull()
            return found?.also { cachedFfmpeg = it }
        }
    }
}
