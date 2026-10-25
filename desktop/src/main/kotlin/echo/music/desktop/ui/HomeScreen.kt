package echo.music.desktop.ui

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.innertube.pages.HomePage
import echo.music.desktop.data.Recommendations
import echo.music.desktop.data.TasteModel
import echo.music.desktop.data.YouTubeRepository
import echo.music.desktop.playback.Playback
import echo.music.desktop.playback.PlayableSong
import echo.music.desktop.playback.songs
import echo.music.desktop.ui.components.ChipsRow
import echo.music.desktop.ui.components.MediaGridItem
import echo.music.desktop.ui.components.NavigationTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Desktop port of upstream's Home screen (`ui/screens/Home.kt`, `FEmusic_home`):
 * a personalized **Made for you** shelf on top, then the chip rail, then each YouTube shelf as an
 * uppercase `NavigationTitle` + horizontal carousel of 128.dp cards. Chips re-query the home
 * endpoint with the chip's `browseId`/`params`, exactly as upstream's `onChipClick` does.
 */
@Composable
fun HomeScreen(contentBottomPadding: Dp) {
    var chips by remember { mutableStateOf<List<HomePage.Chip>?>(null) }
    var sections by remember { mutableStateOf<List<HomePage.Section>>(emptyList()) }
    var chipIndex by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadTick by remember { mutableStateOf(0) }

    LaunchedEffect(chipIndex, reloadTick) {
        loading = true
        val endpoint = chips?.getOrNull(chipIndex)?.endpoint
        YouTubeRepository.home(
            browseId = endpoint?.browseId ?: "FEmusic_home",
            params = endpoint?.params,
        )
            .onSuccess { home ->
                sections = home.sections
                if (chips == null) chips = home.chips
                error = null
            }
            .onFailure { e ->
                if (sections.isEmpty()) error = e.message ?: "Couldn't load Home"
            }
        loading = false
    }

    // Personalized shelf: its own async load on Dispatchers.IO, so it never blocks or delays the
    // YouTube home shelves (or the chips) below it. Starts the moment Home composes; the mix
    // itself is cached ~10 min inside Recommendations, so re-entering Home is instant.
    val madeForYou by produceState<MadeForYouState>(MadeForYouState.Loading) {
        val state: MadeForYouState =
            withContext(Dispatchers.IO) {
                if (!TasteModel.hasTaste()) MadeForYouState.NoTaste
                else {
                    val songs = Recommendations.madeForYou()
                    if (songs.isEmpty()) MadeForYouState.Hidden else MadeForYouState.Loaded(songs)
                }
            }
        value = state
    }

    when {
        loading && sections.isEmpty() && error == null -> LoadingState()
        error != null && sections.isEmpty() -> ErrorState(error!!) { reloadTick++ }
        else ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = contentBottomPadding),
            ) {
                if (madeForYou != MadeForYouState.Hidden) {
                    item(key = "made-for-you") {
                        MadeForYouShelf(state = madeForYou)
                    }
                }
                chips?.let { chipList ->
                    if (chipList.isNotEmpty()) {
                        item(key = "chips") {
                            ChipsRow(
                                labels = chipList.map { it.title },
                                selectedIndex = chipIndex,
                                onSelect = { chipIndex = it },
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
                itemsIndexed(sections, key = { index, section -> "$index:${section.title}" }) { _, section ->
                    SectionBlock(section = section)
                }
                if (loading) {
                    item(key = "loading-more") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.height(24.dp))
                        }
                    }
                }
            }
    }
}

@Composable
private fun SectionBlock(section: HomePage.Section) {
    val songs = section.items.songs()
    Column(modifier = Modifier.padding(top = 8.dp)) {
        NavigationTitle(
            title = section.title,
            onClick = if (songs.isNotEmpty()) ({ playAll(section.items) }) else null,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(section.items, key = { it.id }) { item ->
                MediaGridItem(
                    title = item.title,
                    subtitle = subtitleFor(item),
                    thumbnail = item.thumbnail,
                    onClick = { onMediaClick(item, section.items) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * States of the personalized **Made for you** shelf:
 *
 *  - [Loading] — the mix is being fetched (a small spinner row, matching the shelf rhythm).
 *  - [NoTaste] — not enough listening data yet; we show an honest hint instead of fake cards.
 *  - [Loaded] — a mix exists; render the carousel.
 *  - [Hidden] — fetch failed or came back empty; the shelf (header included) disappears entirely.
 */
private sealed interface MadeForYouState {
    object Loading : MadeForYouState

    object NoTaste : MadeForYouState

    data class Loaded(val songs: List<PlayableSong>) : MadeForYouState

    object Hidden : MadeForYouState
}

/**
 * The "Made for you" shelf — Home's personalized row, driven by `data/Recommendations` (a local
 * `TasteModel` over play completion/skip/search signals). Same anatomy as every other shelf:
 * uppercase `NavigationTitle` with a "Play all" pill, then a `LazyRow` of 128.dp cards.
 */
@Composable
private fun MadeForYouShelf(state: MadeForYouState) {
    when (state) {
        MadeForYouState.Loading ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.height(24.dp))
            }

        MadeForYouState.NoTaste ->
            Column(modifier = Modifier.padding(top = 8.dp)) {
                NavigationTitle(title = "Made for you")
                Text(
                    text = "Play a few songs and this mix starts building itself.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                )
            }

        is MadeForYouState.Loaded -> {
            val songs = state.songs
            Column(modifier = Modifier.padding(top = 8.dp)) {
                NavigationTitle(
                    title = "Made for you",
                    onClick = { Playback.playQueue(songs, 0, expanded = false) },
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(songs, key = { _, song -> song.videoId }) { index, song ->
                        MediaGridItem(
                            title = song.title,
                            subtitle = song.artistLine,
                            thumbnail = song.thumbnail,
                            onClick = { Playback.playQueue(songs, index, expanded = true) },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        MadeForYouState.Hidden -> Unit
    }
}

@Composable
internal fun LoadingState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text("Loading your music…", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Couldn't load your music",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onRetry) { Text("Try again") }
    }
}
