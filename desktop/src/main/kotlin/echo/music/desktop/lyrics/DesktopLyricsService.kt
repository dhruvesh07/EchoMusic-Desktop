package echo.music.desktop.lyrics

import echo.music.iad1tya.betterlyrics.BetterLyrics
import com.music.kugou.KuGou
import com.music.lrclib.LrcLib
import com.music.simpmusic.SimpMusicLyrics
import com.music.echo.unison.Unison
import com.music.youlyplus.YouLyPlus
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Desktop port of upstream `LyricsHelper`
 * (`lyrics/src/main/kotlin/echo/music/iad1tya/lyrics/LyricsHelper.kt`).
 *
 * Upstream races every enabled provider concurrently and returns the first *synced* result,
 * falling back to the first unsynced one. This reproduces that algorithm, but calls the
 * individual provider modules directly instead of the `:lyrics` aggregator, which is coupled
 * to Android `Context`/Hilt/DataStore.
 *
 * Provider order mirrors `LyricsProviderRegistry.getDefaultProviderOrder()` for the
 * context-free providers; the Innertube-backed YouTube providers re-attach in phase 3.
 */
class DesktopLyricsService {

    /** Everything a provider needs for one lookup — replaces upstream's `MediaMetadata` coupling. */
    data class Query(
        val videoId: String? = null,
        val title: String,
        val artist: String,
        val durationMs: Long = -1L,
        val album: String? = null,
    ) {
        val durationSeconds: Int = if (durationMs <= 0) -1 else (durationMs / 1000).toInt()
    }

    /** Same shape as upstream's `LyricsWithProvider`. */
    data class Result(val lyrics: String?, val providerName: String)

    private class Candidate(val name: String, val fetch: suspend (Query) -> String)

    private val providers: List<Candidate> =
        listOf(
            Candidate("YouLyPlus") { q ->
                YouLyPlus.getLyrics(q.title, q.artist, q.durationSeconds, q.album, q.videoId)
                    .getOrThrow()
            },
            Candidate("Unison") { q ->
                Unison.getLyrics(q.videoId, q.title, q.artist, q.album, q.durationSeconds)
                    .getOrThrow()
            },
            Candidate("BetterLyrics") { q ->
                BetterLyrics.getLyrics(q.title, q.artist, q.durationSeconds, q.album).getOrThrow()
            },
            Candidate("SimpMusic") { q ->
                val id =
                    requireNotNull(q.videoId) { "SimpMusic requires a videoId" }
                SimpMusicLyrics.getLyrics(id, q.durationSeconds).getOrThrow()
            },
            Candidate("LrcLib") { q ->
                LrcLib.getLyrics(q.title, q.artist, q.durationSeconds, q.album).getOrThrow()
            },
            Candidate("KuGou") { q ->
                KuGou.getLyrics(q.title, q.artist, q.durationSeconds, q.album).getOrThrow()
            },
        )

    private val cache =
        object : LinkedHashMap<String, Result>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Result>) =
                size > MAX_CACHE
        }
    private val cacheLock = Mutex()
    private val inFlight = HashMap<String, kotlinx.coroutines.Deferred<Result>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun getLyrics(query: Query): Result = withContext(Dispatchers.IO) {
        val key = cacheKey(query)
        cacheLock.withLock { cache[key] }?.let { return@withContext it }

        // Shared fetch: concurrent callers await one in-flight request (upstream behaviour,
        // so a cancelled caller can't fail the others waiting on the same lyrics).
        val created = scope.async(start = CoroutineStart.LAZY) { raceProviders(query) }
        val existing = synchronized(inFlight) { inFlight.putIfAbsent(key, created) }
        val shared =
            existing
                ?: created.also { deferred ->
                    deferred.invokeOnCompletion {
                        synchronized(inFlight) { inFlight.remove(key, deferred) }
                    }
                    deferred.start()
                }
        if (existing != null) created.cancel()

        shared.await().also { result ->
            if (!result.lyrics.isNullOrBlank() && result.lyrics != NOT_FOUND) {
                cacheLock.withLock { cache[key] = result }
            }
        }
    }

    /** Race all providers; first synced result wins, else first non-blank unsynced result. */
    private suspend fun raceProviders(query: Query): Result = coroutineScope {
        val channel = Channel<Result?>(providers.size)
        providers.forEach { provider ->
            launch {
                val lyrics =
                    if (provider.name == "SimpMusic" && query.videoId == null) null
                    else runCatching { provider.fetch(query) }.getOrNull()
                val result =
                    lyrics
                        ?.takeIf { it != NOT_FOUND && it.isNotBlank() }
                        ?.let { Result(it, provider.name) }
                channel.send(result)
            }
        }

        val unsynced = mutableListOf<Result>()
        repeat(providers.size) {
            val result = channel.receive() ?: return@repeat
            if (hasSyncedLyrics(result.lyrics)) {
                coroutineContext.cancelChildren()
                return@coroutineScope result
            }
            unsynced.add(result)
        }
        unsynced.firstOrNull() ?: Result(NOT_FOUND, "Unknown")
    }

    private fun cacheKey(q: Query) = "${q.videoId}|${q.title}|${q.artist}|${q.durationMs}|${q.album}"

    companion object {
        /** Upstream `LyricsEntity.LYRICS_NOT_FOUND` sentinel. */
        const val NOT_FOUND = "LRCLIB_NOT_FOUND"
        private const val MAX_CACHE = 64

        /**
         * Mirrors upstream `LyricsUtils.hasSyncedLyrics`: an LRC body carries time tags
         * (`[mm:ss.xx]`) at line starts.
         */
        fun hasSyncedLyrics(lyrics: String?): Boolean {
            if (lyrics.isNullOrBlank()) return false
            return lyrics.lineSequence().any { line ->
                val t = line.trimStart()
                if (t.length <= 8 || t[0] != '[') return@any false
                val close = t.indexOf(']')
                val colon = t.indexOf(':')
                close > 1 && colon in 1 until close && t.substring(1, colon).all(Char::isDigit)
            }
        }
    }
}
