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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** The system option follows the phone; the three named palettes keep their own appearance. */
enum class AppAppearance {
    System, Paper, Coal, Matcha;

    fun resolve(systemDark: Boolean): AppAppearance = when (this) {
        System -> if (systemDark) Coal else Paper
        else -> this
    }

    fun colors(systemDark: Boolean = false): AppColors = when (resolve(systemDark)) {
        Coal -> DarkColors
        Matcha -> MatchaColors
        else -> LightColors
    }
}

/** Quiet, warm surfaces. Green means recorded; the warm accent marks time, never a verdict on food. */
@Immutable
class AppColors(
    val background: Color,
    val surface: Color,
    val summary: Color,
    val foreground: Color,
    val secondary: Color,
    val tertiary: Color,
    val border: Color,
    val pressed: Color,
    val error: Color,
    val confirm: Color,
    /** Food that is recorded (counted): the foreground with a touch of green. */
    val recorded: Color,
    /** The time a food was recorded: the one warm colour of the diary. */
    val time: Color,
    /**
     * A muted raspberry accent, reserved for future use.
     */
    val cherry: Color,
    /**
     * The calendar of the history: a day with nothing, and the two ends of the fill of a day with food (the least and the goal
     * or more). The same green as recorded food, so that more food is more of it; in between the colours are mixed evenly.
     */
    val heatEmpty: Color,
    val heatLow: Color,
    val heatHigh: Color,
    val isDark: Boolean,
)

val LightColors = AppColors(
    background = Color(0xFFF7F5F0),
    surface = Color(0xFFFDFCFA),
    summary = Color(0xFFF7F5F0),
    foreground = Color(0xFF252823),
    secondary = Color(0xFF666B61),
    tertiary = Color(0xFF6D7267),
    border = Color(0xFFDDDCD3),
    pressed = Color(0xFFE8ECE1),
    error = Color(0xFFC62828),
    confirm = Color(0xFF365C43),
    recorded = Color(0xFF365C43),
    time = Color(0xFF9B4D22),
    cherry = Color(0xFF913949),
    heatEmpty = Color(0xFFECE9E0),
    heatLow = Color(0xFFCDDDC6),
    heatHigh = Color(0xFF365C43),
    isDark = false,
)

/** Coal: matte charcoal, warm white, sage and amber. */
val DarkColors = AppColors(
    background = Color(0xFF171B18),
    surface = Color(0xFF202720),
    summary = Color(0xFF202720),
    foreground = Color(0xFFE7E9E1),
    secondary = Color(0xFFA8B0A3),
    tertiary = Color(0xFF97A18F),
    border = Color(0xFF343D34),
    pressed = Color(0xFF2A342B),
    error = Color(0xFFF3A19B),
    confirm = Color(0xFFB7D2B6),
    recorded = Color(0xFFB7D2B6),
    time = Color(0xFFE1A76F),
    cherry = Color(0xFFD9818C),
    heatEmpty = Color(0xFF202720),
    heatLow = Color(0xFF294A34),
    heatHigh = Color(0xFFB7D2B6),
    isDark = true,
)

/** Matcha: the cream and green of the app icon, with a pistachio summary and terracotta timestamps. */
val MatchaColors = AppColors(
    background = Color(0xFFEDF1E2),
    surface = Color(0xFFF7F8EF),
    summary = Color(0xFFD9E4C0),
    foreground = Color(0xFF263725),
    secondary = Color(0xFF57654F),
    tertiary = Color(0xFF626F57),
    border = Color(0xFFCBD2BB),
    pressed = Color(0xFFD9E4C0),
    error = Color(0xFFAE3E33),
    confirm = Color(0xFF45613D),
    recorded = Color(0xFF45613D),
    time = Color(0xFF984C29),
    cherry = Color(0xFF873D4A),
    heatEmpty = Color(0xFFDFE5D3),
    heatLow = Color(0xFFBFCEA9),
    heatHigh = Color(0xFF45613D),
    isDark = false,
)

/**
 * Inter (SIL Open Font License, see docs/licenses.md): the quiet grotesque the chat apps use, with Cyrillic. It is the only
 * face of the app. "Mono" styles are Inter with tabular figures: every digit is as wide as the next, so columns of numbers
 * line up and a changing total never jitters. A real monospace stays only where text is code (the journal, the agent's prompt).
 */
val Inter = FontFamily(Font(R.font.inter_regular, FontWeight.Normal))

private const val TABULAR = "tnum"
@Immutable
class AppType(
    val body: TextStyle,
    val secondary: TextStyle,
    val caption: TextStyle,
    val mono: TextStyle,
    val monoSecondary: TextStyle,
)

private fun appType(colors: AppColors, font: FontFamily = Inter) = AppType(
    body = TextStyle(fontFamily = font, fontSize = 16.sp, lineHeight = 22.sp, color = colors.foreground),
    secondary = TextStyle(fontFamily = font, fontSize = 14.sp, lineHeight = 20.sp, color = colors.secondary),
    caption = TextStyle(fontFamily = font, fontSize = 12.sp, lineHeight = 16.sp, color = colors.secondary),
    mono = TextStyle(fontFamily = font, fontSize = 14.sp, lineHeight = 20.sp, color = colors.foreground,
        fontWeight = FontWeight.Normal, fontFeatureSettings = TABULAR),
    monoSecondary = TextStyle(fontFamily = font, fontSize = 14.sp, lineHeight = 20.sp, color = colors.secondary,
        fontFeatureSettings = TABULAR),
)

private val LocalColors = staticCompositionLocalOf { LightColors }
private val LocalType = staticCompositionLocalOf { appType(LightColors) }
private val LocalAppearance = staticCompositionLocalOf { AppAppearance.Paper }

object AppTheme {
    val appearance: AppAppearance
        @Composable @ReadOnlyComposable get() = LocalAppearance.current
    val colors: AppColors
        @Composable @ReadOnlyComposable get() = LocalColors.current
    val type: AppType
        @Composable @ReadOnlyComposable get() = LocalType.current
}

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    font: FontFamily = Inter,
    appearance: AppAppearance = AppAppearance.System,
    content: @Composable () -> Unit,
) {
    val resolved = appearance.resolve(darkTheme)
    val c = resolved.colors()
    val base = if (c.isDark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = c.recorded, onPrimary = c.background, primaryContainer = c.pressed, onPrimaryContainer = c.foreground,
        secondary = c.time, onSecondary = c.background, secondaryContainer = c.summary, onSecondaryContainer = c.foreground,
        tertiary = c.cherry, onTertiary = c.background, tertiaryContainer = c.pressed, onTertiaryContainer = c.foreground,
        background = c.background, onBackground = c.foreground,
        surface = c.surface, onSurface = c.foreground, surfaceVariant = c.pressed, onSurfaceVariant = c.secondary,
        surfaceDim = c.background, surfaceBright = c.surface, surfaceContainerLowest = c.background,
        surfaceContainerLow = c.surface, surfaceContainer = c.surface, surfaceContainerHigh = c.surface, surfaceContainerHighest = c.pressed,
        surfaceTint = Color.Transparent, outline = c.border, outlineVariant = c.border, error = c.error, onError = c.background,
        errorContainer = c.surface, onErrorContainer = c.error, inverseSurface = c.foreground, inverseOnSurface = c.background,
        inversePrimary = c.background,
    )
    CompositionLocalProvider(LocalColors provides c, LocalType provides appType(c, font), LocalAppearance provides resolved) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
