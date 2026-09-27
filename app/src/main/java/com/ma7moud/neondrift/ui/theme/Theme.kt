package com.ma7moud.neondrift.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val NeonColors = darkColorScheme(
    primary = Color(0xFF54F7FF),
    onPrimary = Color(0xFF001E22),
    secondary = Color(0xFFB84DFF),
    onSecondary = Color.White,
    tertiary = Color(0xFFFF4DA6),
    background = Color(0xFF030712),
    surface = Color(0xFF0B1220),
    onBackground = Color(0xFFEAFBFF),
    onSurface = Color(0xFFEAFBFF),
)

@Composable
fun NeonDriftTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NeonColors,
        content = content,
    )
}
