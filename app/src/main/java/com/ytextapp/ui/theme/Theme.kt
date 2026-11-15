package com.ytextapp.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val YouTubeRed = Color(0xFFFF0000)
private val YouTubeDarkRed = Color(0xFFCC0000)

private val DarkColorScheme = darkColorScheme(
    primary = YouTubeRed,
    onPrimary = Color.White,
    primaryContainer = YouTubeDarkRed,
    secondary = Color(0xFFAAAAAA),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    onBackground = Color(0xFFE0E0E0),
    onSurface = Color(0xFFE0E0E0),
)

private val LightColorScheme = lightColorScheme(
    primary = YouTubeRed,
    onPrimary = Color.White,
    primaryContainer = YouTubeRed,
    secondary = Color(0xFF606060),
    background = Color.White,
    surface = Color(0xFFF5F5F5),
    onBackground = Color(0xFF1A1A1A),
    onSurface = Color(0xFF1A1A1A),
)

@Composable
fun YTExtTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
