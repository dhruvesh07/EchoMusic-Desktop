package echo.music.desktop.data

import com.music.innertube.YouTube
import com.music.innertube.models.BrowseEndpoint
import com.music.innertube.models.SongItem
import com.music.innertube.models.WatchEndpoint
import echo.music.desktop.playback.PlayableSong
import echo.music.desktop.playback.toPlayable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * The recommendation engine behind Home's **Made for you** shelf — the "what he loves / what he
 * listens (or watches) more" half, built entirely on top of the local [TasteModel].
 *
 * ## Where candidates come from
 *
 *  1. **Related-song graph** — for each top seed song we call upstream
 *     `YouTube.next(WatchEndpoint(videoId = …))`, which is YouTube Music's own auto-play/"Up next"
 *     queue for that exact video. Its model, `NextResult.items`, is a clean `List<SongItem>`
 *     (verified against `innertube/pages/NextPage.kt`), converted with `SongItem.toPlayable()`.
 *     Seeds are fetched in parallel; the first response's `relatedEndpoint` (a `BrowseEndpoint`)
 *     is kept as a fallback for `YouTube.related(…)`, whose `RelatedPage.songs` is also a clean
 *     `List<SongItem>` (verified against `innertube/pages/RelatedPage.kt`).
 *  2. **Search your top artists** — when the pool is thin, `YouTube.search(artist,
 *     SearchFilter.FILTER_SONG)` pulls songs straight from artists the taste model loves.
 *  3. **Listen again** — the taste model's `topSongs`, served straight from local metadata with
 *     zero network.
 *
 * If the taste model is empty (brand-new user, no history) this returns `emptyList()` — the UI
 * shows an honest hint instead of fake picks.
 *
 * ## Scoring
 *
 * For each deduped candidate (dedup by `videoId`):
 *
 * ```
 * score = 1.00 × artistAffinity          // normalized decayed taste for the candidate's artist
 *       + 0.30 × seedAffinity             // affinity of the seed/artist that surfaced it, decayed
 *                                         //   8% per seed position (better seeds weigh more)
 *       + 0.15 × freshness                // 1.0 never played, 0.5 not played in 7 days, else 0
 *       + min(titleSearchBoost, 0.25)     // you searched for this title — a mild positive
 *       − 0.50 × recentRepeat             // played within the last 48 h → repetition penalty
 *       − 0.05 × min(plays, 6)            // heard a lot already → gently step aside
 * ```
 *
 * Songs completed more than [MAX_COMPLETIONS] times are dropped entirely (the user already knows
 * them cold — re-recommending them is noise). The top `limit × 1.5` by score is shuffled and cut
 * to `limit`, so the shelf rotates like Spotify's daily mix instead of being a frozen top-10.
 *
 * Results are cached for [CACHE_TTL_MS] (empty results for [EMPTY_TTL_MS]); [invalidate] clears
 * the cache and is called by [TasteModel] whenever listening signals change.
 *
 * Pure JVM: every network call is wrapped, failures are logged-and-swallowed, and this function
 * **never throws into the UI** — a broken network yields whatever was collected (or nothing).
 */
object Recommendations {

    private const val CACHE_TTL_MS = 10L * 60L * 1000L
    private const val EMPTY_TTL_MS = 30_000L

    /** How many top songs seed the related-song graph. */
    private const val MAX_SEEDS = 4
    private const val MAX_ARTIST_SEARCHES = 3
    private const val MAX_PER_ARTIST_SEARCH = 10
    /** Fetch more sources only while the unique pool is smaller than `limit` × this. */
    private const val MIN_POOL_MULTIPLIER = 2
    /** Drop candidates completed more than this many times — they're already known cold. */
    private const val MAX_COMPLETIONS = 3

    private const val RECENT_REPEAT_MS = 48L * 60L * 60L * 1000L
    private const val FRESH_WINDOW_MS = 7L * 24L * 60L * 60L * 1000L

    private const val SEED_WEIGHT = 0.30f
    private const val FRESH_WEIGHT = 0.15f
    private const val REPEAT_WEIGHT = 0.50f
    private const val PLAY_PENALTY_PER = 0.05f
    private const val MAX_PLAY_PENALTY_COUNT = 6
    private const val TITLE_SEARCH_CAP = 0.25f
    /** Each additional seed contributes this much less than the previous one. */
    private const val SEED_POSITION_DECAY = 0.08f

    private data class Candidate(val song: PlayableSong, val sourceAffinity: Float)

    private data class Scored(val song: PlayableSong, val score: Float)

    /** One seed's `next()` response, plus its `related` endpoint for a breadth fallback. */
    private data class SeedBatch(
        val sourceAffinity: Float,
        val items: List<SongItem>,
        val relatedEndpoint: BrowseEndpoint?,
    )

    private data class Cache(val songs: List<PlayableSong>, val cachedAtMs: Long)

    @Volatile private var cache: Cache? = null

    /**
     * Drop the cached mix. Called by [TasteModel] whenever a playback/search signal lands so the
     * next shelf render reflects the newest taste; also safe to call from outside.
     */
    fun invalidate() {
        cache = null
    }

    /**
     * The single entry point for the Home shelf: up to [limit] personalized songs, or an empty
     * list when there isn't enough taste to recommend from (or the network failed). Never throws.
     *
     * Cached for [CACHE_TTL_MS] so revisiting Home within the session is instant and the mix stays
     * stable, then rebuilt (and re-shuffled) on the next request.
     */
    suspend fun madeForYou(limit: Int = 20): List<PlayableSong> {
        if (limit <= 0) return emptyList()
        cache?.let { hit ->
            val ttl = if (hit.songs.isEmpty()) EMPTY_TTL_MS else CACHE_TTL_MS
            if (System.currentTimeMillis() - hit.cachedAtMs < ttl) return hit.songs
        }
        val songs: List<PlayableSong> =
            try {
                withContext(Dispatchers.IO) { buildMix(limit) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Never let a recommendation failure reach the UI.
                emptyList()
            }
        cache = Cache(songs, System.currentTimeMillis())
        return songs
    }

    // ------------------------------------------------------------------ build

    private suspend fun buildMix(limit: Int): List<PlayableSong> {
        // No taste yet → nothing honest to recommend. The UI shows the cold-start hint instead.
        if (!TasteModel.hasTaste()) return emptyList()

        val now = System.currentTimeMillis()
        val topSongs = TasteModel.topSongs(100)
        val topArtists = TasteModel.topArtists(200)
        if (topSongs.isEmpty() && topArtists.isEmpty()) return emptyList()

        val maxSongAffinity = topSongs.maxOfOrNull { it.second } ?: 0f
        val maxArtistAffinity = topArtists.maxOfOrNull { it.second } ?: 0f
        val artistAffinity = HashMap<String, Float>(topArtists.size)
        for ((name, score) in topArtists) artistAffinity[name.lowercase()] = norm(score, maxArtistAffinity)

        val candidates = LinkedHashMap<String, Candidate>()
        fun add(videoId: String, candidate: Candidate) {
            if (videoId.isBlank() || candidates.containsKey(videoId)) return
            candidates[videoId] = candidate
        }

        // (3) Listen-again pool — local metadata only, works even offline.
        for ((song, score) in topSongs.take(limit)) {
            add(song.videoId, Candidate(song, norm(score, maxSongAffinity)))
        }

        // (1) Related-song graph seeded from the user's top songs. `YouTube.next` runs in
        // parallel per seed; its NextResult.items is the clean List<SongItem> we convert.
        val seeds = topSongs.take(MAX_SEEDS)
        val batches: List<SeedBatch> =
            if (seeds.isEmpty()) {
                emptyList()
            } else {
                coroutineScope {
                    seeds
                        .mapIndexed { index, (seed, score) ->
                            async {
                                val result =
                                    YouTube.next(WatchEndpoint(videoId = seed.videoId)).getOrNull()
                                SeedBatch(
                                    sourceAffinity =
                                        norm(score, maxSongAffinity) *
                                            (1f - SEED_POSITION_DECAY * index),
                                    items = result?.items.orEmpty(),
                                    relatedEndpoint = result?.relatedEndpoint,
                                )
                            }
                        }
                        .awaitAll()
                }
            }

        var relatedEndpoint: BrowseEndpoint? = null
        var relatedSourceAffinity = 0f
        for (batch in batches) {
            if (relatedEndpoint == null && batch.relatedEndpoint != null) {
                relatedEndpoint = batch.relatedEndpoint
                relatedSourceAffinity = batch.sourceAffinity
            }
            for (item in batch.items) {
                add(item.id, Candidate(item.toPlayable(), batch.sourceAffinity))
            }
        }

        val minPool = limit * MIN_POOL_MULTIPLIER

        // (2) Search your top artists for extra breadth when the pool is thin.
        if (candidates.size < minPool && topArtists.isNotEmpty()) {
            val searches =
                coroutineScope {
                    topArtists
                        .take(MAX_ARTIST_SEARCHES)
                        .map { (artist, score) ->
                            async {
                                val items =
                                    YouTube
                                        .search(artist, YouTube.SearchFilter.FILTER_SONG)
                                        .getOrNull()
                                        ?.items
                                        .orEmpty()
                                items.filterIsInstance<SongItem>() to norm(score, maxArtistAffinity)
                            }
                        }
                        .awaitAll()
                }
            for ((songs, affinity) in searches) {
                for (item in songs.take(MAX_PER_ARTIST_SEARCH)) {
                    add(item.id, Candidate(item.toPlayable(), affinity))
                }
            }
        }

        // (1b) One extra `related()` browse off the first seed's related endpoint if still thin.
        if (candidates.size < minPool && relatedEndpoint != null) {
            val page = YouTube.related(relatedEndpoint).getOrNull()
            for (item in page?.songs.orEmpty()) {
                add(item.id, Candidate(item.toPlayable(), relatedSourceAffinity))
            }
        }

        // ---------------------------------------------------------------- rank
        val scored = ArrayList<Scored>(candidates.size)
        for (candidate in candidates.values) {
            val song = candidate.song
            if (song.title.isBlank()) continue
            val signals = TasteModel.songSignals(song.videoId)
            if (signals != null && signals.completions > MAX_COMPLETIONS) continue

            var artistScore = 0f
            for (rawArtist in song.artists) {
                val value = artistAffinity[rawArtist.trim().lowercase()] ?: 0f
                if (value > artistScore) artistScore = value
            }

            val lastPlayedMs = signals?.lastPlayedMs ?: 0L
            val freshness =
                when {
                    signals == null -> 1f
                    now - lastPlayedMs > FRESH_WINDOW_MS -> 0.5f
                    else -> 0f
                }
            val recentRepeat = signals != null && now - lastPlayedMs < RECENT_REPEAT_MS
            val playPenalty = PLAY_PENALTY_PER * minOf(signals?.plays ?: 0, MAX_PLAY_PENALTY_COUNT)

            val score =
                artistScore +
                    SEED_WEIGHT * candidate.sourceAffinity +
                    FRESH_WEIGHT * freshness +
                    minOf(TasteModel.searchBoost(song.title), TITLE_SEARCH_CAP) -
                    REPEAT_WEIGHT * (if (recentRepeat) 1f else 0f) -
                    playPenalty
            scored.add(Scored(song, score))
        }

        if (scored.isEmpty()) return emptyList()
        // Keep the top slice, then shuffle it: the shelf rotates between loads instead of
        // showing an identical ranked list every time — Spotify's "fresh mix" feel.
        val poolSize = (limit * 3 / 2).coerceAtLeast(limit)
        return scored
            .sortedByDescending { it.score }
            .take(poolSize)
            .shuffled()
            .take(limit)
            .map { it.song }
    }

    /** Divide by the best score in the set so affinities land in 0..1 regardless of raw scale. */
    private fun norm(value: Float, max: Float): Float =
        if (max <= 0f) 0f else (value / max).coerceIn(0f, 1f)
}
