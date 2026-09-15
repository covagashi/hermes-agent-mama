package ai.hermes.mama.feature.browser

import ai.hermes.mama.core.ui.theme.MamaTheme
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.accessibility.AccessibilityChecks
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertTrue

/**
 * Compose UI Test de la pantalla Navegador (ROADMAP §5/F4): barra superior con
 * el último `browser.progress` (o "Hermes está navegando…"), botón **Parar**,
 * velo «Un momento…» sólo con comando en curso, «Volver al chat», estados de
 * error y fuente 2.0×. El hueco del WebView es un panel liso — el WebView real
 * lo cubre el test instrumentado.
 * `AccessibilityChecks` (espresso → ATF) rompe el test ante violaciones ERROR.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp")
class BrowserScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `barra muestra el progreso o el texto por defecto`() {
        val state =
            mutableStateOf(BrowserPaneState(phase = BrowserPhase.Browsing))
        setScreen(state)

        composeRule.onNodeWithText(defaultProgress).assertIsDisplayed()
        composeRule.onNodeWithText(wait).assertDoesNotExist()

        state.value =
            state.value.copy(progressMessage = "Hermes está buscando tu factura en Tienda Ejemplo")
        composeRule.waitForIdle()
        composeRule
            .onNodeWithText("Hermes está buscando tu factura en Tienda Ejemplo")
            .assertIsDisplayed()
    }

    @Test
    fun `Parar es grande y llama al callback`() {
        var stopped = false
        setScreen(mutableStateOf(BrowserPaneState(phase = BrowserPhase.Browsing)), onStop = { stopped = true })

        composeRule
            .onNodeWithContentDescription(stopCd)
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertHeightIsAtLeast(MIN_TOUCH_DP.dp)
            .performClick()
        assertTrue(stopped, "Parar debe llegar al callback")
    }

    @Test
    fun `el velo solo aparece con un comando en curso`() {
        val state =
            mutableStateOf(
                BrowserPaneState(
                    phase = BrowserPhase.Browsing,
                    progressMessage = "voy a pulsar «Descargar factura»",
                ),
            )
        setScreen(state)

        composeRule.onNodeWithText(wait, substring = true).assertDoesNotExist()

        state.value = state.value.copy(commandInFlight = true)
        composeRule.waitForIdle()
        // La píldora lleva «Un momento…» + el detalle del último progreso.
        composeRule
            .onNodeWithText("$wait voy a pulsar «Descargar factura»")
            .assertIsDisplayed()
        // La barra superior (Parar) y la inferior siguen alcanzables: el velo
        // sólo cubre el área web.
        composeRule.onNodeWithContentDescription(stopCd).assertIsDisplayed()
        composeRule.onNodeWithText(backToChat).assertIsDisplayed()

        state.value = state.value.copy(commandInFlight = false)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(wait, substring = true).assertDoesNotExist()
    }

    @Test
    fun `Volver al chat llama al callback`() {
        var back = false
        setScreen(
            mutableStateOf(BrowserPaneState(phase = BrowserPhase.Browsing)),
            onBackToChat = { back = true },
        )

        composeRule.onNodeWithText(backToChat).assertIsDisplayed().performClick()
        assertTrue(back, "Volver al chat debe llegar al callback")
    }

    @Test
    fun `servidor sin el flag muestra el texto correspondiente`() {
        setScreen(mutableStateOf(BrowserPaneState(phase = BrowserPhase.ServerNotEnabled)))

        composeRule.onNodeWithText(serverNotEnabled).assertIsDisplayed()
        composeRule.onNodeWithText(defaultProgress).assertDoesNotExist()
    }

    @Test
    fun `fallo de registro muestra el texto correspondiente`() {
        setScreen(mutableStateOf(BrowserPaneState(phase = BrowserPhase.RegistrationFailed)))

        composeRule.onNodeWithText(registrationFailed).assertIsDisplayed()
    }

    @Test
    fun `el aviso de autoapertura solo sale cuando toca`() {
        val state =
            mutableStateOf(
                BrowserPaneState(phase = BrowserPhase.Browsing, showAutoOpenNotice = true),
            )
        setScreen(state)

        composeRule.onNodeWithText(autoOpenNotice).assertIsDisplayed()

        state.value = state.value.copy(showAutoOpenNotice = false)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(autoOpenNotice).assertDoesNotExist()
    }

    @Test
    @Config(sdk = [34], fontScale = 2.0f, qualifiers = "w390dp")
    fun `fuente 2x no corta la pantalla`() {
        setScreen(
            mutableStateOf(
                BrowserPaneState(
                    phase = BrowserPhase.Browsing,
                    progressMessage = "Hermes está buscando tu factura en Tienda Ejemplo",
                    commandInFlight = true,
                ),
            ),
        )

        composeRule.onNodeWithText("Hermes está buscando tu factura en Tienda Ejemplo").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(stopCd).assertIsDisplayed()
        composeRule.onNodeWithText(wait, substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(backToChat).assertIsDisplayed()
    }

    // --------------------------------------------- hoja de descarga (G1) -----

    @Test
    fun `la hoja de descarga muestra nombre, detalle y las tres acciones`() {
        val state =
            mutableStateOf(
                BrowserPaneState(phase = BrowserPhase.Browsing, download = doc),
            )
        setScreen(state)

        composeRule.onNodeWithText("factura-mama.pdf").assertIsDisplayed()
        composeRule.onNodeWithText("Guardado en Descargas · 4 KB").assertIsDisplayed()
        composeRule.onNodeWithText(open).assertIsDisplayed().assertHeightIsAtLeast(MIN_TOUCH_DP.dp)
        composeRule.onNodeWithText(share).assertIsDisplayed().assertHeightIsAtLeast(MIN_TOUCH_DP.dp)
        composeRule.onNodeWithText(sendToHermes).assertIsDisplayed()
        // G1: «Enviar a Hermes» llega en G2 — visible pero deshabilitado.
        composeRule.onNodeWithText(sendToHermes).assertIsNotEnabled()
        composeRule.onNodeWithText(sendHint, substring = true).assertIsDisplayed()

        state.value = state.value.copy(download = null)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("factura-mama.pdf").assertDoesNotExist()
    }

    @Test
    fun `las acciones de la hoja llaman a sus callbacks`() {
        var opened = false
        var shared = false
        setScreen(
            mutableStateOf(BrowserPaneState(phase = BrowserPhase.Browsing, download = doc)),
            onDownloadOpen = { opened = true },
            onDownloadShare = { shared = true },
        )

        composeRule.onNodeWithText(open).performClick()
        composeRule.onNodeWithText(share).performClick()
        assertTrue(opened, "Abrir debe llegar al callback")
        assertTrue(shared, "Compartir debe llegar al callback")
    }

    @Test
    fun `el scrim lleva Cerrar accesible y descarta la hoja`() {
        var dismissed = false
        setScreen(
            mutableStateOf(BrowserPaneState(phase = BrowserPhase.Browsing, download = doc)),
            onDownloadDismiss = { dismissed = true },
        )

        // OnClick semántico — el gesto de TalkBack. (Un performClick por
        // posición caería en el CENTRO del nodo… tapado por la propia hoja.)
        composeRule
            .onNodeWithContentDescription(dismissCd)
            .assertHasClickAction()
            .performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(dismissed, "el scrim debe descartar la hoja")
    }

    @Test
    fun `accesibilidad con la hoja de descarga abierta`() {
        setScreen(
            mutableStateOf(BrowserPaneState(phase = BrowserPhase.Browsing, download = doc)),
        )
        runEspressoA11yCheck()
    }

    @Test
    fun `accesibilidad en navegando con velo y en error`() {
        val state =
            mutableStateOf(
                BrowserPaneState(
                    phase = BrowserPhase.Browsing,
                    progressMessage = "Hermes está buscando tu factura en Tienda Ejemplo",
                ),
            )
        setScreen(state)
        runEspressoA11yCheck()

        state.value = state.value.copy(commandInFlight = true, showAutoOpenNotice = true)
        composeRule.waitForIdle()
        runEspressoA11yCheck()

        state.value =
            BrowserPaneState(phase = BrowserPhase.ServerNotEnabled)
        composeRule.waitForIdle()
        runEspressoA11yCheck()
    }

    // --- soporte ---

    private fun setScreen(
        state: androidx.compose.runtime.MutableState<BrowserPaneState>,
        onStop: () -> Unit = {},
        onBackToChat: () -> Unit = {},
        onDownloadOpen: () -> Unit = {},
        onDownloadShare: () -> Unit = {},
        onDownloadSendToHermes: () -> Unit = {},
        onDownloadDismiss: () -> Unit = {},
    ) {
        composeRule.setContent {
            MamaTheme {
                BrowserScreen(
                    state = state.value,
                    onStop = onStop,
                    onBackToChat = onBackToChat,
                    onDownloadOpen = onDownloadOpen,
                    onDownloadShare = onDownloadShare,
                    onDownloadSendToHermes = onDownloadSendToHermes,
                    onDownloadDismiss = onDownloadDismiss,
                ) {
                    // En JVM el WebView no dibuja: un panel liso hace de página.
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface),
                    )
                }
            }
        }
    }

    /** Evalúa la raíz con ATF: violaciones de nivel ERROR rompen el test. */
    private fun runEspressoA11yCheck() {
        onView(isRoot()).check(matches(isDisplayed()))
    }

    private companion object {
        const val MIN_TOUCH_DP = 56

        private fun string(id: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(id)

        private val defaultProgress: String get() = string(R.string.browser_default_progress)
        private val wait: String get() = string(R.string.browser_wait)
        private val stopCd: String get() = string(R.string.browser_stop_cd)
        private val backToChat: String get() = string(R.string.browser_back_to_chat)
        private val serverNotEnabled: String get() = string(R.string.browser_server_not_enabled)
        private val registrationFailed: String get() = string(R.string.browser_registration_failed)
        private val autoOpenNotice: String get() = string(R.string.browser_auto_open_notice)
        private val open: String get() = string(R.string.download_open)
        private val share: String get() = string(R.string.download_share)
        private val sendToHermes: String get() = string(R.string.download_send_to_hermes)
        private val sendHint: String get() = string(R.string.download_send_hint)
        private val dismissCd: String get() = string(R.string.download_dismiss_cd)

        /** Documento de ejemplo para la hoja (ficticio, §7.2). */
        private val doc =
            DownloadedDoc(
                fileName = "factura-mama.pdf",
                mimeType = "application/pdf",
                sizeBytes = 4_096,
                contentUri = "content://media/external/downloads/7",
            )

        @JvmStatic
        @BeforeClass
        fun enableAccessibilityChecks() {
            AccessibilityChecks
                .enable()
                .setRunChecksFromRootView(true)
                .setThrowExceptionFor(AccessibilityCheckResult.AccessibilityCheckResultType.ERROR)
        }
    }
}
