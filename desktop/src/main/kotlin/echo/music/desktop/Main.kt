package echo.music.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import echo.music.desktop.playback.Playback
import echo.music.desktop.ui.DesktopTab
import echo.music.desktop.ui.FloatingNavPill
import echo.music.desktop.ui.HomeScreen
import echo.music.desktop.ui.LibraryScreen
import echo.music.desktop.ui.SearchScreen
import echo.music.desktop.ui.icons.EchoIcon
import echo.music.desktop.ui.player.FullPlayer
import echo.music.desktop.ui.player.MiniPlayer
import echo.music.desktop.ui.theme.EchoMusicTheme

/**
 * Echo Music — Windows desktop entry point (Compose Multiplatform).
 *
 * Shell mirrors upstream's MainActivity scaffold translated to desktop: 64.dp top bar with the
 * app title, content below, and the floating pill navigation + mini player sitting above the
 * bottom edge, with the full-screen player layered over everything when expanded.
 */
fun main() {
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Echo Music",
            icon = painterResource("icon.png"),
            state = rememberWindowState(width = 1360.dp, height = 860.dp),
        ) {
            val systemDark = isSystemInDarkTheme()
            var dark by remember(systemDark) { mutableStateOf(systemDark) }
            EchoMusicTheme(darkTheme = dark) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppShell(darkTheme = dark, onToggleTheme = { dark = !dark })
                }
            }
        }
    }
}

@Composable
fun AppShell(
    darkTheme: Boolean = true,
    onToggleTheme: () -> Unit = {},
    overrideTab: DesktopTab? = null,
    overrideExpanded: Boolean? = null,
    initialVideoMode: Boolean = false,
    initialShowQueue: Boolean = false,
    searchInitialQuery: String = "",
) {
    var tab by remember { mutableStateOf(DesktopTab.HOME) }
    val effectiveTab = overrideTab ?: tab
    val playback by Playback.state.collectAsState()
    val focusRequester = remember { FocusRequester() }
    val hasPlayer = playback.current != null
    val isExpanded = overrideExpanded ?: playback.playerExpanded
    // Room for the nav pill (56 + 12) plus the mini player (64 + 8) when a track is loaded.
    val bottomPadding = if (hasPlayer) 152.dp else 84.dp

    Box(
        modifier =
            Modifier.fillMaxSize()
                .focusRequester(focusRequester)
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyUp && event.key == Key.Escape) {
                        if (isExpanded) Playback.setExpanded(false)
                        true
                    } else {
                        false
                    }
                },
    ) {
        Column(Modifier.fillMaxSize()) {
            TopBar(darkTheme = darkTheme, onToggleTheme = onToggleTheme)
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (effectiveTab) {
                    DesktopTab.HOME -> HomeScreen(contentBottomPadding = bottomPadding)
                    DesktopTab.SEARCH -> SearchScreen(contentBottomPadding = bottomPadding, initialQuery = searchInitialQuery)
                    DesktopTab.LIBRARY -> LibraryScreen(contentBottomPadding = bottomPadding)
                }
            }
        }

        // ---- floating bottom chrome: mini player above the navigation pill
        Column(
            modifier =
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AnimatedVisibility(
                visible = hasPlayer && !isExpanded,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                MiniPlayer(onClick = { Playback.setExpanded(true) })
            }
            FloatingNavPill(current = effectiveTab, onSelect = { tab = it })
        }

        // ---- full-screen player
        AnimatedVisibility(
            visible = isExpanded && hasPlayer,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            FullPlayer(
                onCollapse = { Playback.setExpanded(false) },
                initialVideoMode = initialVideoMode,
                initialShowQueue = initialShowQueue,
            )
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

/** 64.dp top bar with the app title and theme toggle (upstream's app bar actions). */
@Composable
fun TopBar(darkTheme: Boolean, onToggleTheme: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .height(64.dp)
                .background(colors.background)
                .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.Image(
            painter = painterResource("icon.png"),
            contentDescription = "Echo Music Logo",
            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            "Echo Music",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = colors.onSurface,
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onToggleTheme) {
            EchoIcon(
                EchoIcon.CONTRAST,
                contentDescription = if (darkTheme) "Switch to light theme" else "Switch to dark theme",
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
