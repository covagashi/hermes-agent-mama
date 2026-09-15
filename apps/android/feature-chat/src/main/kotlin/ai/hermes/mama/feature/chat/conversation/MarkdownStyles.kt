package ai.hermes.mama.feature.chat.conversation

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/**
 * Estilos del Markdown mínimo de C4, resueltos desde el tema (composable).
 */
data class MarkdownStyles(
    val bold: SpanStyle,
    val code: SpanStyle,
    val link: SpanStyle,
)

/** Estilos por defecto según el tema activo (negrita, monoespaciada, enlace del tema). */
@Composable
fun rememberMarkdownStyles(): MarkdownStyles {
    val linkColor = MaterialTheme.colorScheme.primary
    return MarkdownStyles(
        bold = SpanStyle(fontWeight = FontWeight.Bold),
        code = SpanStyle(fontFamily = FontFamily.Monospace),
        link = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
    )
}
