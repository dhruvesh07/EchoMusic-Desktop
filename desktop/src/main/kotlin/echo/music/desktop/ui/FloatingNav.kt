package echo.music.desktop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import echo.music.desktop.ui.icons.EchoIcon
import echo.music.desktop.ui.icons.EchoIcon as IconType

/** The three desktop destinations, mirroring upstream's default `bottomItems`. */
enum class DesktopTab(val label: String, val icon: IconType) {
    HOME("Home", IconType.HOME),
    SEARCH("Search", IconType.SEARCH),
    LIBRARY("Library", IconType.LIBRARY),
}

/**
 * Desktop port of upstream's `FloatingNavigationToolbar` (`ui/navigation/`): a floating pill
 * centered at the bottom, `surfaceContainerHigh` on a 1.dp outline at 30%, a sliding highlight
 * behind the active item (`onSurface` at 15%), the active icon scaled to 1.12, and the label
 * revealed only for the active tab.
 */
@Composable
fun FloatingNavPill(
    current: DesktopTab,
    onSelect: (DesktopTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val pillShape = RoundedCornerShape(50)

    Box(
        modifier = modifier.widthIn(max = 480.dp).fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier =
                Modifier.height(56.dp)
                    .clip(pillShape)
                    .background(colors.surfaceContainerHigh)
                    .border(1.dp, colors.outline.copy(alpha = 0.3f), pillShape)
                    .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DesktopTab.entries.forEach { tab ->
                NavItem(
                    tab = tab,
                    selected = tab == current,
                    onSelect = { onSelect(tab) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun NavItem(
    tab: DesktopTab,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val scale by animateFloatAsState(if (selected) 1.12f else 1f, tween(200), label = "iconScale")
    val labelAlpha by animateFloatAsState(if (selected) 1f else 0f, tween(200), label = "labelAlpha")

    Row(
        modifier =
            modifier
                .height(44.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    if (selected) colors.onSurface.copy(alpha = 0.15f)
                    else androidx.compose.ui.graphics.Color.Transparent,
                )
                .clickable(interactionSource = interaction, indication = null, onClick = onSelect)
                .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Box(modifier = Modifier.scale(scale)) {
            EchoIcon(
                tab.icon,
                contentDescription = tab.label,
                tint = if (selected) colors.onSurface else colors.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
        if (labelAlpha > 0.01f) {
            Text(
                tab.label,
                color = colors.onSurface,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.graphicsLayer(alpha = labelAlpha).padding(start = 8.dp),
            )
        }
    }
}
