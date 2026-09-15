package ai.hermes.mama.core.controller

import ai.hermes.mama.contract.BrowserControllerCommandPayload
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Mapeo `browser.controller.command` (payload del wire, §2.6) → [BrowserCommand]
 * tipado: argumentos requeridos, flags tolerantes y acciones desconocidas (F2).
 */
class BrowserCommandTest {
    @Test
    fun `navigate con url`() {
        val cmd = from("browser_navigate", args("url" to "https://hermes.example.invalid/"))
        assertIs<BrowserCommand.Navigate>(cmd)
        assertEquals("https://hermes.example.invalid/", (cmd as BrowserCommand.Navigate).url)
        assertEquals("cmd-1", cmd.commandId)
    }

    @Test
    fun `navigate sin url produce Invalid con motivo en inglés`() {
        val cmd = from("browser_navigate")
        assertIs<BrowserCommand.Invalid>(cmd)
        assertTrue((cmd as BrowserCommand.Invalid).reason.contains("url"))
        val blank = from("browser_navigate", args("url" to "   "))
        assertIs<BrowserCommand.Invalid>(blank)
    }

    @Test
    fun `snapshot compacto por defecto y completo con full`() {
        val compact = from("browser_snapshot")
        assertIs<BrowserCommand.TakeSnapshot>(compact)
        assertEquals(false, (compact as BrowserCommand.TakeSnapshot).full)
        val fullBool = from("browser_snapshot", args("full" to true))
        assertEquals(true, (fullBool as BrowserCommand.TakeSnapshot).full)
        // El wire puede traer el flag como string.
        val fullStr = from("browser_snapshot", JsonObject(buildJsonObject { put("full", "true") }))
        assertEquals(true, (fullStr as BrowserCommand.TakeSnapshot).full)
    }

    @Test
    fun `click type press scroll con sus argumentos`() {
        val click = from("browser_click", args("ref" to "@e5"))
        assertIs<BrowserCommand.Click>(click)
        assertEquals("@e5", (click as BrowserCommand.Click).ref)

        val type = from("browser_type", args("ref" to "@e4", "text" to "hola"))
        assertIs<BrowserCommand.Type>(type)
        assertEquals("hola", (type as BrowserCommand.Type).text)

        // text ausente equivale a "" (clear+type del backend).
        val typeNoText = from("browser_type", args("ref" to "@e4"))
        assertIs<BrowserCommand.Type>(typeNoText)
        assertEquals("", (typeNoText as BrowserCommand.Type).text)

        val press = from("browser_press", args("key" to "Enter"))
        assertIs<BrowserCommand.Press>(press)
        assertEquals("Enter", (press as BrowserCommand.Press).key)

        val scroll = from("browser_scroll", args("direction" to "down"))
        assertIs<BrowserCommand.Scroll>(scroll)
        assertEquals("down", (scroll as BrowserCommand.Scroll).direction)
    }

    @Test
    fun `argumentos requeridos ausentes producen Invalid`() {
        for (action in listOf("browser_click", "browser_type", "browser_press", "browser_scroll")) {
            assertIs<BrowserCommand.Invalid>(from(action), "$action sin args debe ser Invalid")
        }
        assertIs<BrowserCommand.Invalid>(from("browser_tab_activate"))
    }

    @Test
    fun `acciones sin argumentos`() {
        assertIs<BrowserCommand.Noop>(from("controller.noop"))
        assertIs<BrowserCommand.Back>(from("browser_back"))
        assertIs<BrowserCommand.Screenshot>(from("browser_screenshot"))
        assertIs<BrowserCommand.Tabs>(from("browser_tabs"))
        val activate = from("browser_tab_activate", args("id" to "1"))
        assertIs<BrowserCommand.TabActivate>(activate)
        assertEquals("1", (activate as BrowserCommand.TabActivate).tabId)
    }

    @Test
    fun `accion desconocida produce Unsupported conservando el nombre`() {
        val cmd = from("browser_evaluate")
        assertIs<BrowserCommand.Unsupported>(cmd)
        assertEquals("browser_evaluate", cmd.action)
        assertEquals("cmd-1", cmd.commandId)
    }

    @Test
    fun `capabilities del registro cubren exactamente las acciones soportadas`() {
        // §2.6: la app no anuncia artifact_* ni developer_* — el broker las rechaza.
        assertEquals(
            listOf(
                "controller.noop",
                "browser_navigate",
                "browser_snapshot",
                "browser_click",
                "browser_type",
                "browser_scroll",
                "browser_back",
                "browser_press",
                "browser_screenshot",
                "browser_tabs",
                "browser_tab_activate",
            ),
            BrowserCommand.Actions.CAPABILITIES,
        )
    }

    private fun args(vararg pairs: Pair<String, Any?>): JsonObject =
        buildJsonObject {
            for ((k, v) in pairs) {
                when (v) {
                    is String -> put(k, v)
                    is Boolean -> put(k, v)
                    is Number -> put(k, v)
                    null -> put(k, kotlinx.serialization.json.JsonNull)
                    else -> error("tipo de arg no soportado en test")
                }
            }
        }

    private fun from(
        action: String,
        arguments: JsonObject = buildJsonObject {},
    ): BrowserCommand =
        BrowserCommand.from(
            BrowserControllerCommandPayload(
                commandId = "cmd-1",
                action = action,
                arguments = arguments,
                controllerId = "android-test",
                browserProfileId = "mama-webview",
                toolCallId = "tool-test",
            ),
        )
}
