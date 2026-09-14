package ai.hermes.mama.core.controller

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Parser + truncado del resultado de `__hermes.snapshot()` (F1, ROADMAP §2.6).
 */
class SnapshotResultTest {
    @Test
    fun `parsea el JSON directo que devuelve snapshot()`() {
        val raw = """{"success":true,"snapshot":"- button \"Entrar\" [ref=e1]","element_count":1,"ref_count":1}"""
        val result = SnapshotResult.parse(raw)
        assertEquals("- button \"Entrar\" [ref=e1]", result.text)
        assertEquals(1, result.refCount)
    }

    @Test
    fun `parsea el string con doble quoting de evaluateJavascript`() {
        // Así llega un string JS por WebView.evaluateJavascript: JSON dentro de
        // una cadena JSON (\" y saltos escapados).
        val inner =
            buildJsonObject {
                put("success", true)
                put("snapshot", "- link \"Inicio\" [ref=e2]")
                put("element_count", 3)
                put("ref_count", 7)
            }.toString()
        val raw = JsonPrimitive(inner).toString()
        val result = SnapshotResult.parse(raw)
        assertEquals("- link \"Inicio\" [ref=e2]", result.text)
        assertEquals(7, result.refCount)
    }

    @Test
    fun `refCount cae a element_count si falta ref_count`() {
        val raw = """{"success":true,"snapshot":"- main","element_count":4}"""
        assertEquals(4, SnapshotResult.parse(raw).refCount)
    }

    @Test
    fun `snapshot vacío es válido`() {
        val raw = """{"success":true,"snapshot":"","element_count":0,"ref_count":0}"""
        val result = SnapshotResult.parse(raw)
        assertEquals("", result.text)
        assertEquals(0, result.refCount)
    }

    @Test
    fun `rechaza success=false`() {
        val raw = """{"success":false,"error":"no snapshot"}"""
        assertFailsWith<IllegalArgumentException> { SnapshotResult.parse(raw) }
    }

    @Test
    fun `rechaza resultados sin snapshot`() {
        assertFailsWith<IllegalArgumentException> { SnapshotResult.parse("""{"success":true}""") }
        assertFailsWith<IllegalArgumentException> { SnapshotResult.parse("no json") }
        assertFailsWith<IllegalArgumentException> { SnapshotResult.parse("[1,2]") }
    }

    @Test
    fun `truncate no toca textos cortos`() {
        val text = "- main\n  - heading \"Hola\" [ref=e1]"
        assertEquals(text, SnapshotResult.truncate(text))
    }

    @Test
    fun `truncate corta por líneas y marca con el marcador`() {
        val line = "- text \"${"x".repeat(60)}\""
        val text = List(400) { line }.joinToString("\n")
        val out = SnapshotResult.truncate(text)
        assertTrue(out.length <= SnapshotResult.MAX_SNAPSHOT_CHARS)
        assertTrue(out.endsWith(SnapshotResult.TRUNCATION_MARKER))
        // Nunca corta una línea a medias.
        val lines = out.split('\n')
        assertEquals(SnapshotResult.TRUNCATION_MARKER, lines.last())
        assertTrue(lines.dropLast(1).all { it == line })
    }

    @Test
    fun `truncate respeta el límite con tamaños pequeños`() {
        val text = List(50) { "- link \"a\" [ref=e$it]" }.joinToString("\n")
        val out = SnapshotResult.truncate(text, maxChars = 40)
        assertTrue(out.length <= 40)
        assertTrue(out.endsWith(SnapshotResult.TRUNCATION_MARKER))
    }

    @Test
    fun `truncate con primera línea mayor que el límite devuelve solo el marcador`() {
        val text = "x".repeat(20_000)
        assertEquals(SnapshotResult.TRUNCATION_MARKER, SnapshotResult.truncate(text))
        assertFalse(SnapshotResult.truncate(text).contains('x'))
    }
}
