package ai.hermes.mama.feature.chat.conversation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests del Markdown mínimo de C4 ([markdownToAnnotated]): negrita, listas,
 * enlaces y código monoespaciado — puro JVM, sin Compose runtime.
 */
class MarkdownTextTest {
    private val styles =
        MarkdownStyles(
            bold = SpanStyle(fontWeight = FontWeight.Bold),
            code = SpanStyle(fontFamily = FontFamily.Monospace),
            link = SpanStyle(color = Color.Blue, textDecoration = TextDecoration.Underline),
        )

    @Test
    fun `negrita con doble asterisco y doble guion bajo`() {
        val annotated = markdownToAnnotated("esto es **importante** y __también__", styles)
        assertEquals("esto es importante y también", annotated.text)

        val boldRanges =
            annotated.spanStyles
                .filter { it.item.fontWeight == FontWeight.Bold }
                .map { annotated.text.substring(it.start, it.end) }
        assertEquals(listOf("importante", "también"), boldRanges)
    }

    @Test
    fun `marcador sin cerrar queda literal`() {
        val annotated = markdownToAnnotated("esto queda **abierto", styles)
        assertEquals("esto queda **abierto", annotated.text)
        assertTrue(annotated.spanStyles.isEmpty())
    }

    @Test
    fun `listas con guion y asterisco pasan a vinetas`() {
        val annotated = markdownToAnnotated("- pan\n* leche\n  - huevos", styles)
        assertEquals("• pan\n• leche\n  • huevos", annotated.text)
    }

    @Test
    fun `enlace con etiqueta y url`() {
        val annotated = markdownToAnnotated("mira [la factura](https://hermes.example.invalid/f) aquí", styles)
        assertEquals("mira la factura aquí", annotated.text)

        val links = annotated.getLinkAnnotations(0, annotated.length)
        assertEquals(1, links.size)
        assertEquals("https://hermes.example.invalid/f", (links[0].item as LinkAnnotation.Url).url)
        assertEquals("la factura", annotated.text.substring(links[0].start, links[0].end))
        assertTrue(links.any { it.item is LinkAnnotation.Url })
        // El enlace lleva además el estilo de link (subrayado).
        assertTrue(annotated.spanStyles.any { it.item.textDecoration == TextDecoration.Underline })
    }

    @Test
    fun `enlace roto queda literal`() {
        val annotated = markdownToAnnotated("un [corchete suelto y otro ](sin cierre", styles)
        assertTrue(annotated.getLinkAnnotations(0, annotated.length).isEmpty())
    }

    @Test
    fun `codigo en linea es monoespaciado`() {
        val annotated = markdownToAnnotated("el fichero `factura.pdf` listo", styles)
        assertEquals("el fichero factura.pdf listo", annotated.text)

        val codeRanges =
            annotated.spanStyles
                .filter { it.item.fontFamily == FontFamily.Monospace }
                .map { annotated.text.substring(it.start, it.end) }
        assertEquals(listOf("factura.pdf"), codeRanges)
    }

    @Test
    fun `bloque cercado entero es monoespaciado y las cercas no se pintan`() {
        val annotated = markdownToAnnotated("mira:\n```\nlinea **uno**\nlinea dos\n```\nlisto", styles)
        assertEquals("mira:\nlinea **uno**\nlinea dos\nlisto", annotated.text)

        val codeRanges =
            annotated.spanStyles
                .filter { it.item.fontFamily == FontFamily.Monospace }
                .map { annotated.text.substring(it.start, it.end) }
        // El bloque conserva sus marcadores literales (no se parsea dentro).
        assertEquals(listOf("linea **uno**", "linea dos"), codeRanges)
    }

    @Test
    fun `texto plano se conserva tal cual`() {
        val plain = "Hola, ¿qué tal? Sin formato: ni ** ni ` ni ["
        val annotated = markdownToAnnotated(plain, styles)
        // El `[` sin `](…)` queda literal; el `**` suelto también.
        assertEquals("Hola, ¿qué tal? Sin formato: ni ** ni ` ni [", annotated.text)
    }

    @Test
    fun `negrita anidada con enlace dentro`() {
        val annotated = markdownToAnnotated("**mira [esto](https://hermes.example.invalid)**", styles)
        assertEquals("mira esto", annotated.text)
        assertTrue(annotated.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertEquals(1, annotated.getLinkAnnotations(0, annotated.length).size)
    }
}
