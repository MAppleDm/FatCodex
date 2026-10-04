package dev.dietapp.coreui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Black, white and three greys. The only colours are state colours: error, confirm, and the green tint of recorded food.
 * There is deliberately no dynamic colour and no tonal elevation.
 */
@Immutable
class AppColors(
    val background: Color,
    val foreground: Color,
    val secondary: Color,
    val tertiary: Color,
    val border: Color,
    val pressed: Color,
    val error: Color,
    val confirm: Color,
    /** Food that is recorded (counted): the foreground with a touch of green. */
    val recorded: Color,
    val isDark: Boolean,
)

val LightColors = AppColors(
    background = Color(0xFFFFFFFF),
    foreground = Color(0xFF0A0A0A),
    secondary = Color(0xFF6B6B6B),
    tertiary = Color(0xFFA3A3A3),
    border = Color(0xFFE5E5E5),
    pressed = Color(0xFFEBEBEB),
    error = Color(0xFFC62828),
    confirm = Color(0xFF2E7D32),
    recorded = Color(0xFF1E4D2E),
    isDark = false,
)

/** Pure black background: on an OLED screen those pixels are off. */
val DarkColors = AppColors(
    background = Color(0xFF000000),
    foreground = Color(0xFFF2F2F2),
    secondary = Color(0xFF9A9A9A),
    tertiary = Color(0xFF666666),
    border = Color(0xFF262626),
    pressed = Color(0xFF262626),
    error = Color(0xFFFF6B6B),
    confirm = Color(0xFF5BC27C),
    recorded = Color(0xFFDDF4E4),
    isDark = true,
)

/** One system sans-serif for text, one monospace for numbers. */
@Immutable
class AppType(
    val body: TextStyle,
    val secondary: TextStyle,
    val caption: TextStyle,
    val mono: TextStyle,
    val monoSecondary: TextStyle,
)

private fun appType(colors: AppColors) = AppType(
    body = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 22.sp, color = colors.foreground),
    secondary = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp, color = colors.secondary),
    caption = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 16.sp, color = colors.secondary),
    mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 20.sp, color = colors.foreground,
        fontWeight = FontWeight.Normal),
    monoSecondary = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 20.sp, color = colors.secondary),
)

private val LocalColors = staticCompositionLocalOf { LightColors }
private val LocalType = staticCompositionLocalOf { appType(LightColors) }

object AppTheme {
    val colors: AppColors
        @Composable @ReadOnlyComposable get() = LocalColors.current
    val type: AppType
        @Composable @ReadOnlyComposable get() = LocalType.current
}

@Composable
fun AppTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (darkTheme) DarkColors else LightColors
    // Material 3 is only the foundation: every surface is the background colour, nothing is tinted.
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = c.foreground, onPrimary = c.background, background = c.background, onBackground = c.foreground,
            surface = c.background, onSurface = c.foreground, surfaceVariant = c.pressed, onSurfaceVariant = c.secondary,
            surfaceTint = Color.Transparent, outline = c.border, outlineVariant = c.border, error = c.error,
        )
    } else {
        lightColorScheme(
            primary = c.foreground, onPrimary = c.background, background = c.background, onBackground = c.foreground,
            surface = c.background, onSurface = c.foreground, surfaceVariant = c.pressed, onSurfaceVariant = c.secondary,
            surfaceTint = Color.Transparent, outline = c.border, outlineVariant = c.border, error = c.error,
        )
    }
    CompositionLocalProvider(LocalColors provides c, LocalType provides appType(c)) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
