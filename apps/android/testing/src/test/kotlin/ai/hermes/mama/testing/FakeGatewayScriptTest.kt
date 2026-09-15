package ai.hermes.mama.testing

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parser de guiones ([FakeGatewayScript]): los empaquetados cargan y los
 * errores dicen QUÉ campo falla y en qué paso (requisito B5).
 */
class FakeGatewayScriptTest {
    @Test
    fun `todos los guiones empaquetados cargan`() {
        val names =
            listOf(
                "hola_mundo",
                "deltas",
                "approval",
                "clarify",
                "clarify3",
                "browser",
                "browser_off",
                "browser_cancel",
                "chats_demo",
                "error",
                "lento",
                "rate_limited",
                "renombra",
                "request_cancel",
                "sesiones",
                "ticket_requerido",
            )
        for (name in names) {
            val script = FakeGatewayScript.load(name)
            assertEquals(name, script.name)
        }
    }

    @Test
    fun `guion inexistente da error claro`() {
        val error =
            assertFailsWith<FakeScriptException> {
                FakeGatewayScript.load("guion_que_no_existe")
            }
        assertTrue(error.message.orEmpty().contains("guion_que_no_existe"))
        assertTrue(error.message.orEmpty().contains("no encontrado"))
    }

    @Test
    fun `json malformado da error claro con la fuente`() {
        val error =
            assertFailsWith<FakeScriptException> {
                FakeGatewayScript.parse("{ esto no es json", source = "roto.json")
            }
        assertTrue(error.message.orEmpty().contains("roto.json"))
    }

    @Test
    fun `paso con dos claves da error que nombra el paso`() {
        val error =
            assertFailsWith<FakeScriptException> {
                FakeGatewayScript.parse(
                    """{"name":"x","turns":[{"steps":[{"delta":"hola","sleep_ms":5}]}]}""",
                    source = "test.json",
                )
            }
        assertTrue(error.message.orEmpty().contains("steps[0]"))
    }

    @Test
    fun `request sin method da error claro`() {
        val error =
            assertFailsWith<FakeScriptException> {
                FakeGatewayScript.parse(
                    """{"name":"x","turns":[{"steps":[{"request":{"params":{}}}]}]}""",
                    source = "test.json",
                )
            }
        assertTrue(error.message.orEmpty().contains("method"))
    }

    @Test
    fun `when con claves desconocidas da error que las nombra`() {
        val error =
            assertFailsWith<FakeScriptException> {
                FakeGatewayScript.parse(
                    """{"name":"x","turns":[{"when":{"txt":"hola"},"steps":[]}]}""",
                    source = "test.json",
                )
            }
        // Un matcher nulo por clave desconocida sería un catch-all SILENCIOSO.
        assertTrue(error.message.orEmpty().contains("txt"), "debe nombrar la clave mala: ${error.message}")
        assertTrue(error.message.orEmpty().contains("text_contains"))
    }

    @Test
    fun `when vacio da error en vez de casar todo`() {
        val error =
            assertFailsWith<FakeScriptException> {
                FakeGatewayScript.parse(
                    """{"name":"x","turns":[{"when":{},"steps":[]}]}""",
                    source = "test.json",
                )
            }
        assertTrue(error.message.orEmpty().contains("when"))
    }

    @Test
    fun `browser developer_mode se lee del guion`() {
        val script =
            FakeGatewayScript.parse(
                """{"name":"x","browser":{"enabled":false,"developer_mode":true}}""",
                source = "test.json",
            )
        assertEquals(false, script.browser.enabled)
        assertEquals(true, script.browser.developerMode)
        // Por defecto: controlador habilitado sin caps de desarrollador.
        assertEquals(true, FakeGatewayScript.echo().browser.enabled)
        assertEquals(false, FakeGatewayScript.echo().browser.developerMode)
    }

    @Test
    fun `regex invalida da error claro`() {
        val error =
            assertFailsWith<FakeScriptException> {
                FakeGatewayScript.parse(
                    """{"name":"x","turns":[{"when":{"text_regex":"(["},"steps":[]}]}""",
                    source = "test.json",
                )
            }
        assertTrue(error.message.orEmpty().contains("regex"))
    }

    @Test
    fun `turnFor casa por texto y cae en default`() {
        val script =
            FakeGatewayScript.parse(
                """
                {"name":"x","turns":[
                  {"when":{"text_contains":"hola"},"steps":[]},
                  {"when":{"text_regex":"^adios"},"steps":[]}
                ],"default_turn":{"steps":[{"delta":"def"}]}}
                """,
                source = "test.json",
            )
        assertEquals(script.turns[0], script.turnFor("dime hola"))
        assertEquals(script.turns[1], script.turnFor("adiosito"))
        assertEquals(script.defaultTurn, script.turnFor("otra cosa"))
        // Un guion sin default y sin match devuelve null (el dispatcher usa el fallback).
        val sinDefault =
            FakeGatewayScript.parse(
                """{"name":"y","turns":[{"when":{"text_contains":"x"},"steps":[]}]}""",
                source = "test.json",
            )
        assertNull(sinDefault.turnFor("nada casa"))
        assertNotNull(script.defaultTurn)
    }

    @Test
    fun `auth por defecto acepta usuario mama`() {
        val script = FakeGatewayScript.parse("""{"name":"x"}""", source = "test.json")
        assertEquals("usuario", script.auth.username)
        assertEquals("mama", script.auth.password)
        assertEquals("basic", script.auth.provider)
    }

    @Test
    fun `echo es un guion valido de respaldo`() {
        val script = FakeGatewayScript.echo()
        assertEquals("echo", script.name)
        assertNotNull(script.defaultTurn)
        assertEquals(3, script.defaultTurn?.steps?.size)
    }
}
