package ai.hermes.mama.feature.chat.chats

import ai.hermes.mama.core.ui.theme.MamaDimens
import ai.hermes.mama.feature.chat.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Una fila de la lista de chats (mockup Main.dc.html): avatar circular con
 * emoji, título 20 sp negrita, última línea y hora a la derecha — 92 dp de
 * alto, separador fino abajo.
 *
 * El swipe hacia la izquierda descubre el fondo "Borrar" pero NO borra:
 * al soltar, la fila vuelve a su sitio y se abre el diálogo de confirmación
 * ([onDeleteRequested] lo dispara la pantalla). La misma acción existe como
 * acción accesible de TalkBack (`customActions`), porque un gesto de swipe no
 * es descubrible con el lector de pantalla.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListRow(
    chat: ChatRowUi,
    onClick: () -> Unit,
    onDeleteRequested: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val deleteAction = stringResource(R.string.chats_delete_row_action, chat.displayTitle())
    val dismissState =
        rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                if (value == SwipeToDismissBoxValue.EndToStart) {
                    // El gesto no borra: pide confirmación y la fila regresa.
                    onDeleteRequested()
                }
                // Nunca se confirma el dismiss: la fila siempre vuelve a
                // Settled; el borrado lo decide el diálogo.
                value == SwipeToDismissBoxValue.Settled
            },
        )
    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = { DeleteBackground() },
    ) {
        RowContent(
            chat = chat,
            onClick = onClick,
            enabled = enabled,
            deleteAction = deleteAction,
            onDeleteAction = onDeleteRequested,
        )
    }
}

/** Título de la fila con el fallback "Chat nuevo" (chat sin título todavía). */
@Composable
internal fun ChatRowUi.displayTitle(): String = title.ifBlank { stringResource(R.string.chats_untitled) }

/** Texto de tiempo ya resuelto por el VM; "Ayer" viene de strings. */
@Composable
internal fun ChatTimeLabel.text(): String =
    when (this) {
        is ChatTimeLabel.Today -> text
        ChatTimeLabel.Yesterday -> stringResource(R.string.chats_time_yesterday)
        is ChatTimeLabel.Weekday -> text
        is ChatTimeLabel.Date -> text
    }

/** Fondo que se descubre al deslizar: icono de papelera + "Borrar". */
@Composable
private fun DeleteBackground() {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.tertiaryContainer)
                .padding(horizontal = 24.dp)
                // Decorativo: la acción accesible de la fila ya ofrece el
                // borrado a TalkBack; este "Borrar" no debe entrar al árbol
                // semántico (confundiría al lector y duplicaría el del diálogo).
                .clearAndSetSemantics {},
        contentAlignment = Alignment.CenterEnd,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = null, // el texto ya lo dice
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(MamaDimens.IconSizeLarge),
            )
            Text(
                text = stringResource(R.string.chats_delete_action),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

/** Contenido visible de la fila (la tarjeta que viaja al deslizar). */
@Composable
private fun RowContent(
    chat: ChatRowUi,
    onClick: () -> Unit,
    enabled: Boolean,
    deleteAction: String,
    onDeleteAction: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier =
            Modifier
                .fillMaxWidth()
                // Un solo nodo para TalkBack: emoji + título + preview + hora.
                .semantics(mergeDescendants = true) {
                    // El swipe no es descubrible con lector de pantalla: la
                    // misma petición de borrado existe como acción accesible.
                    customActions =
                        listOf(
                            CustomAccessibilityAction(label = deleteAction) {
                                onDeleteAction()
                                true
                            },
                        )
                },
    ) {
        Column {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = MamaDimens.ChatRowHeight)
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChatAvatar(emoji = chat.emoji)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = chat.displayTitle(),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (chat.preview.isNotBlank()) {
                        Text(
                            text = chat.preview,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                RowTrailing(chat)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
    }
}

/** Columna derecha del mockup: etiqueta de tiempo + punto verde opcional. */
@Composable
private fun RowTrailing(chat: ChatRowUi) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = chat.timeLabel.text(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (chat.running) {
            RunningDot()
        }
    }
}

/** Círculo `primaryContainer` 56 dp con el emoji del chat (mockup: icono 28 px). */
@Composable
private fun ChatAvatar(emoji: String) {
    Surface(
        modifier = Modifier.size(MamaDimens.MinTouchTarget),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text = emoji, fontSize = 26.sp)
        }
    }
}

/** Punto verde del mockup: Hermes sigue trabajando en ese chat. */
@Composable
private fun RunningDot() {
    val description = stringResource(R.string.chats_running)
    Box(
        modifier =
            Modifier
                .size(12.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
                .semantics { contentDescription = description },
    )
}
