package echo.music.desktop.data

import echo.music.desktop.playback.PlayableSong
import java.io.File
import kotlinx.serialization.json.Json

/**
 * Local, offline play history backing the Library tab (upstream's Library is Google-account
 * backed, which needs an OAuth login this port doesn't have yet). Written as JSON under
 * `~/.echo-music/history.json`, most-recent first, capped at 100 entries.
 */
object PlayHistory {

    private val directory = File(System.getProperty("user.home"), ".echo-music")
    private val file = File(directory, "history.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private const val MAX_ENTRIES = 100

    @Synchronized
    fun record(song: PlayableSong) {
        runCatching {
            val current = load().toMutableList()
            current.removeAll { it.videoId == song.videoId }
            current.add(0, song)
            val trimmed = current.take(MAX_ENTRIES)
            directory.mkdirs()
            file.writeText(json.encodeToString(trimmed))
        }
    }

    @Synchronized
    fun load(): List<PlayableSong> =
        runCatching {
            if (!file.exists()) emptyList()
            else json.decodeFromString<List<PlayableSong>>(file.readText())
        }
            .getOrElse {
                // A corrupt file should not take the Library tab down with it.
                runCatching { file.delete() }
                emptyList()
            }

    @Synchronized
    fun clear() {
        runCatching { file.delete() }
    }

    /**
     * Full snapshot of the listening history as a list rather than JSON, so an
     * export/restore round-trip stays a single document.
     */
    @Synchronized
    fun exportList(): List<PlayableSong> = load()

    /**
     * Replace local history with a downloaded cloud copy (used when signing in on a new PC, or
     * hitting "Restore"). Tolerates corrupt/absent input the same way [load] does.
     */
    @Synchronized
    fun restoreList(songs: List<PlayableSong>) {
        runCatching {
            val trimmed = songs.distinctBy { it.videoId }.take(MAX_ENTRIES)
            directory.mkdirs()
            file.writeText(json.encodeToString(trimmed))
        }
    }
}
