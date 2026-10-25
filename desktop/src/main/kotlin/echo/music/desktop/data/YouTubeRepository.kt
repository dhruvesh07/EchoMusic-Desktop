package echo.music.desktop.data

import com.music.innertube.YouTube
import com.music.innertube.YouTube.SearchFilter
import com.music.innertube.models.YTItem
import com.music.innertube.pages.AlbumPage
import com.music.innertube.pages.ArtistPage
import com.music.innertube.pages.HomePage
import com.music.innertube.pages.PlaylistPage
import com.music.innertube.pages.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Thin desktop wrapper over upstream's `com.music.innertube.YouTube` façade (113-file module,
 * reused verbatim). Owns the one piece of global configuration that `App.kt` did on Android:
 * locale + visitor data.
 */
object YouTubeRepository {

    init {
        // Same defaults App.kt applies on Android before any request.
        YouTube.locale = com.music.innertube.models.YouTubeLocale(gl = "US", hl = "en")
    }

    suspend fun home(
        continuation: String? = null,
        browseId: String = "FEmusic_home",
        params: String? = null,
    ): Result<HomePage> = withContext(Dispatchers.IO) {
        YouTube.home(continuation, browseId, params)
    }

    /** Upstream's `search` requires a filter; "all results" is expressed by the caller tabs. */
    suspend fun search(query: String, filter: SearchFilter): Result<SearchResult> =
        withContext(Dispatchers.IO) { YouTube.search(query, filter) }

    suspend fun explore() = withContext(Dispatchers.IO) { YouTube.explore() }

    suspend fun album(browseId: String, withSongs: Boolean = true): Result<AlbumPage> =
        withContext(Dispatchers.IO) { YouTube.album(browseId, withSongs) }

    suspend fun artist(browseId: String): Result<ArtistPage> =
        withContext(Dispatchers.IO) { YouTube.artist(browseId) }

    suspend fun playlist(playlistId: String): Result<PlaylistPage> =
        withContext(Dispatchers.IO) { YouTube.playlist(playlistId) }

    fun setLocale(hostLanguage: String, geolocation: String) {
        YouTube.locale =
            com.music.innertube.models.YouTubeLocale(gl = geolocation, hl = hostLanguage)
    }

    fun setVisitorData(visitorData: String?) {
        YouTube.visitorData = visitorData
    }

    fun setCookie(cookie: String?) {
        YouTube.cookie = cookie
    }
}

/** Convenience filters mirroring the upstream search tabs. */
enum class DesktopSearchFilter(val filter: SearchFilter, val label: String) {
    SONGS(SearchFilter.FILTER_SONG, "Songs"),
    VIDEOS(SearchFilter.FILTER_VIDEO, "Videos"),
    ALBUMS(SearchFilter.FILTER_ALBUM, "Albums"),
    ARTISTS(SearchFilter.FILTER_ARTIST, "Artists"),
    FEATURED_PLAYLISTS(SearchFilter.FILTER_FEATURED_PLAYLIST, "Featured playlists"),
    COMMUNITY_PLAYLISTS(SearchFilter.FILTER_COMMUNITY_PLAYLIST, "Community playlists"),
}

/** Upstream `YTItem` is a sealed class — this flattens it into a UI-friendly record. */
data class MediaItem(
    val id: String,
    val title: String,
    val thumbnail: String?,
    val subtitle: String,
)

fun YTItem.toMediaItem(): MediaItem? =
    when (this) {
        is com.music.innertube.models.SongItem ->
            MediaItem(id, title, thumbnail, artists.joinToString(", ") { it.name })
        is com.music.innertube.models.AlbumItem ->
            MediaItem(id, title, thumbnail, artists?.joinToString(", ") { it.name } ?: "")
        is com.music.innertube.models.ArtistItem -> MediaItem(id, title, thumbnail, "Artist")
        is com.music.innertube.models.PlaylistItem ->
            MediaItem(id, title, thumbnail, author?.name ?: "Playlist")
        else -> null
    }
