package ai.hermes.mama.feature.chat.chats

import ai.hermes.mama.core.ui.components.BigButton
import ai.hermes.mama.core.ui.components.MamaButtonVariant
import ai.hermes.mama.core.ui.theme.MamaDimens
import ai.hermes.mama.feature.chat.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Diálogo de confirmación de borrado (C3: "deslizar → borrar, con
 * confirmación"). Misma familia visual que la hoja de aprobación: esquinas
 * grandes, dos botones enormes apilados — "Borrar" en tono de peligro y
 * "Conservar" neutro, que es lo que una persona mayor debe encontrar rápido.
 *
 * Fuera del mockup no hay confirmación dibujada; se usa el patrón estándar de
 * diálogo de Android pero con botones del sistema de diseño (≥ 64 dp).
 */
@Composable
fun DeleteChatDialog(
    chat: ChatRowUi,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.chats_delete_title)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier =
                modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .semantics {
                        paneTitle = title
                        liveRegion = LiveRegionMode.Polite
                    },
            shape = RoundedCornerShape(MamaDimens.SheetCorner),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            Column(
                // Scroll por si la fuente ×2 hace que no quepa el diálogo.
                modifier =
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = stringResource(R.string.chats_delete_body, chat.displayTitle()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BigButton(
                    text = stringResource(R.string.chats_delete_confirm),
                    onClick = onConfirm,
                    variant = MamaButtonVariant.Danger,
                    icon = Icons.Outlined.Delete,
                    iconContentDescription = null,
                )
                BigButton(
                    text = stringResource(R.string.chats_delete_cancel),
                    onClick = onDismiss,
                    variant = MamaButtonVariant.Neutral,
                )
            }
        }
    }
}

/**
 * Diálogo del botón "?" de la barra superior: una explicación corta de qué es
 * esta pantalla y qué se puede hacer (el mockup dibuja el icono; su contenido
 * es esta ayuda mínima).
 */
@Composable
fun ChatsHelpDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.chats_help_title)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier =
                modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .semantics {
                        paneTitle = title
                        liveRegion = LiveRegionMode.Polite
                    },
            shape = RoundedCornerShape(MamaDimens.SheetCorner),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            Column(
                modifier =
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = stringResource(R.string.chats_help_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BigButton(
                    text = stringResource(R.string.chats_help_ok),
                    onClick = onDismiss,
                    variant = MamaButtonVariant.Primary,
                )
            }
        }
    }
}
