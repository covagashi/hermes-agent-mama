package ai.hermes.mama.core.ui.components

import ai.hermes.mama.core.ui.R
import ai.hermes.mama.core.ui.theme.MamaDimens
import ai.hermes.mama.core.ui.theme.MamaTheme
import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Burbuja de chat (mockup Chat): texto 19 sp, radio 20 dp con la esquina
 * "propia" a 6 dp. Las burbujas de Hermes pueden llevar el botón 🔊
 * ([onListenClick]): su zona táctil es ≥ 56 dp aunque el círculo visible es
 * 44 dp como en el mockup.
 */
@Composable
fun ChatBubble(
    text: String,
    author: ChatBubbleAuthor,
    modifier: Modifier = Modifier,
    onListenClick: (() -> Unit)? = null,
    listenContentDescription: String? = null,
) {
    val isUser = author == ChatBubbleAuthor.User
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier =
                Modifier
                    .widthIn(max = MamaDimens.BubbleMaxWidth)
                    .heightIn(min = MamaDimens.MinTouchTarget),
            shape =
                RoundedCornerShape(
                    topStart = MamaDimens.BubbleCorner,
                    topEnd = MamaDimens.BubbleCorner,
                    bottomEnd = if (isUser) MamaDimens.BubbleOwnCorner else MamaDimens.BubbleCorner,
                    bottomStart = if (isUser) MamaDimens.BubbleCorner else MamaDimens.BubbleOwnCorner,
                ),
            color = if (isUser) scheme.primaryContainer else scheme.surface,
            contentColor = scheme.onBackground,
            border = if (isUser) null else BorderStroke(1.dp, scheme.outline),
        ) {
            Column(
                modifier =
                    Modifier.padding(
                        horizontal = MamaDimens.BubblePaddingHorizontal,
                        vertical = MamaDimens.BubblePaddingVertical,
                    ),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (!isUser && onListenClick != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        ListenButton(
                            onClick = onListenClick,
                            contentDescription =
                                listenContentDescription
                                    ?: stringResource(R.string.design_listen_message),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Botón "escuchar" de la burbuja de Hermes: objetivo táctil 56 dp con el
 * círculo visible de 44 dp del mockup.
 */
@Composable
private fun ListenButton(
    onClick: () -> Unit,
    contentDescription: String,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(MamaDimens.MinTouchTarget)) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Preview(name = "Burbujas claro", showBackground = true)
@Preview(name = "Burbujas oscuro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ChatBubblePreview() {
    MamaTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ChatBubble(
                    text = stringResource(R.string.design_example_bubble_user),
                    author = ChatBubbleAuthor.User,
                )
                ChatBubble(
                    text = stringResource(R.string.design_example_bubble_hermes),
                    author = ChatBubbleAuthor.Hermes,
                    onListenClick = {},
                )
            }
        }
    }
}
