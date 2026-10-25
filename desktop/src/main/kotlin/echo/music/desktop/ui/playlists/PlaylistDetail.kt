package echo.music.desktop.ui.playlists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import echo.music.desktop.data.PlaylistStore
import echo.music.desktop.data.UserPlaylist
import echo.music.desktop.playback.Playback
import echo.music.desktop.ui.components.MediaListItem
import echo.music.desktop.ui.components.NavigationTitle
import echo.music.desktop.ui.icons.EchoIcon
import echo.music.desktop.ui.listSubtitle
import echo.music.desktop.ui.playSongList

/**
 * Detail view for one user playlist — the port of upstream's playlist page for locally saved
 * lists. A back chevron returns to the Library grid, `NavigationTitle` doubles as the "Play all"
 * pill (`playQueue(..., 0, expanded = false)`, same as every shelf header), and Rename / Delete
 * live as text actions on the top bar. Each song row's ⋮ opens a confirmation dialog with
 * "Remove from playlist"; clicking a row plays the whole playlist from that track with the full
 * player open.
 *
 * The composable is stateless about storage: it renders the [playlist] snapshot it's given and
 * reports every mutation through [onChanged], so the caller re-reads [PlaylistStore] and either
 * refreshes this view or (after a delete) drops back to the grid.
 */
@Composable
fun PlaylistDetail(
    playlist: UserPlaylist,
    currentVideoId: String?,
    onBack: () -> Unit,
    onChanged: () -> Unit,
    contentBottomPadding: Dp,
) {
    var showRename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var menuVideoId by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar: back (chevron rotated to point left), then rename/delete.
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                EchoIcon(
                    EchoIcon.CHEVRON_DOWN,
                    contentDescription = "Back to library",
                    modifier = Modifier.rotate(90f),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showRename = true }) {
                Text("Rename", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { confirmDelete = true }) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        }

        NavigationTitle(
            title = playlist.name,
            onClick =
                if (playlist.songs.isNotEmpty()) ({
                    Playback.playQueue(playlist.songs, 0, expanded = false)
                }) else null,
        )

        if (playlist.songs.isEmpty()) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(bottom = contentBottomPadding)
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "This playlist is empty",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Find a song in Search and use ⋮ → Add to playlist to fill it up.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = contentBottomPadding),
            ) {
                itemsIndexed(
                    playlist.songs,
                    key = { index, song -> "$index:${song.videoId}" },
                ) { _, song ->
                    val active = currentVideoId == song.videoId
                    MediaListItem(
                        title = song.title,
                        subtitle = listSubtitle(song),
                        thumbnail = song.thumbnail,
                        isActive = active,
                        showPlayingIndicator = active,
                        onMore = { menuVideoId = song.videoId },
                        onClick = { playSongList(playlist.songs, song.videoId) },
                    )
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }

    if (showRename) {
        PlaylistNameDialog(
            title = "Rename playlist",
            initialName = playlist.name,
            confirmLabel = "Save",
            onConfirm = { name ->
                PlaylistStore.rename(playlist.id, name)
                onChanged()
                showRename = false
            },
            onDismiss = { showRename = false },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete playlist?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "“${playlist.name}” (${playlist.songCountLabel}) will be removed from this " +
                        "device.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        PlaylistStore.delete(playlist.id)
                        onChanged() // caller's playlist lookup now misses → back to the grid
                        confirmDelete = false
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }

    menuVideoId?.let { videoId ->
        val songTitle =
            playlist.songs.firstOrNull { it.videoId == videoId }?.title ?: "Song"
        AlertDialog(
            onDismissRequest = { menuVideoId = null },
            title = {
                Text(songTitle, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            text = {
                Text(
                    "Remove this song from “${playlist.name}”?",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        PlaylistStore.removeSong(playlist.id, videoId)
                        onChanged()
                        menuVideoId = null
                    },
                ) {
                    Text("Remove from playlist", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { menuVideoId = null }) { Text("Cancel") }
            },
        )
    }
}
