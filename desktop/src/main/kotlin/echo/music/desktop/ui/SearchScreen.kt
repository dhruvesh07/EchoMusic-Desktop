package echo.music.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.innertube.models.SongItem
import com.music.innertube.models.YTItem
import echo.music.desktop.data.DesktopSearchFilter
import echo.music.desktop.data.TasteModel
import echo.music.desktop.data.YouTubeRepository
import echo.music.desktop.playback.Playback
import echo.music.desktop.playback.playSongFrom
import echo.music.desktop.playback.toPlayable
import echo.music.desktop.ui.components.ChipsRow
import echo.music.desktop.ui.components.MediaGridItem
import echo.music.desktop.ui.components.MediaListItem
import echo.music.desktop.ui.components.NavigationTitle
import echo.music.desktop.ui.icons.EchoIcon
import echo.music.desktop.ui.playlists.AddToPlaylistDialog
import kotlinx.coroutines.delay

/**
 * Desktop port of upstream's search (`search_input` + `search/{query}` routes): a 28.dp-rounded
 * field on `surfaceContainerHigh`, the filter chip rail, then results as 64.dp list rows under an
 * uppercase header — songs queue up on click, containers load and play underneath.
 */
@Composable
fun SearchScreen(
    contentBottomPadding: Dp,
    initialQuery: String = "",
    initialFilter: DesktopSearchFilter = DesktopSearchFilter.SONGS,
) {
    var query by remember { mutableStateOf(initialQuery) }
    var filter by remember { mutableStateOf(initialFilter) }
    // ⋮ on a song result → the add-to-playlist picker for that track.
    var addToPlaylistSong by remember { mutableStateOf<SongItem?>(null) }

    val state by
        produceState<SearchUiState>(SearchUiState.Idle, query, filter) {
            if (query.isBlank()) {
                value = SearchUiState.Idle
                return@produceState
            }
            delay(300) // upstream debounces the same way before firing the request
            value = SearchUiState.Loading
            // What the user looks for is a first-class taste signal (feeds the "Made for you"
            // mix) — record the debounced, trimmed query only.
            TasteModel.recordSearch(query.trim())
            YouTubeRepository.search(query.trim(), filter.filter)
                .onSuccess { result -> value = SearchUiState.Loaded(result.items) }
                .onFailure { e -> value = SearchUiState.Failed(e.message ?: "Search failed") }
        }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Songs, albums, artists, playlists") },
            leadingIcon = { EchoIcon(EchoIcon.SEARCH) },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor =
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                ),
        )

        ChipsRow(
            labels = DesktopSearchFilter.entries.map { it.label },
            selectedIndex = DesktopSearchFilter.entries.indexOf(filter),
            onSelect = { filter = DesktopSearchFilter.entries[it] },
            modifier = Modifier.padding(top = 4.dp),
        )

        when (val current = state) {
            SearchUiState.Idle ->
                Text(
                    "Search YouTube Music",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                )
            SearchUiState.Loading ->
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { CircularProgressIndicator() }
            is SearchUiState.Failed ->
                Text(
                    current.message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                )
            is SearchUiState.Loaded ->
                SearchResults(
                    items = current.items,
                    query = query,
                    contentBottomPadding = contentBottomPadding,
                    onAddToPlaylist = { addToPlaylistSong = it },
                )
        }
    }

    addToPlaylistSong?.let { song ->
        AddToPlaylistDialog(
            song = song.toPlayable(),
            onDismiss = { addToPlaylistSong = null },
        )
    }
}

@Composable
private fun SearchResults(
    items: List<YTItem>,
    query: String,
    contentBottomPadding: Dp,
    onAddToPlaylist: (SongItem) -> Unit,
) {
    if (items.isEmpty()) {
        Text(
            "No results for “$query”",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
        )
        return
    }

    val playback by Playback.state.collectAsState()
    val songs = items.filterIsInstance<SongItem>()
    val containers = items.filter { it !is SongItem }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = contentBottomPadding),
    ) {
        if (songs.isNotEmpty()) {
            item(key = "header-songs") {
                NavigationTitle(
                    title = "Songs",
                    onClick = {
                        Playback.playQueue(songs.map { it.toPlayable() }, 0, expanded = false)
                    },
                )
            }
            itemsIndexed(songs, key = { index, song -> "$index:${song.id}" }) { _, song ->
                val active = playback.current?.videoId == song.id
                MediaListItem(
                    title = song.title,
                    subtitle = listSubtitle(song),
                    thumbnail = song.thumbnail,
                    isActive = active,
                    showPlayingIndicator = active,
                    onMore = { onAddToPlaylist(song) },
                    onClick = { playSongFrom(items, song.id) },
                )
                Spacer(Modifier.height(2.dp))
            }
        }
        if (containers.isNotEmpty()) {
            item(key = "header-more") { NavigationTitle(title = "Albums, artists & playlists") }
            item(key = "shelf") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(containers, key = { it.id }) { item ->
                        MediaGridItem(
                            title = item.title,
                            subtitle = subtitleFor(item),
                            thumbnail = item.thumbnail,
                            onClick = { onMediaClick(item, items) },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

private sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object Loading : SearchUiState
    data class Failed(val message: String) : SearchUiState
    data class Loaded(val items: List<YTItem>) : SearchUiState
}
