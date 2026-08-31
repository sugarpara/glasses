package com.example.glasses.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val GlassesColorScheme = lightColorScheme(
    primary = AppBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8F0FF),
    onPrimaryContainer = Color(0xFF002B6F),
    secondary = AppGreen,
    error = AppRed,
    background = Color.White,
    onBackground = AppText,
    surface = Color.White,
    onSurface = AppText,
    surfaceVariant = AppSurface,
    onSurfaceVariant = AppMutedText,
    outline = AppOutline,
)

@Composable
fun GlassesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GlassesColorScheme,
        typography = Typography,
        content = content,
    )
}
