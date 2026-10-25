package echo.music.desktop.playback

import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import com.music.innertube.models.YTItem
import com.music.innertube.pages.AlbumPage
import com.music.innertube.pages.ArtistPage
import com.music.innertube.pages.PlaylistPage
import echo.music.desktop.data.PlayHistory
import echo.music.desktop.data.TasteModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A song as the player sees it — the desktop equivalent of upstream's `MediaMetadata`. */
@kotlinx.serialization.Serializable
data class PlayableSong(
    val videoId: String,
    val title: String,
    val artists: List<String>,
    val thumbnail: String?,
    val durationMs: Long,
    val explicit: Boolean = false,
) {
    val artistLine: String get() = artists.joinToString(", ")
}

enum class RepeatMode { OFF, ALL, ONE }

/**
 * Playback state + queue, the desktop stand-in for upstream's `MusicService` + `PlayerConnection`
 * (4885 + 543 lines of Android Service/MediaController). Everything the UI observes lives in one
 * [StateFlow]; all methods are safe from the UI thread.
 */
object Playback {

    data class State(
        val queue: List<PlayableSong> = emptyList(),
        val index: Int = -1,
        val isPlaying: Boolean = false,
        val isBuffering: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val error: String? = null,
        val shuffle: Boolean = false,
        val repeat: RepeatMode = RepeatMode.OFF,
        val volume: Float = 1f,
        val playerExpanded: Boolean = false,
    ) {
        val current: PlayableSong? get() = queue.getOrNull(index)
        val hasPrevious: Boolean get() = index > 0
        val hasNext: Boolean
            get() = index < queue.lastIndex || repeat != RepeatMode.OFF
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loadJob: Job? = null
    private var order: List<Int>? = null // shuffled play order (positions into the queue)

    /** videoId+quality → resolved stream, valid for ~5.5 h (URLs expire at 6 h). */
    private data class Cached(val resolved: StreamResolver.Resolved, val expiresAt: Long)

    private val streamCache = HashMap<String, Cached>()

    private val engine =
        FfmpegPlayer(
            onStarted = {
                // First PCM chunk reached the line: the wait is over, flip out of buffering.
                _state.value = _state.value.copy(isBuffering = false, isPlaying = true)
            },
            onProgress = { position, duration ->
                _state.value =
                    _state.value.copy(
                        positionMs = position,
                        durationMs = duration ?: _state.value.durationMs,
                    )
            },
            onEnded = { onTrackEnded() },
            onInterrupted = { positionMs ->
                val s = _state.value
                if (s.current != null) {
                    _state.value = s.copy(isBuffering = true)
                    loadAt(s.index, startMs = positionMs)
                }
            },
            onFailed = { message ->
                _state.value = _state.value.copy(isBuffering = false, isPlaying = false, error = message)
            },
        )

    /**
     * Monotonic id of the latest [loadAt] request. A seek cancels the previous load, but coroutine
     * cancellation only applies at suspension points — the superseded job could still reach
     * `engine.play()` *after* the new one and restart playback from 0:00 while the UI showed the
     * seeked position. Both the id check and the spawn happen under [playGate], so the newest
     * request always wins, deterministically.
     */
    private val loadGeneration = java.util.concurrent.atomic.AtomicLong(0)
    private val playGate = Any()

    // ---------------------------------------------------------------- queue control

    fun playQueue(songs: List<PlayableSong>, startIndex: Int = 0, expanded: Boolean = true) {
        if (songs.isEmpty()) return
        val start = startIndex.coerceIn(0, songs.lastIndex)
        order = null
        _state.value =
            _state.value.copy(queue = songs, index = start, playerExpanded = expanded, error = null)
        loadCurrent()
    }

    /** Pre-populates the display state (e.g. for screenshot automation or UI demos) without starting audio. */
    fun setQueueForDisplay(
        songs: List<PlayableSong>,
        currentIndex: Int = 0,
        isPlaying: Boolean = true,
        positionMs: Long = 74000L,
    ) {
        if (songs.isEmpty()) return
        val start = currentIndex.coerceIn(0, songs.lastIndex)
        _state.value =
            _state.value.copy(
                queue = songs,
                index = start,
                isPlaying = isPlaying,
                isBuffering = false,
                positionMs = positionMs,
                durationMs = songs[start].durationMs,
                error = null,
            )
    }

    fun togglePlay() {
        val s = _state.value
        if (s.current == null) return
        if (s.isBuffering) return
        val playing = !s.isPlaying
        engine.setPaused(!playing)
        _state.value = _state.value.copy(isPlaying = playing)
    }

    fun next() = skipBy(+1, fromAuto = false)

    fun previous() {
        val s = _state.value
        // Like every music player: if we're >3 s in, restart the track instead of going back.
        if (s.positionMs > 3_000) {
            seekTo(0)
            return
        }
        skipBy(-1, fromAuto = false)
    }

    fun seekTo(positionMs: Long) {
        val s = _state.value
        val song = s.current ?: return
        val clamped = positionMs.coerceIn(0L, (s.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE))
        VideoEngine.notifySeek(clamped)
        _state.value = _state.value.copy(positionMs = clamped, isBuffering = true, isPlaying = false)
        loadAt(s.index, startMs = clamped)
    }

    fun setVolume(value: Float) {
        val v = value.coerceIn(0f, 1f)
        engine.setVolume(v)
        _state.value = _state.value.copy(volume = v)
    }

    fun toggleShuffle() {
        val s = _state.value
        if (!s.shuffle) {
            // Keep the current track first in the shuffled order, shuffle the rest.
            val rest = (s.queue.indices).filter { it != s.index }.shuffled()
            order = listOf(s.index) + rest
        } else {
            order = null
        }
        _state.value = _state.value.copy(shuffle = !s.shuffle)
    }

    fun cycleRepeat() {
        val next =
            when (_state.value.repeat) {
                RepeatMode.OFF -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.OFF
            }
        _state.value = _state.value.copy(repeat = next)
    }

    fun setExpanded(expanded: Boolean) {
        _state.value = _state.value.copy(playerExpanded = expanded)
    }

    /** Replace the queue but keep playing the song with the same id if present (for "up next"). */
    fun playNext(songs: List<PlayableSong>) {
        if (songs.isEmpty()) return
        val s = _state.value
        if (s.queue.isEmpty()) {
            playQueue(songs)
            return
        }
        val merged = s.queue.toMutableList().apply { addAll(s.index + 1, songs) }
        _state.value = _state.value.copy(queue = merged)
    }

    fun stop() {
        loadJob?.cancel()
        engine.stop()
        _state.value = State(volume = _state.value.volume)
    }

    // ---------------------------------------------------------------- internals

    private fun skipBy(delta: Int, fromAuto: Boolean) {
        val s = _state.value
        if (s.queue.isEmpty()) return

        val target: Int =
            if (s.repeat == RepeatMode.ONE && fromAuto) s.index
            else {
                val orderList = order
                if (orderList != null) {
                    val pos = orderList.indexOf(s.index)
                    val nextPos = pos + delta
                    if (nextPos in orderList.indices) orderList[nextPos]
                    else if (s.repeat == RepeatMode.ALL) orderList.first()
                    else return
                } else {
                    val next = s.index + delta
                    when {
                        next in s.queue.indices -> next
                        s.repeat == RepeatMode.ALL && delta > 0 -> 0
                        else -> {
                            if (fromAuto) {
                                _state.value =
                                    s.copy(isPlaying = false, positionMs = 0L)
                            }
                            return
                        }
                    }
                }
            }
        // ---- taste signals (the local recommendation model):
        // a natural end = a strong positive; manually advancing = a skip signal whose weight
        // depends on how far into the track the user was (TasteModel applies its own threshold).
        if (fromAuto) {
            s.current?.let { TasteModel.onTrackCompleted(it) }
        } else if (delta > 0) {
            s.current?.let { TasteModel.onTrackSkipped(it, s.positionMs) }
        }

        _state.value = _state.value.copy(index = target, error = null)
        loadCurrent()
    }

    private fun onTrackEnded() = skipBy(+1, fromAuto = true)

    private fun loadCurrent() = loadAt(_state.value.index, startMs = 0L)

    private fun loadAt(index: Int, startMs: Long) {
        val song = _state.value.queue.getOrNull(index) ?: return
        val gen = loadGeneration.incrementAndGet()
        loadJob?.cancel()
        _state.value =
            _state.value.copy(isBuffering = true, isPlaying = false, positionMs = startMs, error = null)

        loadJob =
            scope.launch(Dispatchers.IO) {
                runCatching { StreamResolver.init() }
                val resolved = cachedStream(song.videoId) ?: StreamResolver.resolve(song.videoId)
                if (resolved == null) {
                    _state.value =
                        _state.value.copy(
                            isBuffering = false,
                            isPlaying = false,
                            error = "Couldn't get a stream for “${song.title}”",
                        )
                    return@launch
                }
                // A newer load superseded this one while we were resolving.
                if (_state.value.index != index || _state.value.current?.videoId != song.videoId) {
                    return@launch
                }
                val duration =
                    song.durationMs.takeIf { it > 0 }
                        ?: resolved.contentLength
                            ?.let { it * 8_000 / resolved.bitrateBps.coerceAtLeast(1) }
                        ?: 0L
                _state.value = _state.value.copy(durationMs = duration)

                // Spawn under the gate so a stale job can never outrun a newer seek. isBuffering
                // stays true until the engine reports its first audio chunk (onStarted) — that
                // wait is exactly what the UI's spinner should cover.
                synchronized(playGate) {
                    if (gen != loadGeneration.get()) return@launch
                    engine.play(
                        FfmpegPlayer.Source(resolved.url, resolved.headers, duration.takeIf { it > 0 }),
                        startMs = startMs,
                        volume = _state.value.volume,
                    )
                    if (startMs == 0L) {
                        TasteModel.onTrackStarted(song)
                    }
                    PlayHistory.record(song)
                }
            }
    }

    private suspend fun cachedStream(videoId: String): StreamResolver.Resolved? {
        val now = System.currentTimeMillis()
        synchronized(streamCache) {
            streamCache[videoId]?.let {
                if (it.expiresAt > now) return it.resolved else streamCache.remove(videoId)
            }
        }
        val resolved = StreamResolver.resolve(videoId) ?: return null
        synchronized(streamCache) {
            streamCache[videoId] =
                Cached(resolved, now + (resolved.expiresInSeconds - 60).coerceAtLeast(60) * 1000L)
        }
        return resolved
    }

    // ---------------------------------------------------------------- queue loading

    /** Loads an album/playlist/artist and plays it — the click-through path for non-song cards. */
    fun playFrom(
        fetch: suspend () -> List<PlayableSong>,
        startIndex: Int = 0,
        expanded: Boolean = true,
    ) {
        _state.value = _state.value.copy(isBuffering = true, error = null)
        scope.launch(Dispatchers.IO) {
            runCatching { fetch() }
                .onSuccess { songs ->
                    if (songs.isEmpty()) {
                        _state.value =
                            _state.value.copy(isBuffering = false, error = "No playable songs found")
                    } else {
                        playQueue(songs, startIndex, expanded)
                    }
                }
                .onFailure { e ->
                    _state.value =
                        _state.value.copy(isBuffering = false, error = e.message ?: "Failed to load")
                }
        }
    }
}

// ---------------------------------------------------------------- YTItem ↔ PlayableSong

fun SongItem.toPlayable(): PlayableSong =
    PlayableSong(
        videoId = id,
        title = title,
        artists = artists.map { it.name },
        thumbnail = thumbnail,
        durationMs = (duration ?: -1).let { if (it > 0) it * 1000L else 0L },
        explicit = explicit,
    )

/** Songs inside any upstream page model, in list order. */
fun List<YTItem>.songs(): List<PlayableSong> = mapNotNull { (it as? SongItem)?.toPlayable() }

fun AlbumPage.playableSongs(): List<PlayableSong> = songs.map { it.toPlayable() }
fun PlaylistPage.playableSongs(): List<PlayableSong> = songs.map { it.toPlayable() }

fun ArtistPage.firstSongList(): List<PlayableSong> =
    sections.firstNotNullOfOrNull { section -> section.items.songs().takeIf { it.isNotEmpty() } }
        ?: emptyList()

/** Click a song card → play that song with its shelf/section as the queue. */
fun playSongFrom(items: List<YTItem>, videoId: String, expanded: Boolean = true) {
    val songs = items.songs()
    if (songs.isEmpty()) return
    val start = songs.indexOfFirst { it.videoId == videoId }.coerceAtLeast(0)
    Playback.playQueue(songs, start, expanded)
}

/** Click an album/playlist/artist card → fetch its tracks, then play. */
fun playContainer(
    items: List<YTItem>,
    containerId: String,
    kind: ContainerKind,
    expanded: Boolean = true,
) {
    Playback.playFrom(
        fetch = {
            when (kind) {
                ContainerKind.ALBUM ->
                    YouTube.album(containerId, withSongs = true).getOrThrow().playableSongs()
                ContainerKind.PLAYLIST ->
                    YouTube.playlist(containerId).getOrThrow().playableSongs()
                ContainerKind.ARTIST ->
                    YouTube.artist(containerId).getOrThrow().firstSongList()
            }
        },
        expanded = expanded,
    )
}

enum class ContainerKind { ALBUM, PLAYLIST, ARTIST }
