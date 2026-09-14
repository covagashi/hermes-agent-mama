package ai.hermes.mama.core.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Paleta "Hermes para mamá". Los tokens con nombre son los de design/README.md
// (fuente de verdad); los slots restantes de M3 se derivan en la misma gama
// cálida. Pares texto/fondo usados por los componentes verificados ≥ 4.5:1 en
// ContrastTest (salvo el botón Parar: texto grande en negrita → ≥ 3:1 WCAG).
internal object MamaColors {
    // --- claro (tokens exactos de design/README.md) ---
    val LightBackground = Color(0xFFFBF8F3)
    val LightSurfaceVariant = Color(0xFFF3EEE5)
    val LightOnBackground = Color(0xFF1F1B16)
    val LightOnSurfaceVariant = Color(0xFF5B554C)
    val LightOutline = Color(0xFFE6DED2)
    val LightPrimary = Color(0xFF1B7A5F)
    val LightPrimaryContainer = Color(0xFFDDEFE7)
    val LightOnPrimaryContainer = Color(0xFF0F4D3B)
    val LightTertiary = Color(0xFFB5541C)
    val LightTertiaryContainer = Color(0xFFFBE9DE)
    val LightSurface = Color(0xFFFFFFFF)

    // --- oscuro: derivada de la misma paleta (design/README.md) ---
    val DarkBackground = Color(0xFF1A1714)
    val DarkSurface = Color(0xFF242019)
    val DarkPrimary = Color(0xFF6FCBAA)
}

/** Paleta clara (tema por defecto). Tokens: design/README.md. */
val MamaLightColorScheme =
    lightColorScheme(
        primary = MamaColors.LightPrimary,
        onPrimary = MamaColors.LightSurface,
        primaryContainer = MamaColors.LightPrimaryContainer,
        onPrimaryContainer = MamaColors.LightOnPrimaryContainer,
        secondary = MamaColors.LightOnSurfaceVariant,
        onSecondary = MamaColors.LightSurface,
        secondaryContainer = Color(0xFFEDE6DA),
        onSecondaryContainer = Color(0xFF3E382E),
        tertiary = MamaColors.LightTertiary,
        onTertiary = MamaColors.LightSurface,
        tertiaryContainer = MamaColors.LightTertiaryContainer,
        // Los mockups pintan el texto/icono de "Parar" con tertiary sobre
        // tertiaryContainer (4.2:1 — texto grande en negrita, WCAG ≥ 3:1).
        onTertiaryContainer = MamaColors.LightTertiary,
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFF9DEDC),
        onErrorContainer = Color(0xFF410E0B),
        background = MamaColors.LightBackground,
        onBackground = MamaColors.LightOnBackground,
        surface = MamaColors.LightSurface,
        onSurface = MamaColors.LightOnBackground,
        surfaceVariant = MamaColors.LightSurfaceVariant,
        onSurfaceVariant = MamaColors.LightOnSurfaceVariant,
        outline = MamaColors.LightOutline,
        outlineVariant = Color(0xFFF0EAE0),
        scrim = MamaColors.LightOnBackground,
        inverseSurface = Color(0xFF332F28),
        inverseOnSurface = Color(0xFFF5EFE7),
        inversePrimary = MamaColors.DarkPrimary,
        surfaceTint = MamaColors.LightPrimary,
    )

/**
 * Paleta oscura. El README sólo fija fondo `#1A1714`, superficie `#242019` y
 * acento `#6FCBAA`; el resto se deriva en la misma gama cálida manteniendo
 * contraste AA (verificado en ContrastTest).
 */
val MamaDarkColorScheme =
    darkColorScheme(
        primary = MamaColors.DarkPrimary,
        onPrimary = MamaColors.LightOnPrimaryContainer,
        primaryContainer = Color(0xFF1E5A45),
        onPrimaryContainer = Color(0xFFBCE8D5),
        secondary = Color(0xFFC8BCAB),
        onSecondary = Color(0xFF2C261C),
        secondaryContainer = Color(0xFF4A4335),
        onSecondaryContainer = Color(0xFFEAE1D3),
        tertiary = Color(0xFFF2A97C),
        onTertiary = Color(0xFF4A2508),
        tertiaryContainer = Color(0xFF6B3B1E),
        onTertiaryContainer = Color(0xFFF2A97C),
        error = Color(0xFFF2B8B5),
        onError = Color(0xFF601410),
        errorContainer = Color(0xFF8C1D18),
        onErrorContainer = Color(0xFFF9DEDC),
        background = MamaColors.DarkBackground,
        onBackground = Color(0xFFEDE6DA),
        surface = MamaColors.DarkSurface,
        onSurface = Color(0xFFEDE6DA),
        surfaceVariant = Color(0xFF383127),
        onSurfaceVariant = Color(0xFFC8BCAB),
        outline = Color(0xFF6B6353),
        outlineVariant = Color(0xFF4A4335),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFFEDE6DA),
        inverseOnSurface = Color(0xFF2C261C),
        inversePrimary = MamaColors.LightPrimary,
        surfaceTint = MamaColors.DarkPrimary,
    )
