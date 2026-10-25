package echo.music.desktop.ui

import com.music.innertube.models.AlbumItem
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.SongItem
import com.music.innertube.models.YTItem
import echo.music.desktop.playback.ContainerKind
import echo.music.desktop.playback.Playback
import echo.music.desktop.playback.PlayableSong
import echo.music.desktop.playback.playContainer
import echo.music.desktop.playback.playSongFrom
import echo.music.desktop.playback.songs

/**
 * One click-routing rule for every card/row in the app, so nothing in the UI is inert:
 *
 *  - song → play it, with [siblings] (the shelf/section it lives in) as the queue
 *  - album / playlist / artist → fetch its tracks, then play
 */
fun onMediaClick(item: YTItem, siblings: List<YTItem>) {
    when (item) {
        is SongItem -> playSongFrom(siblings, item.id)
        is AlbumItem -> playContainer(siblings, item.id, ContainerKind.ALBUM)
        is PlaylistItem -> playContainer(siblings, item.id, ContainerKind.PLAYLIST)
        is ArtistItem -> playContainer(siblings, item.id, ContainerKind.ARTIST)
        else -> Unit
    }
}

/** The song list of a shelf/section, used by its "Play all" pill. */
fun sectionQueue(items: List<YTItem>): List<echo.music.desktop.playback.PlayableSong> =
    items.songs()

/** Subtitle line under a card/row — artists for songs, author/role for containers. */
fun subtitleFor(item: YTItem): String =
    when (item) {
        is SongItem -> item.artists.joinToString(", ") { it.name }
        is AlbumItem -> item.artists?.joinToString(", ") { it.name } ?: "Album"
        is ArtistItem -> "Artist"
        is PlaylistItem -> item.author?.name ?: "Playlist"
        else -> ""
    }

/** "Artist • 3:45" for list rows (upstream `joinByBullet`). */
fun listSubtitle(item: YTItem): String =
    when (item) {
        is SongItem -> {
            val artists = item.artists.joinToString(", ") { it.name }
            val duration = item.duration ?: -1
            when {
                duration > 0 && artists.isNotEmpty() -> "$artists • ${echo.music.desktop.ui.components.formatDuration(duration)}"
                duration > 0 -> echo.music.desktop.ui.components.formatDuration(duration)
                else -> artists
            }
        }
        else -> subtitleFor(item)
    }

/** Plays everything in a visible container (used by "Play all" pills). */
fun playAll(items: List<YTItem>) {
    val songs = items.songs()
    if (songs.isNotEmpty()) Playback.playQueue(songs, 0, expanded = false)
}

/** "Artist • 3:45" row subtitle for a locally stored [PlayableSong] (playlists, play history). */
fun listSubtitle(song: PlayableSong): String =
    echo.music.desktop.ui.components.subtitle(song.artistLine, (song.durationMs / 1000).toInt())

/**
 * One rule for clicking a song row whose queue is already a plain list of [PlayableSong]s
 * (playlist detail, recently played): play that list starting at the row you clicked, with the
 * full player open. Unknown ids fall back to the first track instead of doing nothing.
 */
fun playSongList(songs: List<PlayableSong>, videoId: String, expanded: Boolean = true) {
    if (songs.isEmpty()) return
    val index = songs.indexOfFirst { it.videoId == videoId }.coerceAtLeast(0)
    Playback.playQueue(songs, index, expanded = expanded)
}
