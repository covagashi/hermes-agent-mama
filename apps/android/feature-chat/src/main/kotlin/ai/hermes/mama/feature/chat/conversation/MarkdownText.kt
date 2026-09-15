package ai.hermes.mama.feature.chat.conversation

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

/**
 * Markdown mínimo del chat (ROADMAP C4):
 *
 * - `**negrita**` / `__negrita__` → negrita (el interior se parsea también:
 *   `**[t](url)**` funciona).
 * - Listas `- ítem` / `* ítem` al inicio de línea → `• ítem` (la numerada
 *   `1.` se deja literal, ya se lee bien).
 * - `[texto](url)` → enlace pulsable ([LinkAnnotation.Url]) con el estilo link.
 * - `` `código` `` en línea y bloques ` ``` ` cercados → texto monoespaciado
 *   plano (sin resaltado: "código como texto plano monoespaciado", C4).
 *
 * Lo demás se deja literal — incluidos marcadores sin cerrar (`**suelto` se
 * ve tal cual, nunca rompe el render).
 */
fun markdownToAnnotated(
    text: String,
    styles: MarkdownStyles,
): AnnotatedString =
    buildAnnotatedString {
        var fenced = false
        var firstEmitted = true
        for (line in text.split('\n')) {
            if (line.trimStart().startsWith(CODE_FENCE)) {
                // La línea de cercas no se pinta; abre/cierra el bloque mono.
                fenced = !fenced
                continue
            }
            if (!firstEmitted) {
                append('\n')
            }
            firstEmitted = false
            if (fenced) {
                withStyle(styles.code) { append(line) }
            } else {
                appendInline(BULLET_REGEX.replace(line, "$1• "), styles)
            }
        }
    }

private fun AnnotatedString.Builder.appendInline(
    text: String,
    styles: MarkdownStyles,
) {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith(BOLD_STAR, i) || text.startsWith(BOLD_LINE, i) -> {
                val marker = text.substring(i, i + BOLD_MARKER_LEN)
                val end = text.indexOf(marker, i + BOLD_MARKER_LEN)
                if (end < 0 || end == i + BOLD_MARKER_LEN) {
                    append(marker)
                    i += BOLD_MARKER_LEN
                } else {
                    withStyle(styles.bold) { appendInline(text.substring(i + BOLD_MARKER_LEN, end), styles) }
                    i = end + BOLD_MARKER_LEN
                }
            }

            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end <= i + 1) {
                    append('`')
                    i += 1
                } else {
                    withStyle(styles.code) { append(text.substring(i + 1, end)) }
                    i = end + 1
                }
            }

            text[i] == '[' -> {
                val link = parseLink(text, i)
                if (link == null) {
                    append('[')
                    i += 1
                } else {
                    withLink(LinkAnnotation.Url(link.url)) {
                        withStyle(styles.link) { append(link.label) }
                    }
                    i = link.end
                }
            }

            else -> {
                append(text[i])
                i += 1
            }
        }
    }
}

private data class ParsedLink(
    val label: String,
    val url: String,
    /** Índice justo después del `)` de cierre. */
    val end: Int,
)

/** `[etiqueta](url)` a partir de [openBracket]; `null` si no casa (el `[` se pinta literal). */
private fun parseLink(
    text: String,
    openBracket: Int,
): ParsedLink? {
    val labelEnd = text.indexOf("](", openBracket + 1)
    val urlEnd = if (labelEnd < 0) -1 else text.indexOf(')', labelEnd + 2)
    val parsed =
        if (labelEnd < 0 || urlEnd < 0) {
            null
        } else {
            ParsedLink(
                label = text.substring(openBracket + 1, labelEnd),
                url = text.substring(labelEnd + 2, urlEnd),
                end = urlEnd + 1,
            )
        }
    return parsed?.takeIf { it.label.isNotEmpty() && it.url.isNotEmpty() }
}

private const val CODE_FENCE = "```"
private const val BOLD_STAR = "**"
private const val BOLD_LINE = "__"
private const val BOLD_MARKER_LEN = 2

/** `- ítem` o `* ítem` a inicio de línea (respeta la sangría previa). */
private val BULLET_REGEX = Regex("""^(\s*)[-*]\s+""")
