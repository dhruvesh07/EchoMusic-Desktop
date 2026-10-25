package echo.music.desktop.ui.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.awt.image.BufferedImage
import java.net.URL
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Desktop port of upstream `PlayerColorExtractor` + `Bitmap.extractGradientColors`
 * (app/.../ui/theme/Theme.kt, PlayerColorExtractor.kt).
 *
 * YouTube thumbnails are JPEGs on i.ytimg.com, so `javax.imageio` decodes them without pulling in
 * a Skia pixel-reading API. The ranking/enhancement math is upstream's: population-weighted
 * vibrancy score, `saturation * 1.3`, brightness clamped to [0.4, 0.85], and the 3-stop gradient
 * `[primary, primary × 0.6, Black]` that the GRADIENT player background paints.
 */
object ArtworkColors {

    private val cache = object : LinkedHashMap<String, List<Color>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Color>>) =
            size > 24
    }

    private val fallback = listOf(Color(0xFF595959), Color(0xFF0D0D0D))

    suspend fun gradientFor(url: String?): List<Color> {
        if (url.isNullOrBlank()) return fallback
        synchronized(cache) { cache[url] }?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching { extract(url) }.getOrDefault(fallback).also {
                synchronized(cache) { cache[url] = it }
            }
        }
    }

    private fun extract(url: String): List<Color> {
        val image: BufferedImage = ImageIO.read(URL(url)) ?: return fallback

        // Sample a coarse grid — matches upstream's `resizeBitmapArea(200×200)` intent.
        val step = max(1, max(image.width, image.height) / 64)
        val populations = HashMap<Int, Int>()
        var y = 0
        while (y < image.height) {
            var x = 0
            while (x < image.width) {
                val argb = image.getRGB(x, y)
                // Skip near-transparent / near-white / near-black noise.
                val rgb = argb and 0xFFFFFF
                val hsv = hsvOf(rgb)
                if (hsv[1] > 0.12f && hsv[2] > 0.10f && hsv[2] < 0.97f) {
                    populations[rgb] = (populations[rgb] ?: 0) + 1
                }
                x += step
            }
            y += step
        }
        if (populations.isEmpty()) return fallback

        val total = populations.values.sum().coerceAtLeast(1)
        val best = populations.maxByOrNull { (rgb, count) ->
            val hsv = hsvOf(rgb)
            val populationWeight = count * 2f
            val vibrancyBonus = if (hsv[1] > 0.3f && hsv[2] > 0.3f) 1.5f else 1f
            populationWeight * vibrancyBonus * (hsv[1] + hsv[2]) / 2f
        } ?: return fallback

        val primary = enhance(best.key)
        val secondary = Color(
            (primary.red * 0.6f).coerceAtLeast(0f),
            (primary.green * 0.6f).coerceAtLeast(0f),
            (primary.blue * 0.6f).coerceAtLeast(0f),
            1f,
        )
        return listOf(primary, secondary, Color.Black)
    }

    /** Upstream `isColorVibrant` + `enhanceColorEchodness`. */
    private fun enhance(rgb: Int): Color {
        val hsv = hsvOf(rgb)
        val vibrant = hsv[1] > 0.25f && hsv[2] > 0.2f && hsv[2] < 0.9f
        val saturationFactor = if (vibrant) 1.3f else 1.1f
        val saturation = (hsv[1] * saturationFactor).coerceAtMost(1f)
        val brightness = (hsv[2] * 0.9f).coerceIn(0.4f, 0.85f)
        return Color(hsvToArgb(hsv[0], saturation, brightness))
    }

    private fun hsvOf(rgb: Int): FloatArray {
        val r = ((rgb shr 16) and 0xFF) / 255f
        val g = ((rgb shr 8) and 0xFF) / 255f
        val b = (rgb and 0xFF) / 255f
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val delta = maxC - minC
        val hue =
            when {
                delta == 0f -> 0f
                maxC == r -> ((g - b) / delta) % 6f
                maxC == g -> (b - r) / delta + 2f
                else -> (r - g) / delta + 4f
            }.let { (it * 60f + 360f) % 360f }
        val saturation = if (delta == 0f) 0f else delta / maxC
        return floatArrayOf(hue, saturation, maxC)
    }

    private fun hsvToArgb(hue: Float, saturation: Float, value: Float): Int {
        val c = value * saturation
        val x = c * (1 - kotlin.math.abs(((hue / 60f) % 2f) - 1f))
        val m = value - c
        val (r1, g1, b1) =
            when {
                hue < 60f -> Triple(c, x, 0f)
                hue < 120f -> Triple(x, c, 0f)
                hue < 180f -> Triple(0f, c, x)
                hue < 240f -> Triple(0f, x, c)
                hue < 300f -> Triple(x, 0f, c)
                else -> Triple(c, 0f, x)
            }
        val r = ((r1 + m) * 255).toInt().coerceIn(0, 255)
        val g = ((g1 + m) * 255).toInt().coerceIn(0, 255)
        val b = ((b1 + m) * 255).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
