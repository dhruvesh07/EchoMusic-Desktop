package echo.music.desktop.tools

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import echo.music.desktop.AppShell
import echo.music.desktop.data.PlayHistory
import echo.music.desktop.data.PlaylistStore
import echo.music.desktop.data.TasteModel
import echo.music.desktop.playback.PlayableSong
import echo.music.desktop.playback.Playback
import echo.music.desktop.ui.DesktopTab
import echo.music.desktop.ui.theme.EchoMusicTheme
import java.awt.Rectangle
import java.awt.Robot
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.delay

/**
 * Automated screenshot generator for Echo Music Desktop.
 * Captures clean, high-resolution 720p screenshots of all primary desktop screens
 * in the exact presentation style of Echo Music.
 */
fun main() = application {
    val sampleSongs =
        listOf(
            PlayableSong(
                videoId = "4NRXx6U8ABQ",
                title = "Blinding Lights",
                artists = listOf("The Weeknd"),
                thumbnail = "https://i.ytimg.com/vi/4NRXx6U8ABQ/hqdefault.jpg",
                durationMs = 200000L,
            ),
            PlayableSong(
                videoId = "TUVcZfQe-Kw",
                title = "Levitating",
                artists = listOf("Dua Lipa"),
                thumbnail = "https://i.ytimg.com/vi/TUVcZfQe-Kw/hqdefault.jpg",
                durationMs = 203000L,
            ),
            PlayableSong(
                videoId = "dvgZkm1xWPE",
                title = "Viva La Vida",
                artists = listOf("Coldplay"),
                thumbnail = "https://i.ytimg.com/vi/dvgZkm1xWPE/hqdefault.jpg",
                durationMs = 242000L,
            ),
            PlayableSong(
                videoId = "wXhTHyIgQ_U",
                title = "Circles",
                artists = listOf("Post Malone"),
                thumbnail = "https://i.ytimg.com/vi/wXhTHyIgQ_U/hqdefault.jpg",
                durationMs = 215000L,
            ),
            PlayableSong(
                videoId = "5NV6Rdv1a3I",
                title = "Get Lucky (feat. Pharrell Williams)",
                artists = listOf("Daft Punk"),
                thumbnail = "https://i.ytimg.com/vi/5NV6Rdv1a3I/hqdefault.jpg",
                durationMs = 248000L,
            ),
            PlayableSong(
                videoId = "bpOSxM0rNPM",
                title = "Do I Wanna Know?",
                artists = listOf("Arctic Monkeys"),
                thumbnail = "https://i.ytimg.com/vi/bpOSxM0rNPM/hqdefault.jpg",
                durationMs = 272000L,
            ),
        )

    // Pre-populate playlists and play history if empty
    if (PlaylistStore.all().isEmpty()) {
        val p1 = PlaylistStore.create("Synthwave & Hits")
        val p2 = PlaylistStore.create("Timeless Classics")
        val p3 = PlaylistStore.create("Indie & Rock Favorites")
        PlaylistStore.addSong(p1.id, sampleSongs[0])
        PlaylistStore.addSong(p1.id, sampleSongs[1])
        PlaylistStore.addSong(p1.id, sampleSongs[3])
        PlaylistStore.addSong(p2.id, sampleSongs[2])
        PlaylistStore.addSong(p2.id, sampleSongs[4])
        PlaylistStore.addSong(p3.id, sampleSongs[2])
        PlaylistStore.addSong(p3.id, sampleSongs[5])
    }

    sampleSongs.forEach { song ->
        PlayHistory.record(song)
        TasteModel.onTrackStarted(song)
        TasteModel.onTrackCompleted(song)
    }

    Playback.setQueueForDisplay(sampleSongs, currentIndex = 0, isPlaying = true, positionMs = 74000L)

    var stage by remember { mutableStateOf(0) }
    val windowState = rememberWindowState(width = 1280.dp, height = 720.dp)

    Window(
        onCloseRequest = ::exitApplication,
        title = "Echo Music",
        icon = painterResource("icon.png"),
        state = windowState,
    ) {
        val window = this.window

        EchoMusicTheme(darkTheme = true) {
            Surface(modifier = Modifier.fillMaxSize()) {
                when (stage) {
                    0 -> AppShell(
                        darkTheme = true,
                        overrideTab = DesktopTab.HOME,
                        overrideExpanded = false,
                    )
                    1 -> AppShell(
                        darkTheme = true,
                        overrideTab = DesktopTab.HOME,
                        overrideExpanded = true,
                        initialVideoMode = false,
                        initialShowQueue = false,
                    )
                    2 -> AppShell(
                        darkTheme = true,
                        overrideTab = DesktopTab.HOME,
                        overrideExpanded = true,
                        initialVideoMode = true,
                        initialShowQueue = false,
                    )
                    3 -> AppShell(
                        darkTheme = true,
                        overrideTab = DesktopTab.HOME,
                        overrideExpanded = true,
                        initialVideoMode = false,
                        initialShowQueue = true,
                    )
                    4 -> AppShell(
                        darkTheme = true,
                        overrideTab = DesktopTab.SEARCH,
                        overrideExpanded = false,
                        searchInitialQuery = "The Weeknd",
                    )
                    5 -> AppShell(
                        darkTheme = true,
                        overrideTab = DesktopTab.LIBRARY,
                        overrideExpanded = false,
                    )
                }
            }
        }

        LaunchedEffect(Unit) {
            val outDir = File("Screenshots")
            if (!outDir.exists()) outDir.mkdirs()
            val robot = Robot()

            fun capture(fileName: String) {
                val loc = window.locationOnScreen
                val insets = window.insets
                val x = loc.x + insets.left
                val y = loc.y + insets.top
                val w = (window.width - insets.left - insets.right).coerceAtLeast(100)
                val h = (window.height - insets.top - insets.bottom).coerceAtLeast(100)
                val rect = Rectangle(x, y, w, h)
                val img = robot.createScreenCapture(rect)
                val file = File(outDir, fileName)
                ImageIO.write(img, "PNG", file)
                println("Saved: ${file.absolutePath} (${img.width}x${img.height})")
            }

            // 0: HOME
            println("Waiting for Home screen...")
            delay(5500)
            capture("Home.png")

            // 1: FULL PLAYER - SONG
            stage = 1
            println("Waiting for Player (Song mode)...")
            delay(3500)
            capture("Player_Song.png")

            // 2: FULL PLAYER - VIDEO
            stage = 2
            println("Waiting for Player (Video mode)...")
            delay(3000)
            capture("Player_Video.png")

            // 3: QUEUE
            stage = 3
            println("Waiting for Player (Queue)...")
            delay(2500)
            capture("Queue.png")

            // 4: SEARCH
            stage = 4
            println("Waiting for Search screen...")
            delay(4000)
            capture("Search.png")

            // 5: LIBRARY
            stage = 5
            println("Waiting for Library screen...")
            delay(3000)
            capture("Library.png")

            println("ALL 6 SCREENSHOTS CAPTURED SUCCESSFULLY!")
            exitApplication()
        }
    }
}
