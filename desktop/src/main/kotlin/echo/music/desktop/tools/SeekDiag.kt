package echo.music.desktop.tools

import echo.music.desktop.playback.FfmpegPlayer
import echo.music.desktop.playback.StreamResolver
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

/**
 * Seek-latency diagnostic: gradlew :desktop:seekDiag
 *
 * Isolates where the seconds go between `Playback.seekTo()` and audio actually playing at the new
 * position. One run answers three questions:
 *
 *  1. Does ffmpeg's input-side `-ss` produce an HTTP Range seek, or does it stream from byte 0
 *     and discard (the "start over and wait" behaviour a user sees)? — timing matrix across
 *     targets plus a filtered, timestamped stderr trace of one seek.
 *  2. Do googlevideo Range requests at mid/end-of-file offsets actually answer 206 fast?
 *  3. Is our own engine path (FfmpegPlayer.play → first PCM) adding overhead of its own?
 */
fun main() = runBlocking {
    val videoId = System.getProperty("smoke.videoId", "dQw4w9WgXcQ")
    println("== seek diagnostic ($videoId) ==")

    val resolveStart = System.currentTimeMillis()
    val resolved =
        StreamResolver.resolve(videoId) ?: run {
            println("FAIL: resolve returned null")
            kotlin.system.exitProcess(1)
        }
    println("resolve: ${System.currentTimeMillis() - resolveStart}ms  itag=${resolved.itag}")
    val url = resolved.url
    val ua = resolved.headers["User-Agent"] ?: "Mozilla/5.0"
    val ffmpeg = FfmpegPlayer.findFfmpeg() ?: error("ffmpeg not found")

    fun baseArgs(): MutableList<String> =
        mutableListOf(
            ffmpeg, "-hide_banner", "-nostdin",
            "-user_agent", ua,
        )

    // ---------------------------------------------------------------- 1. timing matrix
    println("\n-- time to first 4 KiB of PCM --")
    runCase("baseline (no -ss)", baseArgs().apply {
        addAll(listOf("-loglevel", "error", "-probesize", "131072",
            "-analyzeduration", "200000", "-fflags", "nobuffer"))
    }, url, seekSec = null)
    runCase("-ss 30 (tuned probe)", baseArgs().apply {
        addAll(listOf("-loglevel", "error", "-probesize", "131072",
            "-analyzeduration", "200000", "-fflags", "nobuffer", "-ss", "30"))
    }, url, seekSec = null)
    runCase("-ss 30 (default probe)", baseArgs().apply {
        addAll(listOf("-loglevel", "error", "-ss", "30"))
    }, url, seekSec = null)
    runCase("-ss 30 (+ short_seek_size=16384)", baseArgs().apply {
        addAll(listOf("-loglevel", "error", "-probesize", "131072",
            "-analyzeduration", "200000", "-fflags", "nobuffer",
            "-short_seek_size", "16384", "-ss", "30"))
    }, url, seekSec = null)
    runCase("-ss 180 (+ short_seek_size=16384)", baseArgs().apply {
        addAll(listOf("-loglevel", "error", "-probesize", "131072",
            "-analyzeduration", "200000", "-fflags", "nobuffer",
            "-short_seek_size", "16384", "-ss", "180"))
    }, url, seekSec = null)
    runCase("-ss 30 (-noaccurate_seek)", baseArgs().apply {
        addAll(listOf("-loglevel", "error", "-probesize", "131072",
            "-analyzeduration", "200000", "-fflags", "nobuffer",
            "-noaccurate_seek", "-ss", "30"))
    }, url, seekSec = null)
    runCase("-ss 180 (tuned probe)", baseArgs().apply {
        addAll(listOf("-loglevel", "error", "-probesize", "131072",
            "-analyzeduration", "200000", "-fflags", "nobuffer", "-ss", "180"))
    }, url, seekSec = null)
    runCase("-ss 900 (tuned probe)", baseArgs().apply {
        addAll(listOf("-loglevel", "error", "-probesize", "131072",
            "-analyzeduration", "200000", "-fflags", "nobuffer", "-ss", "900"))
    }, url, seekSec = null)

    // ---------------------------------------------------------------- 2. stderr trace of one seek
    println("\n-- trace: -ss 30 + short_seek_size=16384, filtered stderr with elapsed ms --")
    traceSeek(baseArgs().apply {
        addAll(listOf("-loglevel", "trace", "-short_seek_size", "16384", "-ss", "30"))
    }, url)

    // ---------------------------------------------------------------- 3. HTTP range probes
    println("\n-- googlevideo Range probes --")
    probeRange("bytes=0-65535 (start)", url, ua, "bytes=0-65535")
    probeRange("bytes=513000-578535 (~30s in)", url, ua, "bytes=513000-578535")
    probeRange("bytes=180000000-180065535 (deep)", url, ua, "bytes=180000000-180065535")
    probeRange("bytes=-65536 (suffix = cues end)", url, ua, "bytes=-65536")

    // ---------------------------------------------------------------- 4. engine end-to-end
    println("\n-- FfmpegPlayer.play(startMs=30000) → onStarted --")
    val startedLatch = CountDownLatch(1)
    var failedMessage: String? = null
    val engine =
        FfmpegPlayer(
            onStarted = { startedLatch.countDown() },
            onProgress = { _, _ -> },
            onEnded = { startedLatch.countDown() },
            onFailed = { m -> failedMessage = m; startedLatch.countDown() },
        )
    val engineStart = System.currentTimeMillis()
    engine.play(FfmpegPlayer.Source(url, resolved.headers, null), startMs = 30_000)
    val gotSignal = startedLatch.await(30, TimeUnit.SECONDS)
    val engineMs = System.currentTimeMillis() - engineStart
    engine.stop()
    println(
        if (failedMessage != null) "engine: FAILED after ${engineMs}ms — $failedMessage"
        else if (!gotSignal) "engine: no signal within 30s"
        else "engine play→first-audio: ${engineMs}ms"
    )

    // ---------------------------------------------------------------- 5. video seek matrix
    println("\n-- video: time to first frame --")
    val video = StreamResolver.resolveVideo(videoId)
    if (video == null) {
        println("  no video format available")
    } else {
        println("  video: ${video.width}x${video.height}@${video.fps} itag=${video.itag}")
        videoCase("video baseline (no -ss)", ffmpeg, video, "400000", seek = false)
        videoCase("video -ss 10 (analyzeduration=400000)", ffmpeg, video, "400000", seek = true)
        videoCase("video -ss 10 (analyzeduration=0)", ffmpeg, video, "0", seek = true)
        videoCase("video -ss 60 (analyzeduration=0)", ffmpeg, video, "0", seek = true, atSec = 60)
    }

    println("\ndiagnostic complete")
}

/** Decodes one frame of [video] (optionally after `-ss`) and reports time-to-first-frame. */
private fun videoCase(
    name: String,
    ffmpeg: String,
    video: StreamResolver.VideoResolved,
    analyzeduration: String,
    seek: Boolean,
    atSec: Int = 10,
) {
    val command = mutableListOf(
        ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "error",
        "-user_agent", video.client.userAgent,
        "-probesize", "262144",
        "-analyzeduration", analyzeduration,
        "-fflags", "nobuffer",
        "-short_seek_size", "16384",
    )
    if (seek) command += listOf("-ss", atSec.toString())
    command += listOf(
        "-i", video.url,
        "-an", "-vf", "scale=320:180",
        "-frames:v", "1",
        "-f", "rawvideo", "-pix_fmt", "rgba",
        "pipe:1",
    )
    val start = System.currentTimeMillis()
    val proc = ProcessBuilder(command).redirectErrorStream(true).start()
    var bytes = 0L
    val buf = ByteArray(64 * 1024)
    val expected = 320L * 180 * 4
    proc.inputStream.use { stream ->
        while (bytes < expected) {
            val read = stream.read(buf)
            if (read <= 0) break
            bytes += read
        }
    }
    val elapsed = System.currentTimeMillis() - start
    proc.destroy()
    proc.waitFor()
    println("  $name → " + if (bytes >= expected) "${elapsed}ms" else "NO FRAME (${bytes}B)")
}

private fun runCase(name: String, args: MutableList<String>, url: String, seekSec: Double?) {
    val command = args.toMutableList()
    command += listOf("-i", url, "-vn", "-f", "s16le", "-acodec", "pcm_s16le",
        "-ar", "44100", "-ac", "2", "pipe:1")
    val start = System.currentTimeMillis()
    val proc = ProcessBuilder(command).redirectErrorStream(true).start()
    var bytes = 0L
    val buf = ByteArray(4 * 1024)
    proc.inputStream.use { stream ->
        while (bytes < 4096) {
            val read = stream.read(buf)
            if (read <= 0) break
            bytes += read
        }
    }
    val elapsed = System.currentTimeMillis() - start
    proc.destroy()
    proc.waitFor()
    val status =
        if (bytes >= 4096) "${elapsed}ms" else "NO OUTPUT after ${elapsed}ms ($bytes bytes)"
    println("  $name → $status")
}

/** Runs a seek with `-loglevel trace`, printing stderr lines matching seek/HTTP keywords. */
private fun traceSeek(args: MutableList<String>, url: String) {
    val command = args.toMutableList()
    command += listOf("-i", url, "-vn", "-f", "s16le", "-acodec", "pcm_s16le",
        "-ar", "44100", "-ac", "2", "pipe:1")
    val start = System.currentTimeMillis()
    val proc = ProcessBuilder(command).redirectErrorStream(false).start()

    val keywords =
        listOf("seek", "range", "offset", "cues", "cluster", "opening", "closing",
            "206", "416", "403", "404", "byte", "rescan", "reconn", "retry", "error",
            "position", "duration", "analyzing", "probing")

    val errThread =
        Thread {
            try {
                BufferedReader(InputStreamReader(proc.errorStream)).use { reader ->
                    var line: String?
                    var printed = 0
                    while (reader.readLine().also { line = it } != null) {
                        val l = line ?: continue
                        val low = l.lowercase()
                        if (printed < 90 && keywords.any { low.contains(it) }) {
                            println("  [+%5d ms] %s".format(System.currentTimeMillis() - start,
                                l.take(220)))
                            printed++
                        }
                    }
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }

    val buf = ByteArray(4 * 1024)
    var firstByteMs = -1L
    var bytes = 0L
    proc.inputStream.use { stream ->
        while (bytes < 4096) {
            val read = stream.read(buf)
            if (read <= 0) break
            if (firstByteMs < 0) firstByteMs = System.currentTimeMillis() - start
            bytes += read
        }
    }
    println("  → first PCM byte: ${if (firstByteMs >= 0) "${firstByteMs}ms" else "none"}")
    proc.destroy()
    proc.waitFor()
}

private fun probeRange(label: String, url: String, ua: String, range: String) {
    val start = System.currentTimeMillis()
    runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("Range", range)
        conn.setRequestProperty("User-Agent", ua)
        conn.connectTimeout = 8_000
        conn.readTimeout = 12_000
        val code = conn.responseCode
        val total = conn.contentLengthLong
        var read = 0L
        val buf = ByteArray(64 * 1024)
        conn.inputStream.use { stream ->
            while (read < 65_536) {
                val n = stream.read(buf)
                if (n <= 0) break
                read += n
            }
        }
        conn.disconnect()
        val elapsed = System.currentTimeMillis() - start
        println("  $label → HTTP $code, header-len=$total, read=$read in ${elapsed}ms")
    }
        .onFailure { e ->
            println("  $label → ${e.javaClass.simpleName}: ${e.message} " +
                "(${System.currentTimeMillis() - start}ms)")
        }
}
