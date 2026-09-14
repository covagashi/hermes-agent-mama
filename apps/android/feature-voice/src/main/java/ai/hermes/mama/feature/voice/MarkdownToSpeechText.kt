package ai.hermes.mama.feature.voice

/**
 * Convierte el Markdown de las respuestas de Hermes en texto plano pensado para que un
 * `TextToSpeech` es-ES lo lea en voz alta sin tropezar con la sintaxis (ROADMAP §5, D2):
 *
 * - `**negrita**`, `__negrita__`, `*cursiva*`, `_cursiva_`, `~~tachado~~` → texto sin marcas.
 * - `[texto](url)` → `texto`; `![alt](img)` → `alt`; URLs sueltas → [linkWord]
 *   ("enlace"): leer una URL carácter a carácter es ruido para la usuaria.
 * - Listas `- item` / `1. item` y saltos de línea → frases terminadas en punto, para
 *   que el motor haga una pausa entre elementos.
 * - Bloques de código → palabra [codeWord] ("código") + lectura plana del contenido.
 * - Emojis → se eliminan: es-ES los lee como "emoji cara sonriente" y ensucian la frase.
 *
 * Las palabras habladas ([codeWord], [linkWord]) llegan por constructor desde recursos
 * (`strings_voice_output.xml`), nunca hardcodeadas; los tests JVM pasan los literales.
 */
class MarkdownToSpeechText(
    private val codeWord: String,
    private val linkWord: String,
) {
    fun toSpeechText(markdown: String): String {
        var text = markdown.replace("\r\n", "\n").replace('\r', '\n')

        // Bloques de código cercados (``` o ~~~): "código" + contenido en lectura plana.
        text =
            FENCED_CODE.replace(text) { match ->
                val body =
                    match.groupValues[1]
                        .lines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .joinToString(", ")
                if (body.isEmpty()) "$codeWord. " else "$codeWord. $body "
            }
        // Código en línea: conserva el texto, quita las comillas invertidas.
        text = INLINE_CODE.replace(text) { it.groupValues[1] }
        // Imágenes → texto alternativo; enlaces Markdown → texto visible.
        text = IMAGE.replace(text) { it.groupValues[1] }
        text = LINK.replace(text) { it.groupValues[1] }
        // Autolinks <https://…> y URLs sueltas → "enlace".
        text = AUTOLINK.replace(text) { " $linkWord " }
        text = BARE_URL.replace(text) { " $linkWord " }
        // Marcas de bloque al inicio de línea: encabezados, citas, listas.
        text = HEADING.replace(text, "")
        text = BLOCKQUOTE.replace(text, "")
        text = HORIZONTAL_RULE.replace(text, "")
        text = LIST_MARKER.replace(text, "")
        // Tablas: la fila separadora (|---|---|) desaparece y los `|` quedan como pausas.
        text = TABLE_SEPARATOR_ROW.replace(text, "")
        text = text.replace('|', ',')
        // Énfasis: conserva el texto, quita las marcas (de más fuerte a más débil).
        text = BOLD_STAR.replace(text) { it.groupValues[1] }
        text = BOLD_UNDERSCORE.replace(text) { it.groupValues[1] }
        text = STRIKETHROUGH.replace(text) { it.groupValues[1] }
        text = ITALIC_STAR.replace(text) { it.groupValues[1] }
        text = ITALIC_UNDERSCORE.replace(text) { it.groupValues[1] }
        // "3 * 4" es una multiplicación, no énfasis: se lee "3 por 4".
        text = TIMES.replace(text) { "${it.groupValues[1]} por ${it.groupValues[2]}" }
        // Restos: escapes Markdown (\* → *), asteriscos y barras sueltos, HTML, emojis.
        text = ESCAPED_PUNCT.replace(text) { it.groupValues[1] }
        text = text.replace("*", "").replace("\\", "")
        text = HTML_TAG.replace(text, "")
        text = EMOJI.replace(text, " ")

        // Cada línea que queda se lee como una frase: si no acaba en puntuación de
        // cierre se le añade un punto para que el TTS haga la pausa.
        return text
            .lines()
            .map { it.trim(' ', ',', ';', ':') }
            .filter { it.isNotEmpty() }
            .joinToString(" ") { line -> line.asSentence() }
            .cleanup()
    }

    /** Añade el punto final si la línea no cierra ya una frase. */
    private fun String.asSentence(): String =
        if (isEmpty() || last() in SENTENCE_ENDINGS) {
            this
        } else {
            trimEnd(':', ';', ',') + "."
        }

    /** Colapsa espacios dobles y la puntuación duplicada que deja la limpieza. */
    private fun String.cleanup(): String =
        MULTI_SPACE
            .replace(this, " ")
            .replace(SPACE_BEFORE_PUNCT, "$1")
            .replace(MULTI_DOTS, ".")
            .replace(DOT_COMMA, ". ")
            .replace(LEADING_PUNCT, "")
            .trim()

    private companion object {
        /** Cierres de frase que el TTS ya pausa por sí solo. */
        private const val SENTENCE_ENDINGS = ".!?…»"

        // (```|~~~) [lenguaje opcional] \n cuerpo (```|~~~|fin de texto)
        private val FENCED_CODE =
            Regex("(?:```|~~~)[^\\n]*\\n(.*?)(?:```|~~~|$)", RegexOption.DOT_MATCHES_ALL)
        private val INLINE_CODE = Regex("`([^`\\n]+)`")
        private val IMAGE = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
        private val LINK = Regex("\\[([^\\]]+)]\\([^)]*\\)")
        private val AUTOLINK = Regex("<https?://[^>\\s]+>")
        private val BARE_URL = Regex("\\bhttps?://\\S+|\\bwww\\.\\S+")
        private val HEADING = Regex("^\\s{0,3}#{1,6}\\s+", RegexOption.MULTILINE)
        private val BLOCKQUOTE = Regex("^\\s{0,3}>\\s?", RegexOption.MULTILINE)
        private val HORIZONTAL_RULE =
            Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$", RegexOption.MULTILINE)
        private val LIST_MARKER =
            Regex("^\\s*(?:[-*+]|\\d{1,3}[.)])\\s+", RegexOption.MULTILINE)
        private val TABLE_SEPARATOR_ROW =
            Regex("^[\\s|:\\-]+$", RegexOption.MULTILINE)
        private val BOLD_STAR = Regex("\\*\\*([^*]+)\\*\\*")
        private val BOLD_UNDERSCORE = Regex("__([^_]+)__")
        private val STRIKETHROUGH = Regex("~~([^~]+)~~")
        private val ITALIC_STAR = Regex("\\*([^*\\n]+)\\*")
        private val ITALIC_UNDERSCORE = Regex("(?<![\\w])_([^_\\n]+)_(?![\\w])")
        private val TIMES = Regex("(\\d) ?\\* ?(\\d)")
        private val ESCAPED_PUNCT = Regex("\\\\(\\p{Punct})")

        /** Sólo etiquetas de formato habituales: `<mercado>` no es HTML, es texto. */
        private val HTML_TAG =
            Regex("</?(?:b|i|em|strong|code|br|p|div|span|ul|ol|li|a|s|u)(\\s[^>]*)?/?>")

        /**
         * Emojis y símbolos pictográficos: planos astrales, dingbats, flechas, teclas,
         * banderas, modificadores de tono de piel, variation selectors y ZWJ.
         */
        private val EMOJI =
            Regex(
                "[\\x{1F000}-\\x{1FAFF}]|[\\x{1F1E6}-\\x{1F1FF}]|[\\x{2600}-\\x{27BF}]|" +
                    "[\\x{2B00}-\\x{2BFF}]|[\\x{2300}-\\x{23FF}]|[\\x{2190}-\\x{21FF}]|" +
                    "\\x{FE0F}|\\x{200D}|\\x{20E3}|[\\x{FE00}-\\x{FE0F}]|" +
                    "[\\x{E0020}-\\x{E007F}]|\\x{3030}|\\x{303D}|\\x{3297}|\\x{3299}",
            )
        private val MULTI_SPACE = Regex(" {2,}")
        private val SPACE_BEFORE_PUNCT = Regex(" ([,.;:])")
        private val MULTI_DOTS = Regex("\\.{2,}")
        private val DOT_COMMA = Regex("\\.\\s*[,.;:]+\\s*")
        private val LEADING_PUNCT = Regex("^[,.;:]+ ?")
    }
}
