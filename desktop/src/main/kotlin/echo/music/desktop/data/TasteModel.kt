package echo.music.desktop.data

import echo.music.desktop.playback.PlayableSong
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Local taste model — the "trained on what he loves / what he listens more" half of the
 * personalized recommendation feature (Spotify's *Made for you* equivalent).
 *
 * Everything lives in one JSON document, `~/.echo-music/taste.json`, written in the same
 * convention as [PlayHistory] (kotlinx.serialization, corrupt-tolerant, `@Synchronized`), so the
 * model survives restarts and can be exported/restored verbatim for cloud sync.
 *
 * ## The signals and what they mean
 *
 * Per `videoId` we accumulate ([SongStats]):
 *
 *  - **plays** (+1, [onTrackStarted]) — playback began. A weak positive: the user queued it or
 *    let it start.
 *  - **completions** (+2, [onTrackCompleted]) — the track reached its natural end. The strongest
 *    positive we can observe: they let the whole thing play.
 *  - **skips** (−0.75, [onTrackSkipped] within [EARLY_SKIP_MS]) — abandoned early. A skip near the
 *    *end* of a song is basically a completion with extra steps, so only early rejections count as
 *    a negative signal.
 *  - **totalListenedMs / lastPlayedMs** — how much was heard and when the newest signal arrived.
 *  - **song** — the last-known [PlayableSong] metadata, so shelves can be rebuilt offline without
 *    re-fetching anything from YouTube.
 *
 * Raw affinity is `2*completions + 1*plays − 0.75*skips`, clamped at ≥ 0.
 *
 * ## Why the time decay exists
 *
 * A signal's contribution is multiplied by `0.5 ^ (daysSince(lastPlayed) / 30)` — a 30-day
 * half-life applied **at read time** ([decayedAffinity]). Without it the model freezes on habits
 * from months ago; with it, what the user listens to *recently* dominates, which is exactly what
 * makes the shelf feel like it tracks "what he listens more" instead of "what he once played".
 *
 * ## Cold start
 *
 * On first use (no store yet) the model is backfilled from [PlayHistory] so an existing user gets
 * recommendations immediately instead of an empty shelf ([backfillFromHistory]).
 *
 * ## Search terms
 *
 * [recordSearch] keeps the last [MAX_SEARCH_TERMS] distinct queries (most-recent-first) with
 * occurrence counts. At read time a searched term gently boosts any artist/title that matches it
 * case-insensitively ([searchBoost]) — searching for an artist is a deliberate statement of
 * interest, weaker than actually playing their songs, but real.
 */
object TasteModel {

    /**
     * Read-only snapshot of one song's accumulated signals, as of the moment of the query —
     * used by `Recommendations` for repetition penalties and freshness bonuses.
     */
    data class SongSignals(
        val plays: Int,
        val completions: Int,
        val skips: Int,
        val lastPlayedMs: Long,
        val affinity: Float,
    )

    /** Per-song counters, persisted keyed by `videoId`. See the object KDoc for signal meanings. */
    @Serializable
    internal data class SongStats(
        var plays: Int = 0,
        var completions: Int = 0,
        var skips: Int = 0,
        var totalListenedMs: Long = 0L,
        var lastPlayedMs: Long = 0L,
        var song: PlayableSong? = null,
    )

    /** Whole persisted document — also the exact shape [exportJson]/[restoreJson] move around. */
    @Serializable
    internal data class TasteStore(
        val songs: Map<String, SongStats> = emptyMap(),
        val searchTerms: Map<String, Int> = emptyMap(),
    )

    /** Strongest positive: the track played all the way through. */
    private const val COMPLETION_WEIGHT = 2f
    /** Baseline positive: playback started. */
    private const val START_WEIGHT = 1f
    /** Negative: rejected before [EARLY_SKIP_MS]. */
    private const val SKIP_WEIGHT = -0.75f
    /** A "skip" only becomes a negative signal below this threshold. */
    private const val EARLY_SKIP_MS = 30_000L
    /** Half-life of every signal, in days — see the object KDoc for why decay exists. */
    private const val HALF_LIFE_DAYS = 30.0
    private const val MS_PER_DAY = 86_400_000L
    /** Per-occurrence weight of a matching search term, capped at [SEARCH_BOOST_MAX_COUNT]. */
    private const val SEARCH_BOOST_PER_COUNT = 0.1f
    private const val SEARCH_BOOST_MAX_COUNT = 5
    private const val MAX_SEARCH_TERMS = 100
    private const val MIN_SEARCH_TERM_LENGTH = 3

    private val directory = File(System.getProperty("user.home"), ".echo-music")
    private val file = File(directory, "taste.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** videoId → counters. Insertion order is irrelevant here; recency lives in `lastPlayedMs`. */
    private val songs = LinkedHashMap<String, SongStats>()

    /** lowercased query → occurrence count. LinkedHashMap keeps recency so we can evict the eldest. */
    private val searchTerms = LinkedHashMap<String, Int>()

    init {
        runCatching {
            if (file.exists()) {
                val store = json.decodeFromString<TasteStore>(file.readText())
                songs.putAll(store.songs)
                searchTerms.putAll(store.searchTerms)
            }
        }
            .onFailure {
                // A corrupt taste file must never take the app down with it.
                runCatching { file.delete() }
            }
        backfillFromHistory()
    }

    // ------------------------------------------------------------------ playback signals

    /** Playback of [song] began → +[START_WEIGHT]. */
    @Synchronized
    fun onTrackStarted(song: PlayableSong) {
        mutate(song) { it.plays += 1 }
    }

    /** [song] reached its natural end → +[COMPLETION_WEIGHT]. */
    @Synchronized
    fun onTrackCompleted(song: PlayableSong) {
        mutate(song) { it.completions += 1 }
    }

    /**
     * The user hit next. Only abandoning the track before [EARLY_SKIP_MS] counts as a −0.75
     * rejection signal; skipping after that just records listening time (they heard most of it).
     */
    @Synchronized
    fun onTrackSkipped(song: PlayableSong, listenedMs: Long) {
        mutate(song) { stats ->
            stats.totalListenedMs += listenedMs.coerceAtLeast(0L)
            if (listenedMs < EARLY_SKIP_MS) stats.skips += 1
        }
    }

    /**
     * Record a search query (case-insensitive, last [MAX_SEARCH_TERMS] distinct terms kept,
     * most-recent-first). Searches boost matching artists/songs slightly at read time — see
     * [searchBoost].
     */
    @Synchronized
    fun recordSearch(query: String) {
        val term = query.trim().lowercase()
        if (term.isBlank()) return
        // Re-insert so the map stays ordered most-recent-first for eviction.
        val count = (searchTerms.remove(term) ?: 0) + 1
        searchTerms[term] = count
        while (searchTerms.size > MAX_SEARCH_TERMS) {
            val eldest = searchTerms.keys.firstOrNull() ?: break
            searchTerms.remove(eldest)
        }
        save()
        Recommendations.invalidate()
    }

    // ------------------------------------------------------------------ read side

    /** True once the model knows at least one song — gates whether a mix can be built at all. */
    @Synchronized
    fun hasTaste(): Boolean = songs.isNotEmpty()

    /**
     * Artist → decayed affinity score, strongest first. Song affinity is summed across every
     * song the artist appears on, then a [searchBoost] for the artist name is added on top.
     */
    @Synchronized
    fun topArtists(limit: Int): List<Pair<String, Float>> {
        if (limit <= 0) return emptyList()
        val now = System.currentTimeMillis()
        val totals = LinkedHashMap<String, Float>()
        for (stats in songs.values) {
            val song = stats.song ?: continue
            val affinity = decayedAffinity(stats, now)
            if (affinity <= 0f) continue
            for (rawArtist in song.artists) {
                val artist = rawArtist.trim()
                if (artist.isEmpty()) continue
                totals[artist] = (totals[artist] ?: 0f) + affinity
            }
        }
        return totals
            .map { (artist, score) -> artist to score + searchBoost(artist) }
            .sortedByDescending { it.second }
            .take(limit)
    }

    /**
     * Song → decayed affinity score, strongest first. Songs without known metadata are skipped
     * (nothing to render a card from). A [searchBoost] matching the title is added on top.
     */
    @Synchronized
    fun topSongs(limit: Int): List<Pair<PlayableSong, Float>> {
        if (limit <= 0) return emptyList()
        val now = System.currentTimeMillis()
        return songs.values.mapNotNull { stats ->
            val song = stats.song ?: return@mapNotNull null
            val affinity = decayedAffinity(stats, now) + searchBoost(song.title)
            if (affinity <= 0f) null else song to affinity
        }
            .sortedByDescending { it.second }
            .take(limit)
    }

    /** Signals for one song (null if never seen) — drives repetition/freshness in ranking. */
    @Synchronized
    fun songSignals(videoId: String): SongSignals? {
        val stats = songs[videoId] ?: return null
        return SongSignals(
            plays = stats.plays,
            completions = stats.completions,
            skips = stats.skips,
            lastPlayedMs = stats.lastPlayedMs,
            affinity = decayedAffinity(stats, System.currentTimeMillis()),
        )
    }

    /**
     * How much [label] (an artist or song title) is "worth" in search intent: every distinct
     * searched term that occurs in [label] (or contains it) contributes
     * `SEARCH_BOOST_PER_COUNT * min(count, SEARCH_BOOST_MAX_COUNT)`. Case-insensitive both ways,
     * so searching "weeknd" boosts the artist "The Weeknd".
     */
    @Synchronized
    fun searchBoost(label: String): Float {
        val needle = label.trim().lowercase()
        if (needle.length < MIN_SEARCH_TERM_LENGTH || searchTerms.isEmpty()) return 0f
        var boost = 0f
        for ((term, count) in searchTerms) {
            if (term.length < MIN_SEARCH_TERM_LENGTH) continue
            if (needle.contains(term) || term.contains(needle)) {
                boost += SEARCH_BOOST_PER_COUNT * minOf(count, SEARCH_BOOST_MAX_COUNT)
            }
        }
        return boost
    }

    // ------------------------------------------------------------------ cloud sync

    /** Whole store as JSON (for backup/export); `{}` on any failure. */
    @Synchronized
    fun exportJson(): String =
        runCatching { json.encodeToString(snapshot()) }.getOrElse { "{}" }

    /**
     * Replace the whole store with a downloaded copy. Corrupt/absent input is ignored (the
     * current model is kept rather than wiped) — same tolerance as [PlayHistory.load].
     */
    @Synchronized
    fun restoreJson(json: String) {
        runCatching {
            val store = this.json.decodeFromString<TasteStore>(json)
            songs.clear()
            songs.putAll(store.songs)
            searchTerms.clear()
            searchTerms.putAll(store.searchTerms)
            save()
            Recommendations.invalidate()
        }
    }

    // ------------------------------------------------------------------ internals

    /**
     * Cold start: if nothing is known yet, seed the model from [PlayHistory] so a user who has
     * played music before gets recommendations immediately. Entries arrive most-recent-first and
     * are spaced one day apart, which both satisfies "treat each history entry as a play" and
     * lets the decay naturally rank the most recent listens highest.
     */
    private fun backfillFromHistory() {
        if (songs.isNotEmpty()) return
        val history = runCatching { PlayHistory.load() }.getOrDefault(emptyList())
        if (history.isEmpty()) return
        val now = System.currentTimeMillis()
        history.forEachIndexed { index, song ->
            if (song.videoId.isBlank() || songs.containsKey(song.videoId)) return@forEachIndexed
            songs[song.videoId] =
                SongStats(
                    plays = 1,
                    lastPlayedMs = now - index * MS_PER_DAY,
                    song = song,
                )
        }
        save()
    }

    /** Applies [change], refreshes metadata/timestamp, persists and invalidates the mix cache. */
    private fun mutate(song: PlayableSong, change: (SongStats) -> Unit) {
        if (song.videoId.isBlank()) return
        val stats = songs.getOrPut(song.videoId) { SongStats() }
        change(stats)
        stats.song = song
        stats.lastPlayedMs = System.currentTimeMillis()
        save()
        Recommendations.invalidate()
    }

    /** Raw weighted signal, then the 30-day half-life applied to its most recent activity. */
    private fun decayedAffinity(stats: SongStats, nowMs: Long): Float {
        val raw =
            COMPLETION_WEIGHT * stats.completions +
                START_WEIGHT * stats.plays +
                SKIP_WEIGHT * stats.skips
        if (raw <= 0f) return 0f
        return (raw * decay(stats.lastPlayedMs, nowMs)).coerceAtLeast(0f)
    }

    /** `0.5 ^ (daysSince(lastPlayed) / 30)` — recent taste dominates, stale taste fades. */
    private fun decay(lastPlayedMs: Long, nowMs: Long): Float {
        if (lastPlayedMs <= 0L) return 1f
        val days = (nowMs - lastPlayedMs).toDouble() / MS_PER_DAY
        if (days <= 0.0) return 1f
        return Math.pow(0.5, days / HALF_LIFE_DAYS).toFloat()
    }

    private fun snapshot(): TasteStore =
        TasteStore(songs = songs.toMap(), searchTerms = searchTerms.toMap())

    /** Persist the store; failures are swallowed so playback never trips over disk trouble. */
    private fun save() {
        runCatching {
            directory.mkdirs()
            file.writeText(json.encodeToString(snapshot()))
        }
    }
}
