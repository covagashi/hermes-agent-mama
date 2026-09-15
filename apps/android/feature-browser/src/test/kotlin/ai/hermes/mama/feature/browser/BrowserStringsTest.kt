package ai.hermes.mama.feature.browser

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Copy exacto que fija el roadmap para F3 (§5): el aviso de auto-navegación y
 * el texto de `ServerNotEnabled` deben existir tal cual en `values/` — la UI de
 * F4 los consume por `R.string`.
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
}
