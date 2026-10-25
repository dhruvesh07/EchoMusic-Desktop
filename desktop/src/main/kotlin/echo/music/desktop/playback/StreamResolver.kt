package echo.music.desktop.playback

import com.music.innertube.YouTube
import com.music.innertube.models.YouTubeClient
import com.music.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_65_10
import com.music.innertube.models.YouTubeClient.Companion.IOS
import com.music.innertube.models.YouTubeClient.Companion.VISIONOS
import com.music.innertube.models.YouTubeLocale
import com.music.innertube.models.response.PlayerResponse

/**
 * Turns a videoId into a directly-playable stream URL: [resolve] picks the audio half,
 * [resolveVideo] the visual half of the same adaptive manifest (both halves are separate
 * googlevideo URLs — there is no muxed tier on this ladder).
 *
 * Port of the resolution half of upstream `YTPlayerUtils.playerResponseForPlayback`, but reduced
 * to the ladder that was *measured* to work from a plain JVM (2026-10 probe, see PORT.md):
 *
 *  1. VISIONOS — direct URL, no signature cipher, no `n`-transform, no PO token, serves the whole
 *     file (Range requests answer 206 to the last byte). This rung alone covers normal content.
 *  2. ANDROID_VR 1.65.10 / IOS — direct URLs but googlevideo answers 403 past ~1 MiB from most
 *     networks, so they are degraded fallbacks only (a preview, not playback grade).
 *
 * The APK's entire solver stack (yt.solver.core.js / player_configs.json / po_token.html BotGuard
 * WebView) exists for the WEB_REMIX tier, which is unreachable anonymously anyway — it is not
 * needed for this ladder.
 *
 * `visitorData` is mandatory: without `X-Goog-Visitor-Id` VISIONOS/ANDROID_VR answer
 * `UNPLAYABLE` / `LOGIN_REQUIRED` ("Sign in to confirm you're not a bot") with zero formats.
 */
object StreamResolver {

    /** Ordered by measured ability to serve a whole file. */
    private val LADDER: List<YouTubeClient> = listOf(VISIONOS, ANDROID_VR_1_65_10, IOS)

    data class Resolved(
        val videoId: String,
        val url: String,
        val itag: Int,
        val mimeType: String,
        val bitrateBps: Int,
        val contentLength: Long?,
        val expiresInSeconds: Int,
        val client: YouTubeClient,
    ) {
        /** Media fetch headers — googlevideo compares the fetch against the minting client. */
        val headers: Map<String, String>
            get() = mapOf("User-Agent" to client.userAgent)
    }

    /**
     * A video-only rendition of the same manifest [resolve] reads: the visual half of an adaptive
     * stream (there is no muxed mp4 tier on this ladder, so audio and video are fetched as two
     * independent URLs and only the video half is decoded here).
     *
     * [width]/[height]/[fps] describe the SOURCE, not what gets drawn — `VideoEngine` clamps the
     * rendered height to 480p itself and computes the exact output size the raw pipeline needs.
     */
    data class VideoResolved(
        val videoId: String,
        val url: String,
        val itag: Int,
        val mimeType: String,
        val bitrateBps: Int,
        val width: Int,
        val height: Int,
        val fps: Int,
        val contentLength: Long?,
        val client: YouTubeClient,
    ) {
        /** Media fetch headers — googlevideo compares the fetch against the minting client. */
        val headers: Map<String, String>
            get() = mapOf("User-Agent" to client.userAgent)
    }

    @Volatile private var initialized = false

    /** Call once before the first resolve(). Cheap: one GET to music.youtube.com/sw.js_data. */
    suspend fun init() {
        if (initialized) return
        YouTube.locale = YouTubeLocale(gl = "US", hl = "en")
        runCatching { YouTube.refreshVisitorData() }
        initialized = true
    }

    suspend fun resolve(videoId: String, maxKbps: Int = 160): Resolved? {
        init()
        if (YouTube.visitorData.isNullOrBlank()) runCatching { YouTube.refreshVisitorData() }

        for (client in LADDER) {
            val response =
                YouTube.player(videoId = videoId, playlistId = null, client = client).getOrNull()
                    ?: continue
            if (response.playabilityStatus.status != "OK") continue

            val format = selectFormat(response, maxKbps) ?: continue
            val url = format.url
            if (url.isNullOrEmpty()) continue // ciphered/SABR-only → not this tier

            return Resolved(
                videoId = videoId,
                url = url,
                itag = format.itag,
                mimeType = format.mimeType,
                bitrateBps = format.bitrate,
                contentLength = format.contentLength,
                expiresInSeconds = response.streamingData?.expiresInSeconds ?: 21600,
                client = client,
            )
        }
        return null
    }

    suspend fun resolveVideo(videoId: String, maxHeight: Int = 480): VideoResolved? {
        init()
        if (YouTube.visitorData.isNullOrBlank()) runCatching { YouTube.refreshVisitorData() }

        for (client in LADDER) {
            val response =
                YouTube.player(videoId = videoId, playlistId = null, client = client).getOrNull()
                    ?: continue
            if (response.playabilityStatus.status != "OK") continue

            val format = selectVideoFormat(response, maxHeight) ?: continue
            val url = format.url
            if (url.isNullOrEmpty()) continue // ciphered/SABR-only → not this tier

            val width = format.width ?: continue
            val height = format.height ?: continue
            if (width <= 0 || height <= 0) continue

            return VideoResolved(
                videoId = videoId,
                url = url,
                itag = format.itag,
                mimeType = format.mimeType,
                bitrateBps = format.bitrate,
                width = width,
                height = height,
                fps = format.fps?.takeIf { it > 0 }?.coerceAtMost(60) ?: 30,
                contentLength = format.contentLength,
                client = client,
            )
        }
        return null
    }

    /**
     * Mirrors [selectFormat] for the visual tier: video-only renditions (`!isAudio`, i.e. a
     * declared width/height) with a directly-addressable URL, mp4/H.264 preferred over webm/VP9
     * because ffmpeg decodes it everywhere, then tallest-first under the caller's ceiling —
     * falling back to 720p and finally to whatever exists when nothing fits.
     */
    private fun selectVideoFormat(
        response: PlayerResponse,
        maxHeight: Int,
    ): PlayerResponse.StreamingData.Format? {
        val video =
            response.streamingData
                ?.adaptiveFormats
                .orEmpty()
                .filter {
                    !it.isAudio &&
                        !it.url.isNullOrEmpty() &&
                        (it.mimeType.startsWith("video/mp4") ||
                            it.mimeType.startsWith("video/webm"))
                }
        if (video.isEmpty()) return null
        val mp4 = video.filter { it.mimeType.startsWith("video/mp4") }
        val pool = if (mp4.isNotEmpty()) mp4 else video
        return pool.filter { (it.height ?: 0) in 1..maxHeight }.maxByOrNull { it.height ?: 0 }
            ?: pool.filter { (it.height ?: 0) in 1..720 }.maxByOrNull { it.height ?: 0 }
            ?: pool.maxByOrNull { it.height ?: 0 }
    }

    /**
     * Mirrors upstream `YTPlayerUtils.findFormat`: audio-only, not auto-dubbed, under the quality
     * ceiling, highest bitrate with a +10240 bias toward webm/opus.
     */
    private fun selectFormat(
        response: PlayerResponse,
        maxKbps: Int,
    ): PlayerResponse.StreamingData.Format? {
        val ceiling = if (maxKbps >= Int.MAX_VALUE) Int.MAX_VALUE else maxKbps * 1000 + 5_000
        val audio =
            response.streamingData
                ?.adaptiveFormats
                .orEmpty()
                .filter {
                    it.isAudio &&
                        it.isOriginal &&
                        !it.url.isNullOrEmpty() &&
                        (it.mimeType.startsWith("audio/webm") || it.mimeType.startsWith("audio/mp4"))
                }
                .filter { it.bitrate <= ceiling }
        if (audio.isEmpty()) return null
        return audio.maxByOrNull {
            it.bitrate + (if (it.mimeType.startsWith("audio/webm")) 10_240 else 0)
        }
    }
}
