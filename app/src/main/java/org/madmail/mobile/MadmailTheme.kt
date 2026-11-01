package org.madmail.mobile

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Violet identifies actions; neutral surfaces keep data crisp in both appearances.
internal fun madmailColors(dark: Boolean) = if (dark) darkColorScheme(
    primary = Color(0xFFC5ADFF), onPrimary = Color(0xFF291057),
    primaryContainer = Color(0xFF3D2466), onPrimaryContainer = Color(0xFFEBDDFF),
    background = Color(0xFF141218), surface = Color(0xFF141218),
    onBackground = Color(0xFFF4F0F8), onSurface = Color(0xFFF4F0F8),
    onSurfaceVariant = Color(0xFFC4BDCE), outlineVariant = Color(0xFF3E3947),
) else lightColorScheme(
    primary = Color(0xFF6636CF), onPrimary = Color.White,
    primaryContainer = Color(0xFFEBDDFF), onPrimaryContainer = Color(0xFF35146D),
    background = Color(0xFFFCFAFF), surface = Color(0xFFFCFAFF),
    onBackground = Color(0xFF21192E), onSurface = Color(0xFF21192E),
    onSurfaceVariant = Color(0xFF62586F), outlineVariant = Color(0xFFE1D9EA),
)
