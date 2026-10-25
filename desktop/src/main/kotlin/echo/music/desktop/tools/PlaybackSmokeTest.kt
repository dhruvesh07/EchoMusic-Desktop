package echo.music.desktop.tools

import echo.music.desktop.playback.FfmpegPlayer
import echo.music.desktop.playback.Playback
import echo.music.desktop.playback.StreamResolver
import echo.music.desktop.playback.VideoEngine
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Headless verification of the whole playback chain, run with:
 *
 *     gradlew :desktop:smokeTest
 *
 * Steps: mint visitor data → VISIONOS player response → pick the audio format → Range-fetch the
 * first bytes of the stream → decode 3 s of audio through ffmpeg into PCM. Proves the app can
 * actually play a track without needing someone to click the UI.
 */
fun main() = runBlocking {
    println("== Echo Music playback smoke test ==")

    val videoId = System.getProperty("smoke.videoId", "dQw4w9WgXcQ")
    println("videoId: $videoId")

    val started = System.currentTimeMillis()
    val resolved = StreamResolver.resolve(videoId)
    val elapsed = System.currentTimeMillis() - started

    if (resolved == null) {
        println("FAIL: no stream resolved after ${elapsed}ms (all ladder tiers exhausted)")
        kotlin.system.exitProcess(1)
    }

    println("resolved in ${elapsed}ms")
    println("  client   : ${resolved.client.clientName} ${resolved.client.clientVersion}")
    println("  itag     : ${resolved.itag}")
    println("  mime     : ${resolved.mimeType}")
    println("  bitrate  : ${resolved.bitrateBps} bps")
    println("  length   : ${resolved.contentLength ?: "unknown"} bytes")
    println("  expiry   : ${resolved.expiresInSeconds}s")
    println("  url      : ${resolved.url.take(96)}…")

    // 1) Can we fetch bytes with the minting client's headers?
    val rangeStatus: Int
    var rangeBytes = 0
    with(URL(resolved.url).openConnection() as HttpURLConnection) {
        setRequestProperty("User-Agent", resolved.client.userAgent)
        setRequestProperty("Range", "bytes=0-65535")
        connectTimeout = 15_000
        readTimeout = 15_000
        rangeStatus = responseCode
        val buffer = ByteArray(16 * 384)
        val stream = try {
            inputStream
        } catch (e: Exception) {
            errorStream
        }
        stream.use {
            while (true) {
                val read = it.read(buffer)
                if (read <= 0) break
                rangeBytes += read
                if (rangeBytes >= 65_536) break
            }
        }
        disconnect()
    }
    println("range fetch: HTTP $rangeStatus, $rangeBytes bytes")
    if (rangeStatus !in 200..206 || rangeBytes == 0) {
        println("FAIL: stream not fetchable")
        kotlin.system.exitProcess(1)
    }

    // 2) Decode 3 s of audio to PCM — proves codec + network + pipeline.
    val ffmpeg = FfmpegPlayer.findFfmpeg() ?: run {
        println("FAIL: ffmpeg not found")
        kotlin.system.exitProcess(1)
    }
    val command = listOf(
        ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "error",
        "-user_agent", resolved.client.userAgent,
        "-i", resolved.url,
        "-vn", "-map", "0:a:0", "-t", "3",
        "-f", "s16le", "-acodec", "pcm_s16le", "-ar", "44100", "-ac", "2",
        "pipe:1",
    )
    val decodeStart = System.currentTimeMillis()
    val process = ProcessBuilder(command).redirectErrorStream(false).start()
    var pcmBytes = 0L
    val buffer = ByteArray(32 * 1024)
    val stderr = StringBuilder()
    val stderrThread = Thread {
        try {
            process.errorStream.bufferedReader().use { r ->
                var line: String?
                while (r.readLine().also { line = it } != null) {
                    synchronized(stderr) { stderr.append(line).append('\n') }
                }
            }
        } catch (_: Exception) {}
    }.apply { isDaemon = true; start() }
    process.inputStream.use { stream ->
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            pcmBytes += read
        }
    }
    process.waitFor()
    val decodeElapsed = System.currentTimeMillis() - decodeStart
    // 44100 Hz × 2ch × 2 bytes × 3 s = 529 200 bytes for a full 3 seconds.
    val seconds = pcmBytes / (44100.0 * 2 * 2)
    println("decoded $pcmBytes bytes of PCM (${"%.2f".format(seconds)} s) in ${decodeElapsed}ms, exit=${process.exitValue()}")

    if (pcmBytes < 44_100L) { // less than 0.25 s of audio → effectively nothing
        println("FAIL: ffmpeg produced (almost) no audio")
        println(synchronized(stderr) { stderr.toString() }.take(2000))
        kotlin.system.exitProcess(1)
    }

    println("PASS: resolution, streaming and decoding all work")

    // ---------------------------------------------------------------- phase 3
    // Drive the real Playback controller exactly like a UI click does: play a 20-track queue
    // starting in the middle (the case that regressed once), then pause, resume, next, seek.
    // Audio is muted so the test is silent.
    println("\n== Playback controller test (audio muted) ==")
    Playback.setVolume(0f)

    val queue =
        (0 until 20).map { i ->
            echo.music.desktop.playback.PlayableSong(
                videoId = videoId,
                title = "Track $i",
                artists = listOf("Tester"),
                thumbnail = null,
                durationMs = 180_000L,
            )
        }

    fun check(name: String, ok: Boolean, state: echo.music.desktop.playback.Playback.State) {
        if (ok) {
            println("  ok   $name")
        } else {
            println("  FAIL $name  (state=$state)")
            Playback.stop()
            kotlin.system.exitProcess(1)
        }
    }

    // startIndex 19 == songs.lastIndex: the exact call shape that used to throw
    // "Cannot coerce value to an empty range".
    val playStart = System.currentTimeMillis()
    Playback.playQueue(queue, startIndex = 19, expanded = true)

    var state = Playback.state.value
    val playingNow = await(30_000, "starts playing") { s ->
        s.isPlaying && !s.isBuffering && s.error == null
    }
    val firstPlayMs = System.currentTimeMillis() - playStart
    println("  first play → audible: ${firstPlayMs}ms")
    state = Playback.state.value
    check("queue of 20, startIndex=19 starts playing", playingNow, state)
    check("current track is the 20th", state.index == 19 && state.current?.title == "Track 19", state)

    delay(2_500)
    state = Playback.state.value
    check("position advances (${state.positionMs}ms)", state.positionMs > 0, state)

    Playback.togglePlay()
    delay(600)
    state = Playback.state.value
    check("pause stops playback", !state.isPlaying, state)

    Playback.togglePlay()
    delay(1_200)
    state = Playback.state.value
    check("resume plays again", state.isPlaying, state)

    // ---- seek latency: the user-visible contract (click a point on the timeline → audio is
    // actually playing there). isBuffering now stays true until the FIRST PCM chunk arrives, so
    // this measures real time-to-audio, not time-to-process-spawn.
    val seekStart = System.currentTimeMillis()
    Playback.seekTo(30_000)
    val seeked = await(20_000, "seek lands") { s ->
        !s.isBuffering && s.isPlaying && s.positionMs > 0
    }
    val seekLatencyMs = System.currentTimeMillis() - seekStart
    state = Playback.state.value
    check("seek to 30s re-buffers and plays", seeked, state)
    check("seek position is near 30s (${state.positionMs}ms)",
        state.positionMs in 20_000..60_000, state)
    println("  seek latency (seekTo → audio at target): ${seekLatencyMs}ms")
    check("seek latency under 5000ms (${seekLatencyMs}ms)", seekLatencyMs in 1..5_000, state)

    // ---- rapid double-seek: only the LAST jump may win. The stale-job race used to let the
    // superseded load restart playback from 0:00 while the UI showed the seeked position.
    Playback.seekTo(90_000)
    Playback.seekTo(60_000)
    val doubleSeeked = await(20_000, "double seek lands") { s ->
        !s.isBuffering && s.isPlaying && s.positionMs > 0
    }
    delay(1_500)
    state = Playback.state.value
    check("rapid double-seek settles", doubleSeeked, state)
    check("double-seek honours the LAST target, ~60s (${state.positionMs}ms)",
        state.positionMs in 45_000..75_000, state)

    Playback.stop()
    state = Playback.state.value
    check("stop clears the queue", state.current == null && !state.isPlaying, state)

    println("PASS: playback controller (play/pause/seek/queue) works")

    // ---------------------------------------------------------------- phase 4: Video manifest resolution
    println("\n== Video stream resolution test ==")
    val videoResolveStart = System.currentTimeMillis()
    val video = StreamResolver.resolveVideo(videoId)
    val videoResolveMs = System.currentTimeMillis() - videoResolveStart
    if (video == null) {
        println("FAIL: no video format resolved in ${videoResolveMs}ms (artwork-only would be permanent)")
        kotlin.system.exitProcess(1)
    }
    println("video resolved in ${videoResolveMs}ms: " +
        "${video.width}x${video.height}@${video.fps} itag=${video.itag} " +
        video.mimeType.substringBefore(';'))

    check("video resolution successful", video.url.isNotEmpty(), Playback.state.value)
    println("PASS: video manifest resolved successfully")
    println("\nALL CHECKS PASSED")
}

private suspend fun await(
    timeoutMs: Long,
    what: String,
    predicate: (echo.music.desktop.playback.Playback.State) -> Boolean,
): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (predicate(echo.music.desktop.playback.Playback.state.value)) return true
        kotlinx.coroutines.delay(100)
    }
    println("  (timed out after ${timeoutMs}ms waiting for: $what)")
    return false
}
