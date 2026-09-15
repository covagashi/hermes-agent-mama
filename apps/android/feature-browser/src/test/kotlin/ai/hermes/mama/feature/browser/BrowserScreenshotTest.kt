package ai.hermes.mama.feature.browser

import ai.hermes.mama.core.ui.theme.MamaTheme
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Capturas Roborazzi de la pantalla Navegador (ROADMAP §5/F4, §7.1): navegando,
 * con el velo «Un momento…» (claro/oscuro), aviso de auto-apertura y servidor
 * sin flag. Los PNG caen en `apps/android/screenshots/`
 * (`:feature-browser:recordRoborazzi`). El hueco del WebView es un panel liso —
 * en JVM el WebView no dibuja.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// w390dp-h800dp: como las capturas de :feature-chat — 390 dp reales de mockup.
@Config(sdk = [34], qualifiers = "w390dp-h800dp")
class BrowserScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun browserScreen_browsing_light() =
        capture("browser_screen_browsing_light.png", darkTheme = false) {
            BrowserPaneState(
                phase = BrowserPhase.Browsing,
                progressMessage = "Hermes está buscando tu factura en Tienda Ejemplo",
            )
        }

    @Test
    fun browserScreen_veil_light() = capture("browser_screen_veil_light.png", darkTheme = false) { veilState() }

    @Test
    fun browserScreen_veil_dark() = capture("browser_screen_veil_dark.png", darkTheme = true) { veilState() }

    @Test
    fun browserScreen_auto_open_light() =
        capture("browser_screen_auto_open_light.png", darkTheme = false) {
            BrowserPaneState(
                phase = BrowserPhase.Browsing,
                progressMessage = "Abro la web de la tienda",
                showAutoOpenNotice = true,
            )
        }

    @Test
    fun browserScreen_not_enabled_light() =
        capture("browser_screen_not_enabled_light.png", darkTheme = false) {
            BrowserPaneState(phase = BrowserPhase.ServerNotEnabled)
        }

    @Test
    @Config(sdk = [34], fontScale = 2.0f, qualifiers = "w390dp-h800dp")
    fun browserScreen_veil_light_font200() =
        capture("browser_screen_veil_light_font200.png", darkTheme = false) { veilState() }

    private fun veilState() =
        BrowserPaneState(
            phase = BrowserPhase.Browsing,
            progressMessage = "voy a pulsar «Descargar factura»",
            commandInFlight = true,
        )

    private fun capture(
        fileName: String,
        darkTheme: Boolean,
        state: () -> BrowserPaneState,
    ) {
        composeRule.setContent {
            MamaTheme(darkTheme = darkTheme) {
                BrowserScreen(state = state(), onStop = {}, onBackToChat = {}) {
                    FakePage()
                }
            }
        }
        composeRule.onRoot().captureRoboImage(fileName)
    }
}

/** Panel liso que hace de «página web» en las capturas (sin WebView real). */
@Composable
private fun FakePage() {
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
}
