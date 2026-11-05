package org.madmail.mobile

import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

// The logo's red identifies actions; warm neutral surfaces keep data crisp in both appearances.
// Every container role is set so no Material baseline violet leaks through.
internal fun madmailColors(dark: Boolean) = if (dark) darkColorScheme(
    primary = Color(0xFFFF6A5F), onPrimary = Color(0xFF450002),
    error = Color(0xFFFF6A5F), onError = Color(0xFF450002), errorContainer = Color(0xFF73080A), onErrorContainer = Color(0xFFFFDAD5),
    primaryContainer = Color(0xFF73080A), onPrimaryContainer = Color(0xFFFFDAD5),
    secondary = Color(0xFFE7BDB7), onSecondary = Color(0xFF442925),
    secondaryContainer = Color(0xFF3A2E2C), onSecondaryContainer = Color(0xFFF5DDD9),
    tertiary = Color(0xFFE3C38C), onTertiary = Color(0xFF3F2E04),
    tertiaryContainer = Color(0xFF574419), onTertiaryContainer = Color(0xFFFFDEA6),
    background = Color(0xFF1E1B1A), surface = Color(0xFF1E1B1A),
    onBackground = Color(0xFFF5EFEE), onSurface = Color(0xFFF5EFEE),
    surfaceVariant = Color(0xFF3D3735), onSurfaceVariant = Color(0xFFC9C0BE),
    surfaceContainerLowest = Color(0xFF0F0D0D), surfaceContainerLow = Color(0xFF1C1918),
    surfaceContainer = Color(0xFF201D1C), surfaceContainerHigh = Color(0xFF2B2726),
    surfaceContainerHighest = Color(0xFF363130),
    outline = Color(0xFFA0908D), outlineVariant = Color(0xFF3D3736),
) else lightColorScheme(
    primary = Color(0xFFC41011), onPrimary = Color.White,
    error = Color(0xFFC41011), onError = Color.White, errorContainer = Color(0xFFFFDAD5), onErrorContainer = Color(0xFF410001),
    primaryContainer = Color(0xFFFFDAD5), onPrimaryContainer = Color(0xFF410001),
    secondary = Color(0xFF775652), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF5E6E3), onSecondaryContainer = Color(0xFF2C1512),
    tertiary = Color(0xFF705C2E), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFCDFA6), onTertiaryContainer = Color(0xFF261A00),
    background = Color(0xFFF3F0EF), surface = Color(0xFFF3F0EF),
    onBackground = Color(0xFF1E1A19), onSurface = Color(0xFF1E1A19),
    surfaceVariant = Color(0xFFF0E8E6), onSurfaceVariant = Color(0xFF5F5856),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF8F4F3),
    surfaceContainer = Color(0xFFF3EEED), surfaceContainerHigh = Color(0xFFEDE8E7),
    surfaceContainerHighest = Color(0xFFE7E2E1),
    outline = Color(0xFF857370), outlineVariant = Color(0xFFE6DFDD),
)

// Persian text is set in Vazirmatn; one variable font file covers every weight the UI uses.
private val vazirmatn = FontFamily(listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map {
    Font(R.font.vazirmatn, it, variationSettings = FontVariation.Settings(FontVariation.weight(it.weight)))
})

internal fun madmailTypography(language: String): Typography {
    val base = Typography()
    if (language != "fa") return base
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily = vazirmatn), displayMedium = base.displayMedium.copy(fontFamily = vazirmatn),
        displaySmall = base.displaySmall.copy(fontFamily = vazirmatn), headlineLarge = base.headlineLarge.copy(fontFamily = vazirmatn),
        headlineMedium = base.headlineMedium.copy(fontFamily = vazirmatn), headlineSmall = base.headlineSmall.copy(fontFamily = vazirmatn),
        titleLarge = base.titleLarge.copy(fontFamily = vazirmatn), titleMedium = base.titleMedium.copy(fontFamily = vazirmatn),
        titleSmall = base.titleSmall.copy(fontFamily = vazirmatn), bodyLarge = base.bodyLarge.copy(fontFamily = vazirmatn),
        bodyMedium = base.bodyMedium.copy(fontFamily = vazirmatn), bodySmall = base.bodySmall.copy(fontFamily = vazirmatn),
        labelLarge = base.labelLarge.copy(fontFamily = vazirmatn), labelMedium = base.labelMedium.copy(fontFamily = vazirmatn),
        labelSmall = base.labelSmall.copy(fontFamily = vazirmatn),
    )
}
