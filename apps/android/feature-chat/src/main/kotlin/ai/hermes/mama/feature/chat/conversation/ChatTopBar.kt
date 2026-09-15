package ai.hermes.mama.feature.chat.conversation

import ai.hermes.mama.core.ui.theme.MamaDimens
import ai.hermes.mama.feature.chat.R
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Barra superior de la pantalla Chat (mockup `Chat.dc.html`): volver, avatar
 * con la inicial y título + una línea de estado ("Hermes está escribiendo…",
 * el `status.update` del wire o el aviso de encolado/turno vivo).
 */
@Composable
internal fun ChatTopBar(
    header: ChatHeader,
    streaming: Boolean,
    onBack: () -> Unit,
) {
    val subtitle =
        when {
            header.statusText != null -> header.statusText
            streaming -> stringResource(R.string.chat_typing)
            header.queued -> stringResource(R.string.chat_queued)
            header.running -> stringResource(R.string.chat_running)
            else -> null
        }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = MamaDimens.TopBarHeight)
                    .padding(end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(MamaDimens.MinTouchTarget)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.chat_back),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            ChatAvatar(title = header.title)
            Column(
                modifier =
                    Modifier
                        .padding(start = 12.dp)
                        .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(
                    text = header.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Círculo con la inicial del título (o «H» de Hermes si el chat aún no tiene). Decorativo. */
@Composable
private fun ChatAvatar(title: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.size(44.dp).clearAndSetSemantics {},
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = title.firstOrNull()?.uppercase() ?: AVATAR_FALLBACK,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

private const val AVATAR_FALLBACK = "H"
