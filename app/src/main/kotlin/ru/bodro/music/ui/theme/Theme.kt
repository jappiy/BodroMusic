package ru.bodro.music.ui.theme

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.bodro.music.data.settings.ThemeMode
import ru.bodro.music.ui.image.CoverColors

/** Тёплый жёлто-оранжевый — акцент «волны». */
val BodroYellow = Color(0xFFFFB627)
val BodroOrange = Color(0xFFFF7A1A)
val BodroCoral = Color(0xFFFF4D6D)
val BodroViolet = Color(0xFF7B5CFF)
val NearBlack = Color(0xFF0B0B0C)

private val DarkColors = darkColorScheme(
    primary = BodroYellow,
    onPrimary = Color(0xFF1E1400),
    primaryContainer = Color(0xFF3B2A00),
    onPrimaryContainer = Color(0xFFFFDEA6),
    secondary = BodroOrange,
    onSecondary = Color(0xFF2A1000),
    tertiary = BodroViolet,
    background = NearBlack,
    onBackground = Color(0xFFF2EFEA),
    surface = NearBlack,
    onSurface = Color(0xFFF2EFEA),
    surfaceVariant = Color(0xFF1C1B1D),
    onSurfaceVariant = Color(0xFFB9B4AC),
    surfaceContainer = Color(0xFF151416),
    surfaceContainerHigh = Color(0xFF1E1D20),
    surfaceContainerHighest = Color(0xFF29282B),
    surfaceContainerLow = Color(0xFF111112),
    outline = Color(0xFF4A4740),
    outlineVariant = Color(0xFF2E2C29),
    error = Color(0xFFFF6B6B),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFB57A00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE3A8),
    onPrimaryContainer = Color(0xFF3B2A00),
    secondary = Color(0xFFD45A00),
    tertiary = BodroViolet,
    background = Color(0xFFFFFBF5),
    onBackground = Color(0xFF1C1A16),
    surface = Color(0xFFFFFBF5),
    onSurface = Color(0xFF1C1A16),
    surfaceVariant = Color(0xFFF1EADF),
    onSurfaceVariant = Color(0xFF5A544A),
    surfaceContainer = Color(0xFFF7F0E6),
    surfaceContainerHigh = Color(0xFFF1EADF),
    surfaceContainerHighest = Color(0xFFEAE2D6),
    surfaceContainerLow = Color(0xFFFCF6EE),
)

val BodroTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, lineHeight = 38.sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, lineHeight = 34.sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Огромный заголовок исполнителя на экране волны. */
val WaveTitleStyle = TextStyle(fontSize = 44.sp, lineHeight = 48.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp)

val BodroShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Цвета текущей обложки для плеера; `null` — использовать цвета темы. */
@Immutable
/** Цвета текущего трека. [glow] — цвета свечения «Моей волны» (доминантный обложки и акценты). */
data class PlayerColors(
    val background: Color,
    val accent: Color,
    val onBackground: Color,
    val glow: List<Color> = listOf(accent),
)

/** Свечение без обложки (или с выключенными цветами обложки) — нейтральное тёплое из палитры приложения. */
val NeutralGlow = listOf(Color(0xFFC0702E), BodroOrange, Color(0xFF8E3B2E))

val LocalPlayerColors = staticCompositionLocalOf<PlayerColors?> { null }

@Composable
fun BodroTheme(
    themeMode: ThemeMode = ThemeMode.System,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = scheme, typography = BodroTypography, shapes = BodroShapes, content = content)
}

/** Плавно меняющиеся цвета плеера по обложке (или из темы, если выключено). */
@Composable
fun rememberPlayerColors(cover: CoverColors?, enabled: Boolean): PlayerColors {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    val targetBackground = if (enabled && cover != null) adjustBackground(cover.background, dark) else scheme.surfaceContainerHigh
    val targetAccent = if (enabled && cover != null) adjustAccent(cover.accent, dark) else scheme.primary
    val background by animateColorAsState(targetBackground, tween(600), label = "playerBackground")
    val accent by animateColorAsState(targetAccent, tween(600), label = "playerAccent")
    val onBackground = if (background.luminance() > 0.5f) Color(0xFF15130F) else Color(0xFFF7F3EC)
    val glow = if (enabled && cover != null && cover.glow.isNotEmpty()) cover.glow else NeutralGlow
    return PlayerColors(background, accent, onBackground, glow)
}

private fun adjustBackground(color: Color, dark: Boolean): Color =
    if (dark) lerpColor(color, Color.Black, 0.35f) else lerpColor(color, Color.White, 0.55f)

private fun adjustAccent(color: Color, dark: Boolean): Color =
    if (dark && color.luminance() < 0.25f) lerpColor(color, Color.White, 0.45f) else color

fun lerpColor(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f,
)

@Composable
fun ProvidePlayerColors(colors: PlayerColors?, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalPlayerColors provides colors, content = content)
