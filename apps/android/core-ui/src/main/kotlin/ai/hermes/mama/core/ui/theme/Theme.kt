package ai.hermes.mama.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Tema M3 de "Hermes para mamá" (tokens de `design/README.md`).
 *
 * - Claro por defecto, respeta el tema del sistema (`isSystemInDarkTheme`).
 * - `dynamicColor` queda **apagado**: la paleta está fijada por el diseño y su
 *   contraste verificado (ContrastTest); el color dinámico de Material You se
 *   acepta sólo si una tarea futura lo activa a propósito.
 */
@Composable
fun MamaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            darkTheme -> MamaDarkColorScheme
            else -> MamaLightColorScheme
        }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MamaTypography,
        shapes = MamaShapes,
        content = content,
    )
}
