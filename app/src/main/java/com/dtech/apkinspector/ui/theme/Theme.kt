package com.dtech.apkinspector.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DTechColorScheme = darkColorScheme(
    primary = DTechBlueLight, // Lighter for dark mode visibility
    onPrimary = Black,
    primaryContainer = DTechBlue,
    onPrimaryContainer = White,
    secondary = DTechPurple,
    onSecondary = White,
    background = Black,
    onBackground = White,
    surface = DarkGray,
    onSurface = White,
    error = RedError
)

@Composable
fun DTechTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DTechColorScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}
