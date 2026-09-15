package ai.hermes.mama.feature.browser

import ai.hermes.mama.contract.RpcMethods
import ai.hermes.mama.contract.SessionCreateParams
import ai.hermes.mama.core.controller.ControllerSession
import ai.hermes.mama.core.controller.ControllerSignal
import ai.hermes.mama.core.controller.ControllerState
import ai.hermes.mama.core.controller.WebViewController
import ai.hermes.mama.core.ui.theme.MamaTheme
import ai.hermes.mama.gateway.GatewayClient
import ai.hermes.mama.gateway.JsonRpcChannel
import ai.hermes.mama.gateway.WebSocketTransport
import ai.hermes.mama.gateway.createSession
import ai.hermes.mama.gateway.submitPrompt
import ai.hermes.mama.testing.FakeGateway
import ai.hermes.mama.testing.FakeGatewayScript
import ai.hermes.mama.testing.FakeIdentity
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.accessibility.AccessibilityChecks
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/**
 * Flujo E2E instrumentado de F4 (ROADMAP §5/F4) en emulador/dispositivo real:
 * la cadena entera — `WebSocketTransport` → `JsonRpcChannel` → `GatewayClient`
 * → `ControllerSession` (auto-registro + señal) → `BrowserPaneController` →
 * pantalla Compose → `WebView` real — contra el [FakeGateway] empotrado con el
 * guion `browser_find_invoice` ("buscar factura").
 *
 * Asserts del roadmap: el navegador se abre SOLO al primer `tool.start
 * browser_*`, tres comandos reales llegan al WebView servido por
 * [MockWebServer], el velo «Un momento…» cubre el comando en curso, **Parar**
 * emite `session.interrupt` y apaga el velo, y «Volver al chat» NO hace
 * `browser.controller.detach` (el controlador sigue ejecutando en segundo
 * plano hasta cerrar el chat).
 *
 * `AccessibilityChecks` (espresso → ATF) está activo: violaciones ERROR rompen
 * el test.
 */
@RunWith(AndroidJUnit4::class)
class BrowserScreenInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        // La página "lenta" nunca responde: el navigate queda
                        // en curso hasta que Parar lo cancele (§5/F4).
                        path.endsWith("/lenta.html") ->
                            MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                        else ->
                            MockResponse()
                                .setResponseCode(200)
                                .setHeader("Content-Type", "text/html; charset=utf-8")
                                .setBody(readAsset("orders.html"))
                    }
                }
            }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun buscarFactura_abreElNavegadorEjecutaComandosYPararInterrumpe() {
        // {{WEB}} del guion → base del MockWebServer (fixture orders.html).
        // Misma resolución que FakeGatewayScript.load: /scripts/ va en el
        // classpath del módulo :testing (processResources lo empaqueta).
        val scriptText =
            checkNotNull(
                FakeGatewayScript::class.java.getResource("/scripts/browser_find_invoice.json"),
            ) { "guion browser_find_invoice.json no empaquetado en :testing" }
                .readText()
                .replace("{{WEB}}", server.url("").toString().removeSuffix("/"))
        val gateway = FakeGateway(FakeGatewayScript.parse(scriptText)).start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var driver: AndroidWebViewDriver? = null
        var session: ControllerSession? = null
        var pane: BrowserPaneController? = null
        try {
            // /api/ws con ticket de un solo uso (§2.1.3): sin él la conexión
            // entra en modo dev con identidad NO autenticada y el fake —como
            // el real— rechaza browser.controller.* con 4403.
            val transport =
                WebSocketTransport(
                    "${gateway.wsUrl}?ticket=" +
                        gateway.auth.mintTicket(FakeIdentity("u_fake_usuario", "basic")),
                )
            runBlocking { transport.awaitOpen() }
            val channel = JsonRpcChannel(transport, scope)
            val client = GatewayClient(channel, scope)
            val clients = MutableSharedFlow<GatewayClient>(replay = 1)
            clients.tryEmit(client)

            val created =
                composeRule.runOnUiThread {
                    AndroidWebViewDriver(composeRule.activity)
                }
            driver = created
            val executor = WebViewController(created, scope)
            val controllerSession =
                ControllerSession(scope, clients, executor, CONTROLLER_ID).also { it.start() }
            session = controllerSession

            // La «navegación» de la app: la señal F3 abre el panel con aviso.
            val screenVisible = mutableStateOf(false)
            val autoOpened = mutableStateOf(false)
            val paneRef = AtomicReference<BrowserPaneController>()
            scope.launch {
                controllerSession.signals.collect { signal ->
                    if (signal is ControllerSignal.NavigateToBrowser) {
                        paneRef.set(
                            BrowserPaneController(
                                clients = clients,
                                session = controllerSession,
                                executor = executor,
                                sessionId = signal.sessionId,
                                scope = scope,
                                autoOpenNoticeMs = AUTO_OPEN_NOTICE_TEST_MS,
                            ),
                        )
                        autoOpened.value = true
                        screenVisible.value = true
                    }
                }
            }

            composeRule.setContent {
                MamaTheme {
                    // `screenVisible` se lee siempre durante la composición: si se
                    // escondiera detrás de un `paneRef.get() == null`, Compose no
                    // observaría el estado y la señal no recompondría el panel.
                    if (screenVisible.value) {
                        paneRef.get()?.let { controller ->
                            BrowserPane(
                                controller = controller,
                                driver = created,
                                onBackToChat = { screenVisible.value = false },
                                autoOpened = autoOpened.value,
                            )
                        }
                    }
                }
            }

            val sessionId =
                runBlocking {
                    client
                        .createSession(SessionCreateParams(title = "Test F4"))
                        .sessionId
                }
            runBlocking { client.submitPrompt(sessionId, "busca la factura del pedido") }

            // Auto-apertura al primer tool.start browser_* con su aviso (§5/F3).
            composeRule.waitUntil(WAIT_MS) { screenVisible.value }
            composeRule.waitUntil(WAIT_MS) {
                paneRef
                    .get()
                    ?.uiState
                    ?.value
                    ?.showAutoOpenNotice == true
            }
            composeRule.onNodeWithText(autoOpenNotice).assertIsDisplayed()

            // Tres comandos reales ejecutados contra el WebView: navigate
            // (carga orders.html), snapshot y click al botón «Descargar
            // factura» — cada uno entrega su browser.controller.result ok.
            composeRule.waitUntil(WAIT_MS) { gateway.browserCommandResults.size >= 3 }
            val actions = gateway.browserCommandResults.take(3).map { it.method }
            assertEquals(
                listOf("browser_navigate", "browser_snapshot", "browser_click"),
                actions,
            )
            gateway.browserCommandResults.take(3).forEach { answered ->
                assertNotNull(
                    "el comando ${answered.method} debe responder ok con result",
                    answered.result,
                )
            }
            val urlNow = runBlocking { created.currentUrl() }
            assertTrue(
                "el WebView cargó la fixture servida por MockWebServer",
                urlNow.orEmpty().endsWith("/orders.html"),
            )

            // El cuarto comando (navigate a /lenta.html) queda en curso:
            // el velo «Un momento…» se ve sobre el área web.
            composeRule.waitUntil(WAIT_MS) {
                paneRef
                    .get()
                    ?.uiState
                    ?.value
                    ?.commandInFlight == true
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithText(waitDescargo).assertIsDisplayed()
            composeRule.onNodeWithText(LAST_PROGRESS).assertIsDisplayed()

            // Accesibilidad de la pantalla con velo encendido (ATF nivel ERROR).
            runEspressoA11yCheck()

            // **Parar** → session.interrupt al servidor + el comando muere en
            // el acto: el velo se apaga sin esperar viajes de red.
            composeRule.onNodeWithContentDescription(stopCd).performClick()
            composeRule.waitUntil(WAIT_MS) { callsOf(gateway, RpcMethods.SESSION_INTERRUPT).isNotEmpty() }
            composeRule.waitUntil(WAIT_MS) {
                paneRef
                    .get()
                    ?.uiState
                    ?.value
                    ?.commandInFlight == false
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithText(wait, substring = true).assertDoesNotExist()

            // «Volver al chat»: la pantalla se desmonta pero el controlador
            // sigue registrado — nada de detach, y los comandos de un turno
            // nuevo siguen ejecutándose en segundo plano (§5/F4).
            composeRule.onNodeWithText(backToChat).performClick()
            composeRule.waitForIdle()
            assertEquals(false, screenVisible.value)
            assertEquals(ControllerState.Attached, controllerSession.state.value)
            val detachesBefore = callsOf(gateway, RpcMethods.BROWSER_CONTROLLER_DETACH).size
            assertEquals(0, detachesBefore)

            val resultsBefore = gateway.browserCommandResults.size
            runBlocking { client.submitPrompt(sessionId, "otra vez la factura") }
            composeRule.waitUntil(WAIT_MS) {
                gateway.browserCommandResults.size >= resultsBefore + 2
            }
            assertEquals(
                "sin detaches tras Volver al chat ni con el turno en segundo plano",
                0,
                callsOf(gateway, RpcMethods.BROWSER_CONTROLLER_DETACH).size,
            )
        } finally {
            runBlocking { session?.close() }
            driver?.let { created -> composeRule.runOnUiThread { created.destroy() } }
            scope.cancel()
            gateway.close()
        }
    }

    // ------------------------------------------------------------- helpers --

    private fun callsOf(
        gateway: FakeGateway,
        method: String,
    ) = gateway.receivedCalls.filter { it["method"]?.jsonPrimitive?.contentOrNull == method }

    private fun readAsset(name: String): String =
        androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation()
            .context.assets
            .open(name)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

    /** Evalúa la raíz con ATF: violaciones de nivel ERROR rompen el test. */
    private fun runEspressoA11yCheck() {
        onView(isRoot()).check(matches(isDisplayed()))
    }

    private companion object {
        const val CONTROLLER_ID = "android-f4-instrumented"
        const val WAIT_MS = 20_000L
        const val AUTO_OPEN_NOTICE_TEST_MS = 8_000L

        private fun string(id: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(id)

        private val wait: String get() = string(R.string.browser_wait)
        private val waitDescargo: String get() = "$wait $LAST_PROGRESS"
        private val stopCd: String get() = string(R.string.browser_stop_cd)
        private val backToChat: String get() = string(R.string.browser_back_to_chat)
        private val autoOpenNotice: String get() = string(R.string.browser_auto_open_notice)

        /** Último `browser.progress` del guion (el que sigue al 4º comando). */
        private const val LAST_PROGRESS = "Descargo la factura"

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
