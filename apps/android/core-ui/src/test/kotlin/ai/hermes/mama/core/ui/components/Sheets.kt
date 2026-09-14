package ai.hermes.mama.core.ui.components

import ai.hermes.mama.core.ui.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

// Láminas de ejemplo para las capturas de Roborazzi y el test de accesibilidad.
// Mismo contenido en todas las variantes para comparar claro/oscuro y tamaños
// de fuente. Datos ficticios (repo público): "Tienda Ejemplo", "usuario".

@Composable
internal fun BigButtonSheet() {
    BigButton(
        text = stringResource(R.string.design_example_new_chat),
        onClick = {},
        icon = Icons.Filled.Add,
        iconContentDescription = null,
    )
    BigButton(
        text = stringResource(R.string.design_example_yes),
        onClick = {},
        variant = MamaButtonVariant.Outline,
    )
    BigButton(
        text = stringResource(R.string.design_stop),
        onClick = {},
        variant = MamaButtonVariant.Danger,
    )
    BigButton(
        text = stringResource(R.string.design_example_no),
        onClick = {},
        variant = MamaButtonVariant.Neutral,
        enabled = false,
    )
}

@Composable
internal fun ChatBubbleSheet() {
    ChatBubble(
        text = stringResource(R.string.design_example_bubble_user),
        author = ChatBubbleAuthor.User,
    )
    ChatBubble(
        text = stringResource(R.string.design_example_bubble_hermes),
        author = ChatBubbleAuthor.Hermes,
        onListenClick = {},
    )
    ChatBubble(
        text = stringResource(R.string.design_example_bubble_hermes_doc),
        author = ChatBubbleAuthor.Hermes,
    )
}

@Composable
internal fun ActivityChipSheet() {
    // progress fijo → indicador determinado y captura determinista.
    ActivityChip(
        text = stringResource(R.string.design_example_chip_web),
        progress = 0.65f,
    )
    ActivityChip(
        text = stringResource(R.string.design_chip_working),
        progress = 0.65f,
    )
}

@Composable
internal fun TopBannerSheet() {
    TopBanner()
    TopBanner(text = stringResource(R.string.design_example_banner_working))
}

@Composable
internal fun EmptyStateSheet() {
    EmptyState(
        title = stringResource(R.string.design_example_empty_title),
        description = stringResource(R.string.design_example_empty_body),
        icon = Icons.Outlined.ChatBubbleOutline,
        action = {
            BigButton(
                text = stringResource(R.string.design_example_new_chat),
                onClick = {},
            )
        },
    )
}

/** Envoltorio común: fondo background + columna centrada a ancho de mockup. */
@Composable
internal fun ComponentSheet(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = { content() },
        )
    }
}
