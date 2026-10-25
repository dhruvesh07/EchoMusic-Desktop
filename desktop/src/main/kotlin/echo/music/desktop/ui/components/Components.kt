package echo.music.desktop.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import echo.music.desktop.ui.icons.EchoIcon

/**
 * Metric constants copied from upstream `core/.../constants/Dimensions.kt` so the desktop port
 * keeps the Android app's exact rhythm.
 */
object Dimensions {
    val NavigationBarHeight = 72.dp
    val FloatingToolbarBottomPadding = 12.dp
    val MiniPlayerHeight = 64.dp
    val MiniPlayerBottomSpacing = 8.dp
    val AppBarHeight = 64.dp
    val ListItemHeight = 64.dp
    val ListThumbnailSize = 48.dp
    val ThumbnailCornerRadius = 6.dp
    val GridThumbnailHeight = 128.dp
    val SmallGridThumbnailHeight = 104.dp
}

/** 48.dp artwork, 6.dp corners — upstream `ItemThumbnail` default. */
@Composable
fun Artwork(
    url: String?,
    size: androidx.compose.ui.unit.Dp,
    cornerRadius: androidx.compose.ui.unit.Dp = Dimensions.ThumbnailCornerRadius,
    contentDescription: String? = null,
) {
    AsyncImage(
        model = url,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(size).clip(RoundedCornerShape(cornerRadius)).background(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
    )
}

/**
 * Shelf header — upstream `ui/component/NavigationTitle.kt`: uppercase `titleMedium` Bold in
 * `onSurfaceVariant`, with an optional "Play all" pill (`OutlinedButton`, 24.dp tall,
 * 1.dp border at 50% alpha, `labelSmall`).
 */
@Composable
fun NavigationTitle(
    title: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onClick != null) {
            OutlinedButton(
                onClick = onClick,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp,
                    vertical = 2.dp,
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(24.dp),
            ) {
                Text(
                    "Play all",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Upstream `ChipsRow` — pill filters; selected chip inverts to near-black with white text. */
@Composable
fun ChipsRow(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(4.dp))
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            FilterChip(
                selected = selected,
                onClick = { onSelect(index) },
                label = {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                    )
                },
                shape = RoundedCornerShape(12.dp),
                border = null,
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectedContainerColor = MaterialTheme.colorScheme.onSurface,
                    selectedLabelColor = MaterialTheme.colorScheme.surface,
                ),
                modifier = Modifier.height(32.dp),
            )
        }
        Spacer(Modifier.width(4.dp))
    }
}

/**
 * 64.dp song/album row — upstream `ListItem` in `ui/component/Items.kt`: 48.dp thumbnail at 6.dp
 * corners, `bodyMedium` Bold title, `bodySmall` secondary subtitle, `surfaceContainer` bed,
 * grouped rows get 24.dp squircle corners upstream.
 *
 * [onMore] is the playlist entry point: when supplied, a trailing ⋮ `IconButton` is appended
 * after the [trailing] slot (it consumes its own clicks, so the row's play action still wins
 * everywhere else). Existing callers pass nothing and see no change.
 */
@Composable
fun MediaListItem(
    title: String,
    subtitle: String,
    thumbnail: String?,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    showPlayingIndicator: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(Dimensions.ListItemHeight)
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isActive) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainer
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Artwork(thumbnail, Dimensions.ListThumbnailSize)
            if (showPlayingIndicator) PlayingIndicator()
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing?.invoke()
        if (onMore != null) {
            IconButton(onClick = onMore) {
                EchoIcon(
                    EchoIcon.MORE,
                    contentDescription = "More options",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Upstream `GridItem`: 128.dp thumbnail, `bodyLarge` Bold 1-line title, `bodyMedium` subtitle.
 *
 * Like [MediaListItem], an optional [onMore] adds a 24.dp circular ⋮ button overlaid on the
 * thumbnail's top-end corner. It is a sibling of the artwork inside the same [Box], so it eats
 * its own clicks instead of falling through to the card's click area.
 */
@Composable
fun MediaGridItem(
    title: String,
    subtitle: String,
    thumbnail: String?,
    modifier: Modifier = Modifier,
    thumbnailHeight: androidx.compose.ui.unit.Dp = Dimensions.GridThumbnailHeight,
    onMore: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier.width(thumbnailHeight * 1.15f).clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            AsyncImage(
                model = thumbnail,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.height(thumbnailHeight).fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            )
            if (onMore != null) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(4.dp).size(24.dp).clip(CircleShape)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f),
                            CircleShape,
                        )
                        .clickable(onClick = onMore),
                ) {
                    EchoIcon(
                        EchoIcon.MORE,
                        contentDescription = "More options",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Upstream `PlayingIndicator` — three bouncing bars shown while a row is the active track. */
@Composable
fun PlayingIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "playing")
    val color = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier.size(48.dp).background(
            Color.Black.copy(alpha = 0.45f), RoundedCornerShape(Dimensions.ThumbnailCornerRadius)
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(3) { index ->
            val phase by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(420, easing = LinearEasing, delayMillis = index * 120),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "bar$index",
            )
            Box(
                modifier = Modifier.padding(horizontal = 1.5.dp).width(3.dp)
                    .height((14.dp) * phase).background(color, RoundedCornerShape(1.5.dp))
            )
        }
    }
}

/** "•" separated subtitle, matching upstream's `joinByBullet(artists, duration)`. */
fun subtitle(artists: String, durationSeconds: Int): String {
    val duration = formatDuration(durationSeconds)
    return if (artists.isBlank()) duration
    else if (durationSeconds <= 0) artists
    else "$artists • $duration"
}

fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return ""
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatPosition(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    return formatPositionSeconds(total)
}

private fun formatPositionSeconds(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
