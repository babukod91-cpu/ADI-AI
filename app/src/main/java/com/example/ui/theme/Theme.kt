package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    primary = Color(0xFFFF7AC8), secondary = Color(0xFF8E7CFF), tertiary = Color(0xFF4FD8FF),
    background = Color(0xFF0B0716), surface = Color(0xFF16102A), surfaceVariant = Color(0xFF241A40),
    onBackground = Color(0xFFF3EEFF), onSurface = Color(0xFFF3EEFF), onPrimary = Color(0xFF2A0A1E)
)
private val Light = lightColorScheme(
    primary = Color(0xFFC2185B), secondary = Color(0xFF5E4BD8), tertiary = Color(0xFF0097A7),
    background = Color(0xFFFFF7FB), surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFF1E6F7),
    onBackground = Color(0xFF1B1030), onSurface = Color(0xFF1B1030), onPrimary = Color.White
)

/** themeMode: 0 = follow system, 1 = light, 2 = dark. */
@Composable
fun AdiTheme(themeMode: Int = 0, content: @Composable () -> Unit) {
    val dark = when (themeMode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
