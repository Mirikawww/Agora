package com.newoether.agora.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import java.io.File

enum class ThemeMode { LIGHT, DARK, FOLLOW_DEVICE }

/** True when the active color scheme is the hand-built monochrome palette. */
val LocalIsMonochrome = compositionLocalOf { false }

@Composable
fun AgoraTheme(
    themeMode: ThemeMode = ThemeMode.FOLLOW_DEVICE,
    colorSchemePreset: ColorSchemePreset = ColorSchemePreset.MIDNIGHT,
    schemeStyle: SchemeStyle = SchemeStyle.TONAL_SPOT,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.FOLLOW_DEVICE -> systemDark
    }

    val colorScheme = when {
        // Dynamic wallpaper colors take priority when enabled. Monochrome is just another
        // static preset — it must not trap the dynamic-color switch (user can leave Mono
        // selected and still toggle dynamic on/off).
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        colorSchemePreset == ColorSchemePreset.MONOCHROME -> {
            remember(darkTheme) { monochromeColorScheme(darkTheme) }
        }
        else -> remember(colorSchemePreset, schemeStyle, darkTheme) {
            colorSchemeForPreset(colorSchemePreset, schemeStyle, darkTheme)
        }
    }

    // UI text uses the platform font; only code/terminal surfaces keep the bundled
    // monospace family (see MonoFamily in Type.kt).
    val typography = Typography
    // Only true when the hand-built mono palette is actually applied (not when dynamic is on).
    val isMonochrome = colorSchemePreset == ColorSchemePreset.MONOCHROME &&
        !(dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)

    CompositionLocalProvider(LocalIsMonochrome provides isMonochrome) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content
        )
    }
}
