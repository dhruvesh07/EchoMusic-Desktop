package echo.music.desktop.ui.playlists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import echo.music.desktop.data.PlaylistStore
import echo.music.desktop.data.UserPlaylist
import echo.music.desktop.playback.PlayableSong
import echo.music.desktop.ui.icons.EchoIcon

/**
 * "Add to playlist" picker — the desktop port of upstream's bottom-sheet action.
 *
 * A centered dialog listing every [UserPlaylist] with its song count; a ✓ marks playlists that
 * already hold the song and tapping a row toggles membership (add/remove), so the dialog doubles
 * as a "remove from playlist" surface. The bottom of the dialog creates a brand-new playlist and
 * drops the song in one step. Every mutation re-reads [PlaylistStore] into local state, so the
 * list is always what's on disk — including changes made from other screens.
 */
@Composable
fun AddToPlaylistDialog(
    song: PlayableSong,
    onDismiss: () -> Unit = {},
) {
    // Fresh read on entry; reassigned after every mutation below.
    var playlists by remember { mutableStateOf(PlaylistStore.all()) }
    var newName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Add to playlist", fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                Text(
                    song.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))

                // heightIn keeps the inner scroll bounded even if the dialog hands down
                // infinite height (nested-scroll case); the name field below stays pinned.
                Column(
                    modifier = Modifier.heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (playlists.isEmpty()) {
                        Text(
                            "No playlists yet — create the first one below.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        playlists.forEach { playlist ->
                            PlaylistRow(playlist, song) { playlists = PlaylistStore.all() }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("New playlist name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        val created = PlaylistStore.create(newName)
                        PlaylistStore.addSong(created.id, song)
                        playlists = PlaylistStore.all()
                        newName = ""
                    },
                    enabled = newName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    EchoIcon(EchoIcon.PLUS, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Create & add")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

/**
 * One tappable playlist in [AddToPlaylistDialog]: name, song count, and a ✓ when [song] is
 * already saved there. Tapping toggles membership and reports back so the caller can refresh.
 */
@Composable
private fun PlaylistRow(
    playlist: UserPlaylist,
    song: PlayableSong,
    onChanged: () -> Unit,
) {
    val selected = playlist.songs.any { it.videoId == song.videoId }
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
            if (selected) PlaylistStore.removeSong(playlist.id, song.videoId)
            else PlaylistStore.addSong(playlist.id, song)
            onChanged()
        }.padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                playlist.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                playlist.songCountLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            EchoIcon(
                EchoIcon.CHECK,
                contentDescription = "Already in this playlist",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Shared name prompt for every playlist flow (create, save-current-queue, rename): one field,
 * a confirm button that stays disabled until there's something to save, and a Cancel escape.
 */
@Composable
fun PlaylistNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Playlist name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
