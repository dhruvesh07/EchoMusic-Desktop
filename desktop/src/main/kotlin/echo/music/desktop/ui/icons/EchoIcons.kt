package echo.music.desktop.ui.icons

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import kotlin.math.min

/**
 * Hand-drawn vector icon set.
 *
 * Compose's `material-icons-core` artifact stops at 1.7.3 (it was folded into the platform
 * libraries afterwards), so pinning it next to Compose 1.12.2 would put a stale artifact on the
 * classpath. These icons are the geometric subset Echo Music's UI needs, drawn as paths with the
 * same optical rules as the Material set (24.dp box, ~2.dp stroke, round caps).
 */
enum class EchoIcon {
    PLAY,
    PAUSE,
    NEXT,
    PREVIOUS,
    SHUFFLE,
    REPEAT,
    REPEAT_ONE,
    HOME,
    SEARCH,
    LIBRARY,
    CHEVRON_DOWN,
    VOLUME,
    QUEUE,
    CLOSE,
    CONTRAST,
    VIDEO,
    MORE,
    PLAYLIST_ADD,
    PERSON,
    CHECK,
    PLUS,
}

@Composable
fun EchoIcon(
    type: EchoIcon,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Canvas(
        modifier =
            modifier.then(Modifier.size(24.dp)).then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            ),
    ) {
        drawIcon(type, tint)
    }
}

private fun DrawScope.drawIcon(type: EchoIcon, tint: Color) {
    val w = size.width
    val h = size.height
    val sw = min(w, h) * 0.085f
    val round = Stroke(width = sw, cap = StrokeCap.Round)
    fun p(x: Float, y: Float) = Offset(x * w, y * h)

    when (type) {
        EchoIcon.PLAY -> {
            val path =
                Path().apply {
                    moveTo(0.30f * w, 0.24f * h)
                    lineTo(0.80f * w, 0.50f * h)
                    lineTo(0.30f * w, 0.76f * h)
                    close()
                }
            drawPath(path, tint)
        }
        EchoIcon.PAUSE -> {
            val barW = 0.14f * w
            val corner = CornerRadius(min(w, h) * 0.035f)
            drawRoundRect(tint, p(0.28f, 0.24f), Size(barW, 0.52f * h), corner)
            drawRoundRect(tint, p(0.58f, 0.24f), Size(barW, 0.52f * h), corner)
        }
        EchoIcon.NEXT -> {
            val path =
                Path().apply {
                    moveTo(0.24f * w, 0.26f * h)
                    lineTo(0.66f * w, 0.50f * h)
                    lineTo(0.24f * w, 0.74f * h)
                    close()
                }
            drawPath(path, tint)
            val corner = CornerRadius(min(w, h) * 0.03f)
            drawRoundRect(tint, p(0.70f, 0.26f), Size(0.09f * w, 0.48f * h), corner)
        }
        EchoIcon.PREVIOUS -> {
            val path =
                Path().apply {
                    moveTo(0.76f * w, 0.26f * h)
                    lineTo(0.34f * w, 0.50f * h)
                    lineTo(0.76f * w, 0.74f * h)
                    close()
                }
            drawPath(path, tint)
            val corner = CornerRadius(min(w, h) * 0.03f)
            drawRoundRect(tint, p(0.21f, 0.26f), Size(0.09f * w, 0.48f * h), corner)
        }
        EchoIcon.SHUFFLE -> {
            // Two crossing tails with arrowheads — Material's shuffle silhouette.
            drawLine(tint, p(0.10f, 0.26f), p(0.34f, 0.26f), sw, StrokeCap.Round)
            drawLine(tint, p(0.34f, 0.26f), p(0.64f, 0.72f), sw, StrokeCap.Round)
            drawLine(tint, p(0.64f, 0.72f), p(0.78f, 0.72f), sw, StrokeCap.Round)
            drawPath(arrow(p(0.90f, 0.72f), p(0.78f, 0.72f), 0.11f * w), tint)

            drawLine(tint, p(0.10f, 0.74f), p(0.34f, 0.74f), sw, StrokeCap.Round)
            drawLine(tint, p(0.34f, 0.74f), p(0.64f, 0.28f), sw, StrokeCap.Round)
            drawLine(tint, p(0.64f, 0.28f), p(0.78f, 0.28f), sw, StrokeCap.Round)
            drawPath(arrow(p(0.90f, 0.28f), p(0.78f, 0.28f), 0.11f * w), tint)
        }
        EchoIcon.REPEAT, EchoIcon.REPEAT_ONE -> {
            val corner = CornerRadius(min(w, h) * 0.16f)
            drawRoundRect(
                tint,
                p(0.14f, 0.32f),
                Size(0.72f * w, 0.40f * h),
                corner,
                style = round,
            )
            drawPath(arrow(p(0.66f, 0.32f), p(0.78f, 0.32f), 0.11f * w), tint)
            if (type == EchoIcon.REPEAT_ONE) {
                drawLine(tint, p(0.50f, 0.44f), p(0.50f, 0.58f), sw, StrokeCap.Round)
                drawLine(tint, p(0.44f, 0.47f), p(0.50f, 0.44f), sw, StrokeCap.Round)
                drawLine(tint, p(0.43f, 0.58f), p(0.57f, 0.58f), sw, StrokeCap.Round)
            }
        }
        EchoIcon.HOME -> {
            val path =
                Path().apply {
                    moveTo(0.50f * w, 0.16f * h)
                    lineTo(0.86f * w, 0.46f * h)
                    lineTo(0.75f * w, 0.46f * h)
                    lineTo(0.75f * w, 0.80f * h)
                    lineTo(0.25f * w, 0.80f * h)
                    lineTo(0.25f * w, 0.46f * h)
                    lineTo(0.14f * w, 0.46f * h)
                    close()
                }
            drawPath(path, tint)
        }
        EchoIcon.SEARCH -> {
            drawCircle(tint, 0.23f * w, center = p(0.44f, 0.44f), style = round)
            drawLine(tint, p(0.62f, 0.62f), p(0.84f, 0.84f), sw, StrokeCap.Round)
        }
        EchoIcon.LIBRARY -> {
            listOf(0.32f, 0.50f, 0.68f).forEach { y ->
                drawLine(tint, p(0.32f, y), p(0.84f, y), sw, StrokeCap.Round)
            }
            listOf(0.32f, 0.50f, 0.68f).forEach { y ->
                drawCircle(tint, sw * 0.85f, center = p(0.18f, y))
            }
        }
        EchoIcon.CHEVRON_DOWN -> {
            drawLine(tint, p(0.26f, 0.42f), p(0.50f, 0.62f), sw, StrokeCap.Round)
            drawLine(tint, p(0.50f, 0.62f), p(0.74f, 0.42f), sw, StrokeCap.Round)
        }
        EchoIcon.VOLUME -> {
            val speaker =
                Path().apply {
                    moveTo(0.18f * w, 0.40f * h)
                    lineTo(0.34f * w, 0.40f * h)
                    lineTo(0.54f * w, 0.24f * h)
                    lineTo(0.54f * w, 0.76f * h)
                    lineTo(0.34f * w, 0.60f * h)
                    lineTo(0.18f * w, 0.60f * h)
                    close()
                }
            drawPath(speaker, tint)
            drawArc(
                tint,
                startAngle = -55f,
                sweepAngle = 110f,
                useCenter = false,
                topLeft = p(0.54f, 0.36f),
                size = Size(0.20f * w, 0.28f * h),
                style = round,
            )
            drawArc(
                tint,
                startAngle = -55f,
                sweepAngle = 110f,
                useCenter = false,
                topLeft = p(0.54f, 0.26f),
                size = Size(0.34f * w, 0.48f * h),
                style = round,
            )
        }
        EchoIcon.QUEUE -> {
            listOf(0.32f, 0.50f, 0.68f).forEach { y ->
                drawLine(tint, p(0.14f, y), p(0.62f, y), sw, StrokeCap.Round)
            }
            val path =
                Path().apply {
                    moveTo(0.70f * w, 0.38f * h)
                    lineTo(0.90f * w, 0.50f * h)
                    lineTo(0.70f * w, 0.62f * h)
                    close()
                }
            drawPath(path, tint)
        }
        EchoIcon.CLOSE -> {
            drawLine(tint, p(0.28f, 0.28f), p(0.72f, 0.72f), sw, StrokeCap.Round)
            drawLine(tint, p(0.72f, 0.28f), p(0.28f, 0.72f), sw, StrokeCap.Round)
        }
        EchoIcon.CONTRAST -> {
            drawCircle(tint, 0.34f * w, center = p(0.50f, 0.50f), style = round)
            val path =
                Path().apply {
                    // Right half filled, like Material's dark_mode glyph.
                    moveTo(0.50f * w, 0.16f * h)
                    lineTo(0.50f * w, 0.84f * h)
                    arcTo(
                        androidx.compose.ui.geometry.Rect(p(0.16f, 0.16f), Size(0.68f * w, 0.68f * h)),
                        startAngleDegrees = 90f,
                        sweepAngleDegrees = -180f,
                        forceMoveTo = false,
                    )
                    close()
                }
            drawPath(path, tint)
        }
        EchoIcon.VIDEO -> {
            // Camera body + lens wedge, with a play glyph inside.
            drawRoundRect(
                tint,
                p(0.10f, 0.26f),
                Size(0.60f * w, 0.48f * h),
                CornerRadius(min(w, h) * 0.14f),
                style = round,
            )
            val wedge =
                Path().apply {
                    moveTo(0.74f * w, 0.36f * h)
                    lineTo(0.92f * w, 0.26f * h)
                    lineTo(0.92f * w, 0.74f * h)
                    lineTo(0.74f * w, 0.64f * h)
                    close()
                }
            drawPath(wedge, tint)
            val glyph =
                Path().apply {
                    moveTo(0.33f * w, 0.40f * h)
                    lineTo(0.50f * w, 0.50f * h)
                    lineTo(0.33f * w, 0.60f * h)
                    close()
                }
            drawPath(glyph, tint)
        }
        EchoIcon.MORE -> {
            listOf(0.26f, 0.50f, 0.74f).forEach { y ->
                drawCircle(tint, min(w, h) * 0.075f, center = p(0.50f, y))
            }
        }
        EchoIcon.PLAYLIST_ADD -> {
            listOf(0.26f, 0.42f, 0.58f).forEach { y ->
                drawLine(tint, p(0.10f, y), p(0.58f, y), sw, StrokeCap.Round)
            }
            drawLine(tint, p(0.76f, 0.62f), p(0.76f, 0.90f), sw, StrokeCap.Round)
            drawLine(tint, p(0.62f, 0.76f), p(0.90f, 0.76f), sw, StrokeCap.Round)
        }
        EchoIcon.PERSON -> {
            drawCircle(tint, 0.15f * w, center = p(0.50f, 0.34f), style = round)
            drawArc(
                tint,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = p(0.24f, 0.52f),
                size = Size(0.52f * w, 0.46f * h),
                style = round,
            )
        }
        EchoIcon.CHECK -> {
            drawLine(tint, p(0.22f, 0.52f), p(0.43f, 0.72f), sw, StrokeCap.Round)
            drawLine(tint, p(0.43f, 0.72f), p(0.78f, 0.30f), sw, StrokeCap.Round)
        }
        EchoIcon.PLUS -> {
            drawLine(tint, p(0.50f, 0.22f), p(0.50f, 0.78f), sw, StrokeCap.Round)
            drawLine(tint, p(0.22f, 0.50f), p(0.78f, 0.50f), sw, StrokeCap.Round)
        }
    }
}

/** Arrowhead whose tip is [tip] and whose base sits [back] along the shaft direction. */
private fun arrow(tip: Offset, back: Offset, spread: Float): Path {
    val dx = tip.x - back.x
    val dy = tip.y - back.y
    val len = hypot(dx, dy).coerceAtLeast(0.001f)
    val ux = dx / len
    val uy = dy / len
    val px = -uy
    val py = ux
    val half = spread * 0.55f
    return Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(back.x + px * half, back.y + py * half)
        lineTo(back.x - px * half, back.y - py * half)
        close()
    }
}
