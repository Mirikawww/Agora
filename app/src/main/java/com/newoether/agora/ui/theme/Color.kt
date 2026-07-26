package com.newoether.agora.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeExpressive
import com.materialkolor.scheme.SchemeNeutral
import com.materialkolor.scheme.SchemeTonalSpot
import com.materialkolor.scheme.SchemeVibrant
import com.materialkolor.hct.Hct

enum class SchemeStyle { TONAL_SPOT, EXPRESSIVE, VIBRANT, NEUTRAL }

enum class ColorSchemePreset {
    MIDNIGHT, NORDIC, FOREST, SUNSET, ROSE, LAVENDER, SLATE, OCEAN,
    /** Pure black / white / gray only — no Material chroma. */
    MONOCHROME,
}

private val seedColors = mapOf(
    ColorSchemePreset.MIDNIGHT to 0xFF1A237E,
    ColorSchemePreset.NORDIC   to 0xFF546E7A,
    ColorSchemePreset.FOREST   to 0xFF2E7D32,
    ColorSchemePreset.SUNSET   to 0xFFE65100,
    ColorSchemePreset.ROSE     to 0xFFAD1457,
    ColorSchemePreset.LAVENDER to 0xFF7B1FA2,
    ColorSchemePreset.SLATE    to 0xFF455A64,
    ColorSchemePreset.OCEAN    to 0xFF0277BD,
    // Seed unused for monochrome (hand-built grayscale palette).
    ColorSchemePreset.MONOCHROME to 0xFF808080,
)

fun colorSchemeForPreset(
    preset: ColorSchemePreset,
    style: SchemeStyle = SchemeStyle.TONAL_SPOT,
    isDark: Boolean = false
): ColorScheme {
    if (preset == ColorSchemePreset.MONOCHROME) {
        return monochromeColorScheme(isDark)
    }
    val seedArgb = seedColors[preset]!!.toInt()
    val hct = Hct.fromInt(seedArgb)
    val scheme: DynamicScheme = when (style) {
        SchemeStyle.TONAL_SPOT -> SchemeTonalSpot(hct, isDark, 0.0)
        SchemeStyle.EXPRESSIVE -> SchemeExpressive(hct, isDark, 0.0)
        SchemeStyle.VIBRANT   -> SchemeVibrant(hct, isDark, 0.0)
        SchemeStyle.NEUTRAL   -> SchemeNeutral(hct, isDark, 0.0)
    }
    return scheme.toColorScheme()
}

/**
 * Strict grayscale Material 3 palette: black / white / gray only.
 * Primary accents are near-black (light) or near-white (dark) so the whole UI
 * stays monochrome even for selected chips, FABs, and links.
 */
fun monochromeColorScheme(isDark: Boolean): ColorScheme {
    // Pure neutrals — no blue tint, no Material error red.
    val black = Color(0xFF000000)
    val white = Color(0xFFFFFFFF)
    val g04 = Color(0xFF0A0A0A)
    val g08 = Color(0xFF141414)
    val g12 = Color(0xFF1E1E1E)
    val g16 = Color(0xFF292929)
    val g22 = Color(0xFF383838)
    val g30 = Color(0xFF4D4D4D)
    val g40 = Color(0xFF666666)
    val g50 = Color(0xFF808080)
    val g60 = Color(0xFF999999)
    val g70 = Color(0xFFB3B3B3)
    val g80 = Color(0xFFCCCCCC)
    val g90 = Color(0xFFE6E6E6)
    val g94 = Color(0xFFF0F0F0)
    val g96 = Color(0xFFF5F5F5)
    val g98 = Color(0xFFFAFAFA)

    return if (isDark) {
        darkColorScheme(
            primary = g90,
            onPrimary = black,
            primaryContainer = g22,
            onPrimaryContainer = g90,
            secondary = g70,
            onSecondary = black,
            secondaryContainer = g16,
            onSecondaryContainer = g90,
            tertiary = g60,
            onTertiary = black,
            tertiaryContainer = g16,
            onTertiaryContainer = g80,
            // Grayscale "error" only for monochrome non-destructive surfaces.
            // Destructive actions that must stay red (e.g. delete-all) use an explicit Color.
            error = g80,
            onError = black,
            errorContainer = g22,
            onErrorContainer = g90,
            background = black,
            onBackground = g90,
            surface = g04,
            onSurface = g90,
            surfaceVariant = g12,
            onSurfaceVariant = g70,
            // Explicit surface containers: DropdownMenu / menus default to these tokens.
            // Leaving them unset falls back to Material purple defaults.
            surfaceBright = g16,
            surfaceDim = black,
            surfaceContainer = g12,
            surfaceContainerHigh = g16,
            surfaceContainerHighest = g22,
            surfaceContainerLow = g08,
            surfaceContainerLowest = black,
            outline = g40,
            outlineVariant = g22,
            scrim = black,
            inverseSurface = g90,
            inverseOnSurface = g08,
            inversePrimary = g12,
            surfaceTint = g50,
        )
    } else {
        lightColorScheme(
            primary = g12,
            onPrimary = white,
            primaryContainer = g90,
            onPrimaryContainer = g08,
            secondary = g30,
            onSecondary = white,
            secondaryContainer = g94,
            onSecondaryContainer = g12,
            tertiary = g40,
            onTertiary = white,
            tertiaryContainer = g94,
            onTertiaryContainer = g16,
            error = g22,
            onError = white,
            errorContainer = g90,
            onErrorContainer = g08,
            background = white,
            onBackground = g08,
            surface = g98,
            onSurface = g08,
            surfaceVariant = g94,
            onSurfaceVariant = g40,
            surfaceBright = white,
            surfaceDim = g90,
            surfaceContainer = g96,
            surfaceContainerHigh = g94,
            surfaceContainerHighest = g90,
            surfaceContainerLow = g98,
            surfaceContainerLowest = white,
            outline = g60,
            outlineVariant = g80,
            scrim = black,
            inverseSurface = g16,
            inverseOnSurface = g94,
            inversePrimary = g90,
            surfaceTint = g50,
        )
    }
}

/**
 * Map Material Kolor [DynamicScheme] into a full Compose [ColorScheme].
 *
 * Must include the surface-container ladder (`surfaceContainer*`, `surfaceBright`,
 * `surfaceDim`, `surfaceTint`, scrim/inverse). Menus, dialogs, and many M3
 * components read those tokens; omitting them leaves Compose's purple defaults.
 */
private fun DynamicScheme.toColorScheme(): ColorScheme {
    val c = { argb: Int -> Color(argb) }
    return if (isDark) darkColorScheme(
        primary = c(primary), onPrimary = c(onPrimary),
        primaryContainer = c(primaryContainer), onPrimaryContainer = c(onPrimaryContainer),
        secondary = c(secondary), onSecondary = c(onSecondary),
        secondaryContainer = c(secondaryContainer), onSecondaryContainer = c(onSecondaryContainer),
        tertiary = c(tertiary), onTertiary = c(onTertiary),
        tertiaryContainer = c(tertiaryContainer), onTertiaryContainer = c(onTertiaryContainer),
        error = c(error), onError = c(onError),
        errorContainer = c(errorContainer), onErrorContainer = c(onErrorContainer),
        background = c(background), onBackground = c(onBackground),
        surface = c(surface), onSurface = c(onSurface),
        surfaceVariant = c(surfaceVariant), onSurfaceVariant = c(onSurfaceVariant),
        surfaceBright = c(surfaceBright),
        surfaceDim = c(surfaceDim),
        surfaceContainer = c(surfaceContainer),
        surfaceContainerHigh = c(surfaceContainerHigh),
        surfaceContainerHighest = c(surfaceContainerHighest),
        surfaceContainerLow = c(surfaceContainerLow),
        surfaceContainerLowest = c(surfaceContainerLowest),
        outline = c(outline), outlineVariant = c(outlineVariant),
        scrim = c(scrim),
        inverseSurface = c(inverseSurface),
        inverseOnSurface = c(inverseOnSurface),
        inversePrimary = c(inversePrimary),
        surfaceTint = c(surfaceTint),
    ) else lightColorScheme(
        primary = c(primary), onPrimary = c(onPrimary),
        primaryContainer = c(primaryContainer), onPrimaryContainer = c(onPrimaryContainer),
        secondary = c(secondary), onSecondary = c(onSecondary),
        secondaryContainer = c(secondaryContainer), onSecondaryContainer = c(onSecondaryContainer),
        tertiary = c(tertiary), onTertiary = c(onTertiary),
        tertiaryContainer = c(tertiaryContainer), onTertiaryContainer = c(onTertiaryContainer),
        error = c(error), onError = c(onError),
        errorContainer = c(errorContainer), onErrorContainer = c(onErrorContainer),
        background = c(background), onBackground = c(onBackground),
        surface = c(surface), onSurface = c(onSurface),
        surfaceVariant = c(surfaceVariant), onSurfaceVariant = c(onSurfaceVariant),
        surfaceBright = c(surfaceBright),
        surfaceDim = c(surfaceDim),
        surfaceContainer = c(surfaceContainer),
        surfaceContainerHigh = c(surfaceContainerHigh),
        surfaceContainerHighest = c(surfaceContainerHighest),
        surfaceContainerLow = c(surfaceContainerLow),
        surfaceContainerLowest = c(surfaceContainerLowest),
        outline = c(outline), outlineVariant = c(outlineVariant),
        scrim = c(scrim),
        inverseSurface = c(inverseSurface),
        inverseOnSurface = c(inverseOnSurface),
        inversePrimary = c(inversePrimary),
        surfaceTint = c(surfaceTint),
    )
}
