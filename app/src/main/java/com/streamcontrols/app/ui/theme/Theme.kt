package com.streamcontrols.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBC9CF4), onPrimary = Color(0xFF100D19),
    primaryContainer = Color(0xFF3D305E), onPrimaryContainer = Color(0xFFDEC6FF),
    background = Color(0xFF100E15), onBackground = Color(0xFFF3F0F9),
    surface = Color(0xFF1B1A24), onSurface = Color(0xFFF3F0F9),
    surfaceVariant = Color(0xFF25232F), onSurfaceVariant = Color(0xFFC6C2D4),
    outline = Color(0xFF464453), outlineVariant = Color(0xFF363442)
)
private val LightColors = lightColorScheme(
    primary = Color(0xFF6940A0), onPrimary = Color.White,
    primaryContainer = Color(0xFFEBDCFF), onPrimaryContainer = Color(0xFF260A4B),
    background = Color(0xFFF0EDF3), onBackground = Color(0xFF201D27),
    surface = Color(0xFFEAE6EF), onSurface = Color(0xFF201D27),
    surfaceVariant = Color(0xFFDDD8E4), onSurfaceVariant = Color(0xFF4B4653),
    surfaceDim = Color(0xFFD6D1DC), surfaceBright = Color(0xFFF3F0F6),
    surfaceContainerLowest = Color(0xFFF4F1F6), surfaceContainerLow = Color(0xFFF0EDF3),
    surfaceContainer = Color(0xFFEAE6EF), surfaceContainerHigh = Color(0xFFE3DFE9),
    surfaceContainerHighest = Color(0xFFDCD8E3)
)

@Composable
fun StreamControlsTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, typography = Typography, content = content)
}
