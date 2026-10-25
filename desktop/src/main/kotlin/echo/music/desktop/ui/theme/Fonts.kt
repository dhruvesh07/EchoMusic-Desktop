package echo.music.desktop.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font as PlatformFont

/**
 * Brand typefaces, ported from upstream `ui/theme/Type.kt`.
 *
 * Android loads these via `R.font.*`; desktop loads the same TTFs from the classpath
 * (`src/main/resources/fonts/`) through `androidx.compose.ui.text.platform.Font(resource)`,
 * which resolves them with Skia and applies `FontVariation` axis settings at load time — so the
 * variable-font instances (weight 400/500/700, `wdth` 100, Google Sans's `ROND` axis) match what
 * the Android app renders, rather than faking bold synthetically.
 *
 * The axis settings below are copied verbatim from upstream's `GoogleSansFontFamily`,
 * `SansFontFamily`, `OutfitFontFamily` and `PlusJakartaSansFontFamily`.
 */

private fun classpathFontAvailable(resourceName: String): Boolean =
    runCatching {
        val loader = Thread.currentThread().contextClassLoader
        loader?.getResource(resourceName) != null ||
            ClassLoader.getSystemResource(resourceName) != null
    }
        .getOrDefault(false)

/** Upstream: `Font(R.font.google_sans_flex, …)` × 3 weights with width/ROND axes. */
@OptIn(ExperimentalTextApi::class)
val GoogleSansFontFamily: FontFamily by lazy {
    if (!classpathFontAvailable("fonts/google_sans_flex.ttf")) return@lazy FontFamily.Default
    FontFamily(
        PlatformFont(
            resource = "fonts/google_sans_flex.ttf",
            weight = FontWeight.Normal,
            variationSettings =
                FontVariation.Settings(
                    FontVariation.weight(400),
                    FontVariation.width(100f),
                    FontVariation.Setting("ROND", 100f),
                ),
        ),
        PlatformFont(
            resource = "fonts/google_sans_flex.ttf",
            weight = FontWeight.Medium,
            variationSettings =
                FontVariation.Settings(
                    FontVariation.weight(500),
                    FontVariation.width(100f),
                    FontVariation.Setting("ROND", 100f),
                ),
        ),
        PlatformFont(
            resource = "fonts/google_sans_flex.ttf",
            weight = FontWeight.Bold,
            variationSettings =
                FontVariation.Settings(
                    FontVariation.weight(700),
                    FontVariation.width(100f),
                    FontVariation.Setting("ROND", 100f),
                ),
        ),
    )
}

/** Upstream: `Font(R.font.sans_flex, …)` — same axes as Google Sans Flex. */
@OptIn(ExperimentalTextApi::class)
val SansFlexFontFamily: FontFamily by lazy {
    if (!classpathFontAvailable("fonts/sans_flex.ttf")) return@lazy FontFamily.Default
    FontFamily(
        PlatformFont(
            resource = "fonts/sans_flex.ttf",
            weight = FontWeight.Normal,
            variationSettings =
                FontVariation.Settings(
                    FontVariation.weight(400),
                    FontVariation.width(100f),
                    FontVariation.Setting("ROND", 100f),
                ),
        ),
        PlatformFont(
            resource = "fonts/sans_flex.ttf",
            weight = FontWeight.Medium,
            variationSettings =
                FontVariation.Settings(
                    FontVariation.weight(500),
                    FontVariation.width(100f),
                    FontVariation.Setting("ROND", 100f),
                ),
        ),
        PlatformFont(
            resource = "fonts/sans_flex.ttf",
            weight = FontWeight.Bold,
            variationSettings =
                FontVariation.Settings(
                    FontVariation.weight(700),
                    FontVariation.width(100f),
                    FontVariation.Setting("ROND", 100f),
                ),
        ),
    )
}

/** Upstream: `Font(R.font.outfit, …)` — weight axis only. */
@OptIn(ExperimentalTextApi::class)
val OutfitFontFamily: FontFamily by lazy {
    if (!classpathFontAvailable("fonts/outfit.ttf")) return@lazy FontFamily.Default
    FontFamily(
        PlatformFont(
            resource = "fonts/outfit.ttf",
            weight = FontWeight.Normal,
            variationSettings = FontVariation.Settings(FontVariation.weight(400)),
        ),
        PlatformFont(
            resource = "fonts/outfit.ttf",
            weight = FontWeight.Medium,
            variationSettings = FontVariation.Settings(FontVariation.weight(500)),
        ),
        PlatformFont(
            resource = "fonts/outfit.ttf",
            weight = FontWeight.Bold,
            variationSettings = FontVariation.Settings(FontVariation.weight(700)),
        ),
    )
}

/** Upstream: `Font(R.font.plus_jakarta_sans, …)` — weight axis only. */
@OptIn(ExperimentalTextApi::class)
val PlusJakartaSansFontFamily: FontFamily by lazy {
    if (!classpathFontAvailable("fonts/plus_jakarta_sans.ttf")) return@lazy FontFamily.Default
    FontFamily(
        PlatformFont(
            resource = "fonts/plus_jakarta_sans.ttf",
            weight = FontWeight.Normal,
            variationSettings = FontVariation.Settings(FontVariation.weight(400)),
        ),
        PlatformFont(
            resource = "fonts/plus_jakarta_sans.ttf",
            weight = FontWeight.Medium,
            variationSettings = FontVariation.Settings(FontVariation.weight(500)),
        ),
        PlatformFont(
            resource = "fonts/plus_jakarta_sans.ttf",
            weight = FontWeight.Bold,
            variationSettings = FontVariation.Settings(FontVariation.weight(700)),
        ),
    )
}
