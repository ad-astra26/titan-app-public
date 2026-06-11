package tech.iamtitan.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Titan's palette — deep cosmic ink + a cyan/violet aurora. Sovereign, calm, premium.
val TitanInk = Color(0xFF0B0E14)
val TitanSurface = Color(0xFF141A24)
val TitanSurfaceHi = Color(0xFF1C2433)
val TitanCyan = Color(0xFF4DD0E1)
val TitanViolet = Color(0xFF9C7BFF)
val TitanText = Color(0xFFE6EAF2)
val TitanMuted = Color(0xFF8A93A6)
val TitanGood = Color(0xFF5BD9A6)
val TitanWarn = Color(0xFFE7B450)

private val TitanColors = darkColorScheme(
    primary = TitanCyan,
    onPrimary = TitanInk,
    secondary = TitanViolet,
    onSecondary = TitanInk,
    background = TitanInk,
    onBackground = TitanText,
    surface = TitanSurface,
    onSurface = TitanText,
    surfaceVariant = TitanSurfaceHi,
    onSurfaceVariant = TitanMuted,
    error = TitanWarn,
)

/** The auroral background wash used behind hero screens. */
val TitanAurora: Brush
    get() = Brush.verticalGradient(
        0.0f to TitanInk,
        0.55f to Color(0xFF101725),
        1.0f to Color(0xFF0E1422),
    )

@Composable
fun TitanTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = TitanColors, content = content)
