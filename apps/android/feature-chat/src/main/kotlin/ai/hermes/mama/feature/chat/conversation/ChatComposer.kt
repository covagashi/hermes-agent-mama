package ai.hermes.mama.feature.chat.conversation

import ai.hermes.mama.core.ui.theme.MamaDimens
import ai.hermes.mama.feature.chat.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp

/**
 * Composer del mockup (C4: sólo texto — 📎/🎤 llegan en C5): campo multilínea
 * grande + botón Enviar deshabilitado en vacío, ambos ≥ 56 dp.
 */
@Composable
internal fun Composer(onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val submit = {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) {
            onSend(trimmed)
            text = ""
        }
    }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f).heightIn(min = MamaDimens.FieldHeight),
            placeholder = { Text(text = stringResource(R.string.chat_composer_hint)) },
            textStyle = MaterialTheme.typography.bodyLarge,
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Send,
                ),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            maxLines = COMPOSER_MAX_LINES,
        )
        IconButton(
            onClick = submit,
            enabled = text.isNotBlank(),
            modifier = Modifier.size(MamaDimens.MinTouchTarget),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = stringResource(R.string.chat_send),
                tint =
                    if (text.isNotBlank()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                modifier = Modifier.size(MamaDimens.IconSizeLarge),
            )
        }
    }
}

private const val COMPOSER_MAX_LINES = 4
