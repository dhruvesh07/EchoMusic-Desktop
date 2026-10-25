package echo.music.desktop.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.rememberDynamicColorScheme

/** Upstream's seed color (app/.../ui/theme/Theme.kt: `DefaultThemeColor`). */
val DefaultThemeColor = Color(0xFFED5564)

/** Upstream's `pureBlack(true)` — surface/background forced to true black, M3 tones untouched. */
fun ColorScheme.pureBlack(apply: Boolean): ColorScheme =
    if (apply) copy(surface = Color.Black, background = Color.Black) else this

/**
 * Desktop port of upstream `echomusicTheme`.
 *
 * Upstream derives its entire palette at runtime from the seed with materialKolor
 * (TonalSpot / SPEC_2025) — same call on the same library version (4.1.1), so the tones match the
 * Android app exactly. Android 12 dynamic color has no desktop equivalent and is dropped;
 * `pureBlack` and the seed stay.
 */
@Composable
fun EchoMusicTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    pureBlack: Boolean = false,
    themeColor: Color = DefaultThemeColor,
    content: @Composable () -> Unit,
) {
    val base =
        rememberDynamicColorScheme(
            seedColor = themeColor,
            isDark = darkTheme,
            specVersion = ColorSpec.SpecVersion.SPEC_2025,
            style = PaletteStyle.TonalSpot,
        )

    val colorScheme =
        remember(base, pureBlack, darkTheme) {
            if (darkTheme && pureBlack) base.pureBlack(true) else base
        }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = echoTypography,
        // The only shape upstream overrides in the theme.
        shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(24.dp)),
        content = content,
    )
}

/**
 * Type scale from upstream `ui/theme/Type.kt` (`getTypography`) — sizes, weights, line heights and
 * letter spacing match exactly. `brandFont` is [GoogleSansFontFamily] (upstream's default brand
 * pick for this port; the Android app offers all four families plus system/custom in Settings,
 * which this port doesn't have a settings screen for yet) and `plainFont` stays
 * `FontFamily.Default` for body/label, exactly like upstream's `getTypography(brandFont)` call.
 */
val echoTypography: Typography
    get() {
        val brand: FontFamily = GoogleSansFontFamily
        val plain: FontFamily = FontFamily.Default

        fun brand(
            size: Int,
            lineHeight: Int,
            letterSpacing: Double,
            weight: FontWeight = FontWeight.Normal,
        ) = TextStyle(
            fontFamily = brand,
            fontWeight = weight,
            fontSize = size.sp,
            lineHeight = lineHeight.sp,
            letterSpacing = letterSpacing.sp,
        )

        fun body(
            size: Int,
            lineHeight: Int,
            letterSpacing: Double,
            weight: FontWeight = FontWeight.Normal,
        ) = TextStyle(
            fontFamily = plain,
            fontWeight = weight,
            fontSize = size.sp,
            lineHeight = lineHeight.sp,
            letterSpacing = letterSpacing.sp,
        )

        return Typography(
            displayLarge = brand(57, 64, -0.25),
            displayMedium = brand(45, 52, 0.0),
            displaySmall = brand(36, 44, 0.0),
            headlineLarge = brand(32, 40, 0.0),
            headlineMedium = brand(28, 36, 0.0),
            headlineSmall = brand(24, 32, 0.0),
            titleLarge = brand(22, 28, 0.0),
            titleMedium = brand(16, 24, 0.15, FontWeight.Medium),
            titleSmall = brand(14, 20, 0.1, FontWeight.Medium),
            bodyLarge = body(16, 24, 0.5),
            bodyMedium = body(14, 20, 0.25),
            bodySmall = body(12, 16, 0.4),
            labelLarge = body(14, 20, 0.1, FontWeight.Medium),
            labelMedium = body(12, 16, 0.5, FontWeight.Medium),
            labelSmall = body(11, 16, 0.5, FontWeight.Medium),
        )
    }

/** Fallback scheme for code that runs before the theme composes. */
val fallbackDarkScheme = darkColorScheme()
