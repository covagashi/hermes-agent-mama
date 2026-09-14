package ai.hermes.mama.feature.voice

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests JVM de [MarkdownToSpeechText] (ROADMAP §5, D2): el Markdown de las burbujas
 * de Hermes se convierte en texto llano que un TTS es-ES puede leer con pausas.
 *
 * Las palabras habladas se pasan explícitas ("código", "enlace"): en producción
 * llegan de `strings_voice_output.xml` vía [SpeechOutputFactory].
 */
class MarkdownToSpeechTextTest {
    private val cleaner = MarkdownToSpeechText(codeWord = "código", linkWord = "enlace")

    @Test
    fun `bold markers are removed keeping the text`() {
        assertEquals("Negrita importante.", cleaner.toSpeechText("**Negrita** importante"))
        assertEquals("Negrita importante.", cleaner.toSpeechText("__Negrita__ importante"))
    }

    @Test
    fun `italic and strikethrough markers are removed`() {
        assertEquals("Es cursiva.", cleaner.toSpeechText("Es *cursiva*."))
        assertEquals("Es cursiva.", cleaner.toSpeechText("Es _cursiva_."))
        assertEquals("Texto tachado.", cleaner.toSpeechText("~~Texto tachado~~"))
    }

    @Test
    fun `underscores inside identifiers are kept`() {
        // snake_case no es cursiva: las barras bajas internas no se tocan.
        assertEquals("Abre fichero_final.txt.", cleaner.toSpeechText("Abre `fichero_final.txt`"))
        assertEquals("La variable mi_variable existe.", cleaner.toSpeechText("La variable mi_variable existe"))
    }

    @Test
    fun `markdown link keeps only the visible text`() {
        assertEquals(
            "Pulsa aquí para verlo.",
            cleaner.toSpeechText("[Pulsa aquí](https://hermes.example.invalid) para verlo"),
        )
    }

    @Test
    fun `image keeps only its alt text`() {
        assertEquals(
            "Te mando foto del ticket.",
            cleaner.toSpeechText("Te mando ![foto del ticket](https://hermes.example.invalid/t.png)"),
        )
    }

    @Test
    fun `bare urls become the link word`() {
        val speech = cleaner.toSpeechText("Mira https://hermes.example.invalid/factura cuando puedas")
        assertEquals("Mira enlace cuando puedas.", speech)
    }

    @Test
    fun `unordered list items become paused sentences`() {
        assertEquals("pan. leche. huevos.", cleaner.toSpeechText("- pan\n- leche\n- huevos"))
    }

    @Test
    fun `ordered list markers are removed`() {
        assertEquals("primero. segundo.", cleaner.toSpeechText("1. primero\n2. segundo"))
    }

    @Test
    fun `fenced code block is announced and read flat`() {
        val speech = cleaner.toSpeechText("Ejecuta esto:\n```bash\nls -la\npwd\n```")
        assertTrue(speech.startsWith("Ejecuta esto. código."), speech)
        assertTrue(speech.contains("ls -la"), "el contenido del bloque se lee en plano")
        assertFalse(speech.contains("```"), "las cercas no se leen")
    }

    @Test
    fun `inline code keeps its text`() {
        assertEquals("Pulsa Enter para seguir.", cleaner.toSpeechText("Pulsa `Enter` para seguir"))
    }

    @Test
    fun `headings and quotes lose their markers`() {
        assertEquals("Título. Contenido.", cleaner.toSpeechText("## Título\nContenido"))
        assertEquals("Esto es una cita.", cleaner.toSpeechText("> Esto es una cita"))
    }

    @Test
    fun `line breaks become sentence pauses`() {
        val speech = cleaner.toSpeechText("Primera línea\nSegunda línea\n\nTercera.")
        assertEquals("Primera línea. Segunda línea. Tercera.", speech)
    }

    @Test
    fun `existing sentence punctuation is not duplicated`() {
        assertEquals("Ya tiene punto.", cleaner.toSpeechText("Ya tiene punto."))
        assertEquals("¿Seguro?", cleaner.toSpeechText("¿Seguro?"))
    }

    @Test
    fun `emojis are stripped`() {
        assertEquals("Guardado correctamente.", cleaner.toSpeechText("Guardado ✅ correctamente"))
        assertEquals("Hecho.", cleaner.toSpeechText("✅ Hecho."))
    }

    @Test
    fun `table pipes become pauses and separator row is dropped`() {
        val speech = cleaner.toSpeechText("| Producto | Precio |\n|---|---|\n| Pan | 1 € |")
        assertFalse(speech.contains('|'), "las barras de tabla no se leen")
        assertFalse(speech.contains("---"), "la fila separadora no se lee")
        assertTrue(speech.contains("Producto"), speech)
        assertTrue(speech.contains("Pan"), speech)
    }

    @Test
    fun `multiplication reads as por`() {
        assertEquals("Son 3 por 4.", cleaner.toSpeechText("Son 3 * 4"))
    }

    @Test
    fun `blank input produces blank output`() {
        assertEquals("", cleaner.toSpeechText(""))
        assertEquals("", cleaner.toSpeechText("   \n  "))
    }
}
