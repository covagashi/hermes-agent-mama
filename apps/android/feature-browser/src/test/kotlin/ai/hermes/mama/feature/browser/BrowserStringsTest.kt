package ai.hermes.mama.feature.browser

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Copy exacto que fija el roadmap para F3/F4 (§5): el aviso de auto-navegación,
 * el texto de `ServerNotEnabled` y los textos de la pantalla («Parar»,
 * «Un momento…», «Hermes está navegando…», «Volver al chat») deben existir tal
 * cual en `values/` — la UI los consume por `R.string`.
 */
class BrowserStringsTest {
    // Gradle ejecuta los unit tests con cwd = directorio del módulo.
    private val stringsXml = File("src/main/res/values/strings_browser.xml").readText()

    @Test
    fun `aviso de auto-apertura del navegador`() {
        assertTrue(
            stringsXml.contains(">Hermes va a usar el navegador<"),
            "browser_auto_open_notice debe ser «Hermes va a usar el navegador» (§5/F3)",
        )
    }

    @Test
    fun `texto de servidor sin navegador compartido`() {
        assertTrue(
            stringsXml.contains(">El servidor no tiene activado el navegador compartido<"),
            "browser_server_not_enabled debe ser «El servidor no tiene activado el navegador compartido» (§5/F3)",
        )
    }

    @Test
    fun `copy de la pantalla Navegador`() {
        listOf(
            ">Hermes está navegando…<" to "browser_default_progress (§5/F4)",
            ">Un momento…<" to "browser_wait (§5/F4)",
            ">Parar<" to "browser_stop (§5/F4)",
            ">Volver al chat<" to "browser_back_to_chat (§5/F4)",
        ).forEach { (needle, name) ->
            assertTrue(stringsXml.contains(needle), "$name debe existir tal cual en values/")
        }
    }
}
