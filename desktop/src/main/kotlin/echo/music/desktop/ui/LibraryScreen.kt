package echo.music.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import echo.music.desktop.data.PlayHistory
import echo.music.desktop.data.PlaylistStore
import echo.music.desktop.data.UserPlaylist
import echo.music.desktop.playback.PlayableSong
import echo.music.desktop.playback.Playback
import echo.music.desktop.ui.components.ChipsRow
import echo.music.desktop.ui.components.MediaGridItem
import echo.music.desktop.ui.components.MediaListItem
import echo.music.desktop.ui.components.NavigationTitle
import echo.music.desktop.ui.icons.EchoIcon
import echo.music.desktop.ui.playlists.AddToPlaylistDialog
import echo.music.desktop.ui.playlists.PlaylistDetail
import echo.music.desktop.ui.playlists.PlaylistNameDialog

/**
 * Library tab. Upstream's Library is backed by the signed-in Google account; this port has no
 * OAuth login, so the tab carries the two things that really are local: the user's own playlists
 * (create / rename / delete, add & remove songs — persisted by [PlaylistStore]) and the recorded
 * play history (see [PlayHistory]).
 *
 * A [ChipsRow] picks between the two sections — defaulting to "Playlists" when any exist, so the
 * tab lands on what the user came for — and a playlist opens into [PlaylistDetail]. All playlist
 * state is read fresh from disk the moment the screen enters composition and re-read after every
 * mutation, so returning from Search (where songs get added) always shows current data.
 */
@Composable
fun LibraryScreen(contentBottomPadding: Dp) {
    // Read fresh every time the tab enters composition.
    var playlists by remember { mutableStateOf(PlaylistStore.all()) }
    var addToPlaylistSong by remember { mutableStateOf<PlayableSong?>(null) }
    var openPlaylistId by remember { mutableStateOf<String?>(null) }
    // Default to Playlists when the user has some, otherwise land on Recently played.
    var tab by remember { mutableStateOf(if (playlists.isEmpty()) 1 else 0) }
    val playback by Playback.state.collectAsState()

    // A playlist that no longer exists (deleted) simply drops us back to the grid.
    val openPlaylist = playlists.firstOrNull { it.id == openPlaylistId }
    if (openPlaylist != null) {
        PlaylistDetail(
            playlist = openPlaylist,
            currentVideoId = playback.current?.videoId,
            onBack = { openPlaylistId = null },
            onChanged = { playlists = PlaylistStore.all() },
            contentBottomPadding = contentBottomPadding,
        )
        return
    }

    addToPlaylistSong?.let { song ->
        AddToPlaylistDialog(
            song = song,
            onDismiss = {
                addToPlaylistSong = null
                // Counts/membership may have changed while the dialog was up.
                playlists = PlaylistStore.all()
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ChipsRow(
            labels = listOf("Playlists", "Recently played"),
            selectedIndex = tab,
            onSelect = { tab = it },
            modifier = Modifier.padding(top = 4.dp),
        )
        // The tab body gets exactly the space below the chips, so each section can safely
        // use fillMaxSize() (grid, list, empty states) without overflowing the column.
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 ->
                    PlaylistsTab(
                        playlists = playlists,
                        queue = playback.queue,
                        contentBottomPadding = contentBottomPadding,
                        onOpen = { openPlaylistId = it },
                        onChanged = { playlists = PlaylistStore.all() },
                    )
                else ->
                    RecentlyPlayedTab(
                        contentBottomPadding = contentBottomPadding,
                        currentVideoId = playback.current?.videoId,
                        onAddToPlaylist = { addToPlaylistSong = it },
                    )
            }
        }
    }
}

// ------------------------------------------------------------------------------ playlists

/**
 * Playlists section: a two-up grid of cards (first song's artwork + "N songs"), a "New
 * playlist" action and — only while something is actually queued — "Save current queue".
 */
@Composable
private fun PlaylistsTab(
    playlists: List<UserPlaylist>,
    queue: List<PlayableSong>,
    contentBottomPadding: Dp,
    onOpen: (String) -> Unit,
    onChanged: () -> Unit,
) {
    var showCreate by remember { mutableStateOf(false) }
    var showSaveQueue by remember { mutableStateOf(false) }

    if (playlists.isEmpty()) {
        EmptyState(
            title = "No playlists yet",
            message = "Save your current queue, or add songs from Search with ⋮ → Add to playlist.",
            contentBottomPadding = contentBottomPadding,
            actions = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    OutlinedButton(onClick = { showCreate = true }) {
                        EchoIcon(
                            EchoIcon.PLUS,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("New playlist")
                    }
                    if (queue.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { showSaveQueue = true }) {
                            Text("Save current queue")
                        }
                    }
                }
            },
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = contentBottomPadding),
        ) {
            item(key = "header") {
                Column {
                    NavigationTitle(title = "Your playlists")
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(onClick = { showCreate = true }) {
                            EchoIcon(
                                EchoIcon.PLUS,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("New playlist")
                        }
                        // Only offered when there is a queue worth saving.
                        if (queue.isNotEmpty()) {
                            OutlinedButton(onClick = { showSaveQueue = true }) {
                                Text("Save current queue")
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            items(playlists.chunked(2), key = { chunk -> chunk.joinToString("+") { it.id } }) { chunk ->
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    chunk.forEach { playlist ->
                        MediaGridItem(
                            title = playlist.name,
                            subtitle = playlist.songCountLabel,
                            thumbnail = playlist.songs.firstOrNull()?.thumbnail,
                            onClick = { onOpen(playlist.id) },
                        )
                    }
                }
            }
        }
    }

    if (showCreate) {
        PlaylistNameDialog(
            title = "New playlist",
            initialName = "",
            confirmLabel = "Create",
            onConfirm = { name ->
                PlaylistStore.create(name)
                onChanged()
                showCreate = false
            },
            onDismiss = { showCreate = false },
        )
    }

    if (showSaveQueue) {
        PlaylistNameDialog(
            title = "Save current queue",
            initialName = "Queue",
            confirmLabel = "Save",
            onConfirm = { name ->
                val created = PlaylistStore.create(name)
                queue.forEach { PlaylistStore.addSong(created.id, it) }
                onChanged()
                showSaveQueue = false
            },
            onDismiss = { showSaveQueue = false },
        )
    }
}

// --------------------------------------------------------------------------- play history

/**
 * Recently played section — unchanged behaviour from the original Library: play history
 * (newest first) straight from [PlayHistory], refreshed when a new track starts, with a
 * "Play all" pill, a Clear-history action, and now a ⋮ per row to save the song to a playlist.
 */
@Composable
private fun RecentlyPlayedTab(
    contentBottomPadding: Dp,
    currentVideoId: String?,
    onAddToPlaylist: (PlayableSong) -> Unit,
) {
    var history by remember { mutableStateOf(PlayHistory.load()) }
    val playback by Playback.state.collectAsState()

    // Refresh when a new track starts so the Library reflects what you just played.
    LaunchedEffect(playback.index, playback.isBuffering) {
        if (!playback.isBuffering) history = PlayHistory.load()
    }

    if (history.isEmpty()) {
        EmptyState(
            title = "Nothing here yet",
            message = "Songs you play are collected here so you can jump back into them.",
            contentBottomPadding = contentBottomPadding,
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = contentBottomPadding),
    ) {
        item(key = "header") {
            Column {
                NavigationTitle(
                    title = "Recently played",
                    onClick = { Playback.playQueue(history, 0, expanded = false) },
                )
                TextButton(
                    onClick = {
                        PlayHistory.clear()
                        history = emptyList()
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Text("Clear history", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        itemsIndexed(history, key = { index, song -> "$index:${song.videoId}" }) { _, song ->
            val active = currentVideoId == song.videoId
            MediaListItem(
                title = song.title,
                subtitle = listSubtitle(song),
                thumbnail = song.thumbnail,
                isActive = active,
                showPlayingIndicator = active,
                onMore = { onAddToPlaylist(song) },
                onClick = { playSongList(history, song.videoId) },
            )
            Spacer(Modifier.height(2.dp))
        }
    }
}

// ------------------------------------------------------------------------------ empty state

/** Centered honest-and-friendly empty copy, shared by both Library sections. */
@Composable
private fun EmptyState(
    title: String,
    message: String,
    contentBottomPadding: Dp = 0.dp,
    actions: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(bottom = contentBottomPadding).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actions != null) {
            Spacer(Modifier.height(16.dp))
            actions()
        }
    }
}
