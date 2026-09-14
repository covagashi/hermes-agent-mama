package ai.hermes.mama.core.ui.components

import ai.hermes.mama.core.ui.R
import ai.hermes.mama.core.ui.theme.MamaDimens
import ai.hermes.mama.core.ui.theme.MamaTheme
import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Chip de actividad (mockup Chat: "Navegando por Tienda Ejemplo…"): aviso no
 * interactivo que muestra qué está haciendo Hermes mientras trabaja.
 *
 * - [progress] `null` → indicador indeterminado (uso normal, C4); con valor
 *   0f–1f → determinado (capturas de Roborazzi deterministas).
 * - No es pulsable: no necesita objetivo táctil, pero mantiene ≥ 56 dp de alto
 *   como el resto del sistema. `liveRegion` hace que TalkBack anuncie el cambio
 *   de texto sin mover el foco.
 */
@Composable
fun ActivityChip(
    text: String,
    modifier: Modifier = Modifier,
    progress: Float? = null,
) {
    Surface(
        // Un solo nodo accesible: TalkBack lee "texto" (y el progreso si lo hay)
        // una sola vez, sin foco en el icono.
        modifier = modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(MamaDimens.CardCorner),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier =
                Modifier
                    .heightIn(min = MamaDimens.MinTouchTarget)
                    .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val indicatorModifier = Modifier.size(20.dp)
            if (progress == null) {
                CircularProgressIndicator(
                    modifier = indicatorModifier,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primaryContainer,
                    strokeWidth = 3.dp,
                )
            } else {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = indicatorModifier,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primaryContainer,
                    strokeWidth = 3.dp,
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Preview(name = "Chip claro", showBackground = true)
@Preview(name = "Chip oscuro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ActivityChipPreview() {
    MamaTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ActivityChip(text = stringResource(R.string.design_example_chip_web))
                ActivityChip(text = stringResource(R.string.design_chip_working))
            }
        }
    }
}
