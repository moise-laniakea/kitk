package com.moise.laniakea.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext

private val FallbackLight = lightColorScheme(primary = Color(0xFF3B5BA9), secondary = Color(0xFF5A6BA0), tertiary = Color(0xFF8B4F8F))
private val FallbackDark = darkColorScheme(primary = Color(0xFFB2C5FF), secondary = Color(0xFFBAC3EB), tertiary = Color(0xFFE6B4E8))

/**
 * Material 3 / Material You theme.
 *  - [dynamicColor]: Monet colors from the wallpaper (Android 12+)
 *  - [artworkSeed]: when set, accent colors are derived from the current album art instead
 */
@Composable
fun MoiseTheme(
    dynamicColor: Boolean,
    artworkSeed: Color? = null,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val base: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> FallbackDark
        else -> FallbackLight
    }
    val scheme = if (artworkSeed != null) base.tintedBy(artworkSeed, darkTheme) else base
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Lightweight "dynamic from artwork" scheme: re-tints the accent roles from one seed color. */
private fun ColorScheme.tintedBy(seed: Color, dark: Boolean): ColorScheme {
    fun mix(a: Color, b: Color, t: Float) = Color(
        a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f,
    )
    val primary = if (dark) mix(seed, Color.White, 0.45f) else mix(seed, Color.Black, 0.25f)
    val container = if (dark) mix(seed, Color.Black, 0.55f) else mix(seed, Color.White, 0.8f)
    val onPrimary = if (primary.luminance() > 0.5f) Color.Black else Color.White
    val onContainer = if (container.luminance() > 0.5f) Color.Black else Color.White
    return copy(
        primary = primary, onPrimary = onPrimary,
        primaryContainer = container, onPrimaryContainer = onContainer,
        secondary = mix(primary, surface, 0.35f),
        secondaryContainer = mix(container, surface, 0.4f),
        surfaceTint = primary,
    )
}
