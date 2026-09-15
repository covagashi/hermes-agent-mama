package ai.hermes.mama.feature.browser

import ai.hermes.mama.contract.RpcMethods
import ai.hermes.mama.core.controller.ControllerSession
import ai.hermes.mama.core.controller.ControllerState
import ai.hermes.mama.core.controller.WebViewController
import ai.hermes.mama.core.controller.WebViewDriver
import ai.hermes.mama.core.controller.WebViewEventBus
import ai.hermes.mama.core.controller.WebViewPageEvent
import ai.hermes.mama.core.controller.WebViewScreenshot
import ai.hermes.mama.gateway.GatewayClient
import ai.hermes.mama.gateway.JsonRpcChannel
import ai.hermes.mama.gateway.WebSocketTransport
import ai.hermes.mama.gateway.createSession
import ai.hermes.mama.gateway.submitPrompt
import ai.hermes.mama.testing.FakeGateway
import ai.hermes.mama.testing.FakeGatewayScript
import ai.hermes.mama.testing.FakeIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Integración de [BrowserPaneController] contra [FakeGateway] por sockets
 * reales (ROADMAP §5/F4): `start` pide el controlador, `browser.progress`
 * alimenta la barra, un comando en curso enciende `commandInFlight` (velo
 * «Un momento…»), **Parar** manda `session.interrupt` + cancela el comando y
 * `stop` suelta los colectores SIN hacer `browser.controller.detach`
 * (el controlador sigue registrado hasta cerrar el chat).
 *
 * Los delays son reales (sockets de verdad): [realScope] da dispatchers reales
 * y [awaitReal] espera con reloj de verdad, como `ControllerSessionGatewayTest`.
 * El WebView es [PaneWebView] (JVM puro): `loadUrl` emite `PageFinished` en el
 * acto salvo en las URLs "lenta", que quedan cargando para ejercitar el velo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Timeout(60)
class BrowserPaneControllerTest {
    private val gateways = CopyOnWriteArrayList<FakeGateway>()
    private val httpClients = CopyOnWriteArrayList<okhttp3.OkHttpClient>()

    @AfterEach
    fun tearDown() {
        gateways.forEach { it.close() }
        httpClients.forEach {
            it.dispatcher.executorService.shutdown()
            it.connectionPool.evictAll()
        }
    }

    // -------------------------------------------------------------- harness

    private fun startGateway(script: String): FakeGateway =
        FakeGateway(FakeGatewayScript.parse(script), logger = { println("GW: $it") }).start().also(gateways::add)

    private fun startGateway(script: FakeGatewayScript): FakeGateway =
        FakeGateway(script, logger = { println("GW: $it") }).start().also(gateways::add)

    private fun okHttp(): okhttp3.OkHttpClient =
        WebSocketTransport.defaultClient(okhttp3.CookieJar.NO_COOKIES).also(httpClients::add)

    /**
     * Cliente directo (sin ConnectionManager: el panel no reconecta, eso es de
     * B2) publicado en un `MutableSharedFlow(replay = 1)` — la misma forma que
     * la app alimentará (un client por generación de conexión, F3).
     */
    private suspend fun TestScope.connect(
        gateway: FakeGateway,
    ): Pair<GatewayClient, MutableSharedFlow<GatewayClient>> {
        val ticket = gateway.auth.mintTicket(FakeIdentity("u_fake_usuario", "basic"))
        val transport = WebSocketTransport("${gateway.wsUrl}?ticket=$ticket", okHttpClient = okHttp())
        awaitReal { transport.awaitOpen() }
        val channel =
            JsonRpcChannel(
                transport,
                realScope(),
                heartbeatInterval = Duration.INFINITE,
                logger = { println("CH: $it") },
            )
        val client = GatewayClient(channel, realScope())
        val clients = MutableSharedFlow<GatewayClient>(replay = 1)
        clients.tryEmit(client)
        return client to clients
    }

    private fun TestScope.newPane(
        clients: Flow<GatewayClient>,
        sessionId: String,
        webView: PaneWebView = PaneWebView(),
        autoOpenNoticeMs: Long = 120,
    ): Triple<BrowserPaneController, ControllerSession, WebViewController> {
        val executor = WebViewController(webView, realScope(), timeouts = TIMEOUTS)
        val session =
            ControllerSession(realScope(), clients, executor, CONTROLLER_ID)
                .also { it.start() }
        val pane =
            BrowserPaneController(
                clients = clients,
                session = session,
                executor = executor,
                sessionId = sessionId,
                scope = realScope(),
                autoOpenNoticeMs = autoOpenNoticeMs,
                logger = { println("PANE: $it") },
            )
        return Triple(pane, session, executor)
    }

    @Suppress("InjectDispatcher")
    private fun TestScope.realScope(): CoroutineScope =
        CoroutineScope(backgroundScope.coroutineContext + Dispatchers.Default)

    @Suppress("InjectDispatcher")
    private suspend fun <T> awaitReal(
        timeoutMillis: Long = REAL_WAIT_MS,
        block: suspend CoroutineScope.() -> T,
    ): T = withContext(Dispatchers.IO) { withTimeout(timeoutMillis) { block() } }

    private suspend fun awaitCalls(
        gateway: FakeGateway,
        method: String,
        min: Int = 1,
    ) = awaitReal {
        while (gateway.receivedCalls.count { it["method"]?.jsonPrimitive?.contentOrNull == method } < min) {
            delay(POLL_MS)
        }
    }

    private fun callsOf(
        gateway: FakeGateway,
        method: String,
    ) = gateway.receivedCalls.filter { it["method"]?.jsonPrimitive?.contentOrNull == method }

    // ---------------------------------------------------------------- tests

    @Test
    fun `start pide el controlador y la fase pasa a Browsing`() =
        runTest {
            val gateway = startGateway(SCRIPT_PANE)
            val (client, clients) = connect(gateway)
            val sessionId = awaitReal { client.createSession("Prueba") }.sessionId
            val (pane, session) = newPane(clients, sessionId).let { it.first to it.second }

            pane.start()
            awaitReal { session.state.first { it is ControllerState.Attached } }
            awaitReal { pane.uiState.first { it.phase == BrowserPhase.Browsing } }
            assertEquals(1, callsOf(gateway, RpcMethods.BROWSER_CONTROLLER_REGISTER).size)
            awaitReal { session.close() }
        }

    @Test
    fun `browser progress de la sesion alimenta la barra superior`() =
        runTest {
            val gateway = startGateway(SCRIPT_PANE)
            val (client, clients) = connect(gateway)
            val sessionId = awaitReal { client.createSession("Prueba") }.sessionId
            val (pane, session) = newPane(clients, sessionId).let { it.first to it.second }
            pane.start()
            awaitReal { session.state.first { it is ControllerState.Attached } }

            awaitReal { client.submitPrompt(sessionId, "busca mi factura") }
            awaitReal {
                pane.uiState.first { it.progressMessage == "Voy a pulsar «Descargar factura»" }
            }
            awaitReal { session.close() }
        }

    @Test
    fun `comando en curso enciende el velo y Parar interrumpe la sesion`() =
        runTest {
            val gateway = startGateway(SCRIPT_PANE)
            val (client, clients) = connect(gateway)
            val webView = PaneWebView()
            val sessionId = awaitReal { client.createSession("Prueba") }.sessionId
            val (pane, session) = newPane(clients, sessionId, webView).let { it.first to it.second }
            pane.start()
            awaitReal { session.state.first { it is ControllerState.Attached } }

            awaitReal { client.submitPrompt(sessionId, "busca mi factura") }
            // Primer comando (navigate a /pedidos) entrega su resultado; el
            // segundo (navigate a /lenta) queda en curso → velo encendido.
            awaitReal {
                while (gateway.browserCommandResults.isEmpty()) {
                    delay(POLL_MS)
                }
            }
            awaitReal { pane.uiState.first { it.commandInFlight } }
            // busy sube antes de que el comando llegue a loadUrl (salto de
            // dispatcher): esperar a que la navegación lenta aterrice.
            awaitReal {
                while (webView.loadedUrls.lastOrNull()?.endsWith("/lenta") != true) {
                    delay(POLL_MS)
                }
            }
            assertTrue(
                webView.loadedUrls.last().endsWith("/lenta"),
                "el comando en curso es la navegación lenta",
            )

            pane.stopHermes()

            awaitCalls(gateway, RpcMethods.SESSION_INTERRUPT)
            awaitReal { pane.uiState.first { !it.commandInFlight } }
            awaitReal { session.close() }
        }

    @Test
    fun `registro 4403 muestra ServerNotEnabled`() =
        runTest {
            val gateway = startGateway(FakeGatewayScript.load("browser_off"))
            val (client, clients) = connect(gateway)
            val sessionId = awaitReal { client.createSession("Prueba") }.sessionId
            val (pane, session) = newPane(clients, sessionId).let { it.first to it.second }

            pane.start()
            awaitReal { pane.uiState.first { it.phase == BrowserPhase.ServerNotEnabled } }
            awaitReal { session.close() }
        }

    @Test
    fun `stop suelta los colectores sin detach y la pantalla puede reabrirse`() =
        runTest {
            val gateway = startGateway(SCRIPT_PANE)
            val (client, clients) = connect(gateway)
            val sessionId = awaitReal { client.createSession("Prueba") }.sessionId
            val (pane, session) = newPane(clients, sessionId).let { it.first to it.second }
            pane.start()
            awaitReal { session.state.first { it is ControllerState.Attached } }

            pane.stop()
            // El registro sigue vivo: ni detach ni cambio de fase (§5/F4).
            awaitReal { delay(300) }
            assertTrue(callsOf(gateway, RpcMethods.BROWSER_CONTROLLER_DETACH).isEmpty())
            assertIs<ControllerState.Attached>(session.state.value)

            // Reabrir: attach es idempotente, no sale un segundo register.
            pane.start()
            awaitReal { pane.uiState.first { it.phase == BrowserPhase.Browsing } }
            awaitReal { delay(200) }
            assertEquals(1, callsOf(gateway, RpcMethods.BROWSER_CONTROLLER_REGISTER).size)
            awaitReal { session.close() }
        }

    @Test
    fun `el aviso de autoapertura se muestra y se apaga solo`() =
        runTest {
            val gateway = startGateway(SCRIPT_PANE)
            val (client, clients) = connect(gateway)
            val sessionId = awaitReal { client.createSession("Prueba") }.sessionId
            val (pane, session) =
                newPane(clients, sessionId, autoOpenNoticeMs = 80).let { it.first to it.second }

            pane.start(autoOpenNotice = true)
            awaitReal { pane.uiState.first { it.showAutoOpenNotice } }
            awaitReal { pane.uiState.first { !it.showAutoOpenNotice } }
            awaitReal { session.close() }
        }

    @Test
    fun `el aviso solo sale cuando se pidio autoOpenNotice`() =
        runTest {
            val gateway = startGateway(SCRIPT_PANE)
            val (client, clients) = connect(gateway)
            val sessionId = awaitReal { client.createSession("Prueba") }.sessionId
            val (pane, session) =
                newPane(clients, sessionId, autoOpenNoticeMs = 60).let { it.first to it.second }

            pane.start()
            awaitReal { delay(200) }
            assertEquals(false, pane.uiState.value.showAutoOpenNotice)
            awaitReal { session.close() }
        }

    // ----------------------------------------------------------------- fake

    /**
     * [WebViewDriver] de mentira para los tests del panel: `loadUrl` emite
     * `PageFinished` enseguida (navegación instantánea) salvo en las URLs que
     * llevan "lenta", que quedan cargando hasta que las cancelen — el caso del
     * velo + Parar.
     */
    private class PaneWebView : WebViewDriver {
        val bus = WebViewEventBus()
        val loadedUrls = CopyOnWriteArrayList<String>()

        override fun pageEventsSince(): Flow<WebViewPageEvent> = bus.since()

        override suspend fun evaluateJavascript(script: String): String =
            when {
                script.contains("__hermes.snapshot") ->
                    jsResult("""{"success":true,"snapshot":"- text \"Pedido\"","element_count":1}""")
                script.startsWith("window.__hermes.") -> jsResult("""{"success":true}""")
                else -> "null"
            }

        override suspend fun loadUrl(url: String) {
            loadedUrls += url
            if (!url.contains("lenta")) {
                bus.emit { seq -> WebViewPageEvent.PageFinished(seq, url) }
            }
        }

        override suspend fun goBack() = Unit

        override suspend fun canGoBack(): Boolean = false

        override suspend fun currentUrl(): String? = loadedUrls.lastOrNull()

        override suspend fun currentTitle(): String? = "Tienda Ejemplo"

        override suspend fun capturePng(maxDimPx: Int): WebViewScreenshot =
            WebViewScreenshot(byteArrayOf(1, 2, 3), width = 1, height = 1)

        companion object {
            fun jsResult(innerJson: String): String = JsonPrimitive(innerJson).toString()
        }
    }

    private companion object {
        const val CONTROLLER_ID = "android-test-f4"
        const val REAL_WAIT_MS = 15_000L
        const val POLL_MS = 25L

        /** Los delays del executor van a reloj real: ventanas cortas. */
        val TIMEOUTS =
            WebViewController.Timeouts(
                commandMs = 8_000,
                navSettleMs = 8_000,
                networkCalmMs = 60,
                actionSettleMs = 40,
                actionNavPeekMs = 80,
            )

        /**
         * Guion del panel: progreso + navigate ok + progreso + navigate que se
         * queda cargando (la fake no emite PageFinished para "/lenta") para que
         * el velo quede encendido hasta Parar.
         */
        val SCRIPT_PANE =
            """
            {
              "name": "f4_pane",
              "turns": [
                { "when": { "text_contains": "factura" },
                  "steps": [
                    { "event": "message.start" },
                    { "event": "browser.progress",
                      "payload": { "message": "Abro la web de la tienda", "level": "info" } },
                    { "browser_command": { "action": "browser_navigate",
                        "arguments": { "url": "https://tienda.example.invalid/pedidos" } } },
                    { "event": "browser.progress",
                      "payload": { "message": "Voy a pulsar «Descargar factura»", "level": "info" } },
                    { "browser_command": { "action": "browser_navigate",
                        "arguments": { "url": "https://tienda.example.invalid/lenta" } } },
                    { "event": "message.complete", "payload": { "status": "complete" } }
                  ] }
              ]
            }
            """.trimIndent()
    }
}
