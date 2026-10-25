package echo.music.desktop.ui.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import echo.music.desktop.playback.Playback
import echo.music.desktop.ui.components.Dimensions
import echo.music.desktop.ui.icons.EchoIcon
import echo.music.desktop.ui.icons.EchoIcon as IconType

/**
 * Desktop port of upstream's mini player (`ui/player/MiniPlayer.kt`) with its exact metrics:
 * a 64.dp pill capped at the tablet-width 480.dp, 1.dp outline at 30% over
 * `surfaceContainerHigh`, a 48.dp progress ring (3.dp round stroke) around a 40.dp circular
 * thumbnail, 14.sp/500 title and 12.sp/70% artists, and 32/48/32 transport with the rotating
 * "cookie" play button.
 */
@Composable
fun MiniPlayer(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val state by Playback.state.collectAsState()
    val song = state.current ?: return
    val colors = MaterialTheme.colorScheme

    val progress =
        if (state.durationMs > 0) {
            (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
        } else {
            0f
        }

    Box(
        modifier =
            modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .height(Dimensions.MiniPlayerHeight)
                .clip(RoundedCornerShape(percent = 50))
                .background(colors.surfaceContainerHigh)
                .border(1.dp, colors.outline.copy(alpha = 0.3f), RoundedCornerShape(percent = 50))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                )
                .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(Dimensions.MiniPlayerHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RingArtwork(progress = progress, thumbnail = song.thumbnail, colors = colors)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    song.title,
                    color = colors.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val status: String =
                    when {
                        state.error != null -> "⚠ ${state.error}"
                        state.isBuffering -> "Buffering…"
                        else -> song.artistLine
                    }
                Text(
                    status,
                    color =
                        if (state.error != null) colors.error
                        else colors.onSurface.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(4.dp))
            TransportRow(
                isPlaying = state.isPlaying,
                isBuffering = state.isBuffering,
                colors = colors,
            )
        }
    }
}

/** 48.dp ring (track at 20% alpha, progress in `onSurface`) around a 40.dp thumbnail. */
@Composable
private fun RingArtwork(
    progress: Float,
    thumbnail: String?,
    colors: androidx.compose.material3.ColorScheme,
) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            val inset = 1.5.dp.toPx()
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val origin = Offset(inset, inset)
            drawArc(
                colors.outline.copy(alpha = 0.2f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = origin,
                size = arcSize,
                style = stroke,
            )
            if (progress > 0f) {
                drawArc(
                    colors.onSurface,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = origin,
                    size = arcSize,
                    style = stroke,
                )
            }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.size(40.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceVariant.copy(alpha = 0.6f)),
        ) {
            AsyncImage(
                model = thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.fillMaxSize()
                    .border(1.dp, colors.outline.copy(alpha = 0.3f), CircleShape),
            )
        }
    }
}

/** prev 32.dp / play 48.dp / next 32.dp — upstream's `MiniPlayerControls`. */
@Composable
private fun TransportRow(
    isPlaying: Boolean,
    isBuffering: Boolean,
    colors: androidx.compose.material3.ColorScheme,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        MiniButton(
            description = "Previous",
            enabled = !isBuffering,
            onClick = { Playback.previous() },
        ) {
            EchoIcon(IconType.PREVIOUS, tint = colors.onSurface, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        CookieButton(isPlaying = isPlaying, isBuffering = isBuffering, colors = colors)
        Spacer(Modifier.width(12.dp))
        MiniButton(
            description = "Next",
            enabled = !isBuffering,
            onClick = { Playback.next() },
        ) {
            EchoIcon(IconType.NEXT, tint = colors.onSurface, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun MiniButton(
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier =
            Modifier.size(32.dp)
                .scale(if (pressed) 0.91f else 1f)
                .clip(RoundedCornerShape(50))
                .semantics { contentDescription = description }
                .clickable(interactionSource = interaction, indication = null, enabled = enabled) {
                    onClick()
                },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * 48.dp circle whose background is the 9-sided "cookie" polygon, rotating only while playing
 * (upstream `PolygonCookieShape` + its progress rotation).
 */
@Composable
private fun CookieButton(
    isPlaying: Boolean,
    isBuffering: Boolean,
    colors: androidx.compose.material3.ColorScheme,
) {
    val transition = rememberInfiniteTransition(label = "cookie")
    val rotation by
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec =
                infiniteRepeatable(tween(8_000, easing = LinearEasing), RepeatMode.Restart),
            label = "rotation",
        )
    val indent by
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 0.08f,
            animationSpec = infiniteRepeatable(tween(300), RepeatMode.Reverse),
            label = "indent",
        )

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.size(48.dp)
                .clip(CircleShape)
                .semantics {
                    contentDescription =
                        when {
                            isBuffering -> "Buffering"
                            isPlaying -> "Pause"
                            else -> "Play"
                        }
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = !isBuffering,
                ) {
                    Playback.togglePlay()
                },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            val shapeIndent = if (isPlaying) indent else 0f
            val path = androidx.compose.ui.graphics.Path()
            for (angle in 0..360 step 3) {
                val theta = Math.toRadians(angle.toDouble())
                val r =
                    radius * (1f - shapeIndent + shapeIndent * kotlin.math.cos(9 * theta).toFloat())
                val x = size.width / 2f + (r * kotlin.math.cos(theta)).toFloat()
                val y = size.height / 2f + (r * kotlin.math.sin(theta)).toFloat()
                if (angle == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            if (isPlaying && rotation != 0f) {
                withTransform({ rotate(degrees = rotation, pivot = center) }) {
                    drawPath(path, colors.onSurface)
                }
            } else {
                drawPath(path, colors.onSurface)
            }
        }
        if (isBuffering) {
            // YouTube-style: the transport centre becomes a spinner while a seek re-buffers.
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = colors.onSurface,
            )
        } else {
            EchoIcon(
                if (isPlaying) IconType.PAUSE else IconType.PLAY,
                contentDescription = null,
                tint = colors.surface,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
