package echo.music.desktop.data

import echo.music.desktop.playback.PlayableSong
import java.io.File
import java.util.UUID
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * User-created playlists — the local store behind every "add to playlist" action in the desktop
 * port. Upstream keeps these on the signed-in Google account; this build has no OAuth login for
 * the music side, so they live next to [PlayHistory] as JSON under
 * `~/.echo-music/playlists.json`.
 *
 * Design notes:
 *
 *  - **Write-through.** Every mutation rewrites the whole file immediately, so screens can simply
 *    re-read [all] after a call and observe the result — no cache to keep in sync.
 *  - **[Synchronized] accessors.** The UI, the dialogs and (via [export]/[restore]) the cloud
 *    sync layer all touch one file, so every public entry point is a monitor lock. Kotlin's
 *    `synchronized` is reentrant, so [contains] may call [byId] freely.
 *  - **Corrupt files are tolerated.** A malformed or unreadable file is deleted and treated as an
 *    empty list — losing playlists should never crash the app, and the next save repairs it.
 *
 * IDs are short random UUID fragments (8 hex chars) so they stay human-readable in the JSON and
 * are unique enough for a personal library.
 */
@kotlinx.serialization.Serializable
data class UserPlaylist(
    val id: String,
    val name: String,
    val createdMs: Long,
    val songs: List<PlayableSong>,
) {
    /** "1 song" / "12 songs" — card and dialog subtitle. */
    val songCountLabel: String get() = if (songs.size == 1) "1 song" else "${songs.size} songs"
}

object PlaylistStore {

    private val directory = File(System.getProperty("user.home"), ".echo-music")
    private val file = File(directory, "playlists.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** All playlists, newest creation order preserved as stored. */
    @Synchronized
    fun all(): List<UserPlaylist> = load()

    /** One playlist by id, or `null` if it was deleted (or lives on another device). */
    @Synchronized
    fun byId(id: String): UserPlaylist? = load().firstOrNull { it.id == id }

    /** Creates an empty playlist and returns it. A blank name falls back to "Playlist". */
    @Synchronized
    fun create(name: String): UserPlaylist {
        val current = load().toMutableList()
        val playlist =
            UserPlaylist(
                id = newId(current),
                name = name.trim().ifBlank { "Playlist" },
                createdMs = System.currentTimeMillis(),
                songs = emptyList(),
            )
        current.add(playlist)
        save(current)
        return playlist
    }

    /** Renames a playlist; a blank name is ignored rather than wiping the title. */
    @Synchronized
    fun rename(id: String, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        save(load().map { if (it.id == id) it.copy(name = clean) else it })
    }

    /** Deletes a playlist. Missing ids are a no-op. */
    @Synchronized
    fun delete(id: String) {
        save(load().filterNot { it.id == id })
    }

    /**
     * Appends [song] to the playlist. Returns `false` when the playlist doesn't exist or the
     * song is already in it (playlists never hold the same track twice).
     */
    @Synchronized
    fun addSong(playlistId: String, song: PlayableSong): Boolean {
        val current = load()
        if (current.none { it.id == playlistId }) return false
        if (current.first { it.id == playlistId }.songs.any { it.videoId == song.videoId }) {
            return false
        }
        save(current.map { if (it.id == playlistId) it.copy(songs = it.songs + song) else it })
        return true
    }

    /** Removes the song with [videoId] from the playlist; `false` if neither existed. */
    @Synchronized
    fun removeSong(playlistId: String, videoId: String): Boolean {
        val current = load()
        val target = current.firstOrNull { it.id == playlistId } ?: return false
        if (target.songs.none { it.videoId == videoId }) return false
        save(
            current.map { playlist ->
                if (playlist.id == playlistId) {
                    playlist.copy(songs = playlist.songs.filterNot { it.videoId == videoId })
                } else playlist
            }
        )
        return true
    }

    /** Whether the playlist already contains the song. */
    @Synchronized
    fun contains(playlistId: String, videoId: String): Boolean =
        byId(playlistId)?.songs?.any { it.videoId == videoId } == true

    /** Snapshot for the cloud sync layer (replace-all semantics on the other side). */
    @Synchronized
    fun export(): List<UserPlaylist> = load()

    /** Replaces the whole local set — used by cloud restore. */
    @Synchronized
    fun restore(playlists: List<UserPlaylist>) {
        save(playlists.toList())
    }

    // ------------------------------------------------------------------ internals

    private fun load(): List<UserPlaylist> =
        runCatching {
            if (!file.exists()) emptyList()
            else json.decodeFromString<List<UserPlaylist>>(file.readText())
        }
            .getOrElse {
                // A corrupt file should not take the playlists down with it.
                runCatching { file.delete() }
                emptyList()
            }

    private fun save(playlists: List<UserPlaylist>) {
        runCatching {
            directory.mkdirs()
            file.writeText(json.encodeToString(playlists))
        }
    }

    /** 8-hex-char UUID fragment that doesn't collide with an existing id. */
    private fun newId(existing: List<UserPlaylist>): String {
        val taken = existing.map { it.id }.toSet()
        while (true) {
            val candidate = UUID.randomUUID().toString().replace("-", "").take(8)
            if (candidate !in taken) return candidate
        }
    }
}
