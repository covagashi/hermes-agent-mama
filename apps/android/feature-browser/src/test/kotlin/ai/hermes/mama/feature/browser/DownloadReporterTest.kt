package ai.hermes.mama.feature.browser

import ai.hermes.mama.contract.RpcMethods
import ai.hermes.mama.core.controller.BrowserCommand
import ai.hermes.mama.core.controller.WebViewController
import ai.hermes.mama.core.controller.WebViewDriver
import ai.hermes.mama.core.controller.WebViewEventBus
import ai.hermes.mama.core.controller.WebViewPageEvent
import ai.hermes.mama.core.controller.WebViewScreenshot
import ai.hermes.mama.gateway.GatewayClient
import ai.hermes.mama.gateway.JsonRpcChannel
import ai.hermes.mama.gateway.WebSocketTransport
import ai.hermes.mama.gateway.createSession
import ai.hermes.mama.testing.FakeGateway
import ai.hermes.mama.testing.FakeGatewayScript
import ai.hermes.mama.testing.FakeIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * [DownloadReporter] contra [FakeGateway] por sockets reales (§5/G1):
 * - el `prompt.submit` sale con `display_kind:"system"` y lleva el aviso
 *   visible en español + la línea del modelo en inglés;
 * - con un comando §2.6 en curso, la nota del modelo viaja también en su
 *   `browser.controller.result` como `"notes":[…]`;
 * - sin client conectado el aviso se anota y nada se rompe.
 *
 * Misma pila que `BrowserPaneControllerTest`: dispatchers reales, [awaitReal]
 * con reloj de verdad. El WebView es [ReporterWebView] (JVM puro).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Timeout(60)
class DownloadReporterTest {
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

    private fun okHttp(): okhttp3.OkHttpClient =
        WebSocketTransport.defaultClient(okhttp3.CookieJar.NO_COOKIES).also(httpClients::add)

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

    @Suppress("InjectDispatcher")
    private fun TestScope.realScope(): CoroutineScope =
        CoroutineScope(backgroundScope.coroutineContext + Dispatchers.Default)

    @Suppress("InjectDispatcher")
    private suspend fun <T> awaitReal(
        timeoutMillis: Long = REAL_WAIT_MS,
        block: suspend CoroutineScope.() -> T,
    ): T = withContext(Dispatchers.IO) { withTimeout(timeoutMillis) { block() } }

    private fun submitCalls(gateway: FakeGateway) =
        gateway.receivedCalls.filter {
            it["method"]?.jsonPrimitive?.contentOrNull == RpcMethods.PROMPT_SUBMIT
        }

    private val doc =
        DownloadedDoc(
            fileName = "factura-mama.pdf",
            mimeType = "application/pdf",
            sizeBytes = 4_096,
            contentUri = "content://media/external/downloads/7",
        )

    // ---------------------------------------------------------------- tests

    @Test
    fun `descarga terminada manda prompt_submit system con aviso y nota`() =
        runTest {
            val gateway = startGateway(SCRIPT_ECHO)
            val (client, clients) = connect(gateway)
            val sessionId = awaitReal { client.createSession("Prueba G1") }.sessionId
            val executor = WebViewController(ReporterWebView(), realScope(), timeouts = TIMEOUTS)
            val reporter =
                DownloadReporter(clients, sessionId, executor, realScope(), logger = { println("REP: $it") })
            reporter.start()
            awaitReal { delay(150) } // el colector de clients aterriza

            reporter.onDownloadSaved(doc)

            awaitReal {
                while (submitCalls(gateway).isEmpty()) {
                    delay(POLL_MS)
                }
            }
            val params = submitCalls(gateway).single()["params"]?.jsonObject
            assertNotNull(params)
            assertEquals(sessionId, params["session_id"]?.jsonPrimitive?.contentOrNull)
            assertEquals("system", params["display_kind"]?.jsonPrimitive?.contentOrNull)
            val text = params["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
            assertTrue(text.contains("📄 Se ha guardado *factura-mama.pdf* en Descargas"))
            assertTrue(
                text.contains(
                    "Downloaded file saved on the user's phone: factura-mama.pdf (application/pdf, 4 KB)",
                ),
            )
            // Sin comando en curso: la nota no se queda a medias en la cola —
            // el siguiente comando NO la hereda.
            val outcome = executor.execute(BrowserCommand.Noop("noop-1"))
            assertTrue(!outcome.resultJson.contains("\"notes\""), "sin notas colgadas: $outcome")
        }

    @Test
    fun `con comando en curso la nota viaja en su resultado`() =
        runTest {
            val gateway = startGateway(SCRIPT_ECHO)
            val (client, clients) = connect(gateway)
            val sessionId = awaitReal { client.createSession("Prueba G1") }.sessionId
            val webView = ReporterWebView()
            val executor = WebViewController(webView, realScope(), timeouts = TIMEOUTS)
            val reporter =
                DownloadReporter(clients, sessionId, executor, realScope(), logger = { println("REP: $it") })
            reporter.start()

            // «lenta» en ReporterWebView: PageStarted sin PageFinished — el
            // comando queda en vuelo navSettleMs.
            val outcome =
                realScope().async {
                    executor.execute(BrowserCommand.Navigate("nav-lenta", "https://x/lenta"))
                }
            awaitReal { executor.busy.first { it } }
            awaitReal {
                while (webView.loadedUrls.lastOrNull()?.endsWith("/lenta") != true) {
                    delay(POLL_MS)
                }
            }

            reporter.onDownloadSaved(doc)

            val result = awaitReal { outcome.await() }
            val notes =
                Json
                    .parseToJsonElement(result.resultJson)
                    .jsonObject["notes"]
                    ?.jsonArray
            assertNotNull(notes, "el resultado del comando debe llevar notes: ${result.resultJson}")
            assertTrue(
                notes
                    .mapNotNull { it.jsonPrimitive.contentOrNull }
                    .any { it.contains("Downloaded file saved on the user's phone: factura-mama.pdf") },
            )
            // Y el aviso visible salió por prompt.submit igualmente.
            awaitReal {
                while (submitCalls(gateway).isEmpty()) {
                    delay(POLL_MS)
                }
            }
        }

    @Test
    fun `sin client conectado el aviso se anota y no rompe nada`() =
        runTest {
            val executor = WebViewController(ReporterWebView(), realScope(), timeouts = TIMEOUTS)
            val clients = MutableSharedFlow<GatewayClient>(replay = 1) // nunca emite
            val warnings = CopyOnWriteArrayList<String>()
            val reporter =
                DownloadReporter(clients, "sesion-x", executor, realScope(), logger = warnings::add)
            reporter.start()

            reporter.onDownloadSaved(doc)

            awaitReal { delay(150) }
            assertTrue(warnings.any { it.contains("sin gateway") })
        }

    // ----------------------------------------------------------------- fake

    /**
     * [WebViewDriver] de mentira: `loadUrl` emite `PageStarted` siempre y
     * `PageFinished` enseguida salvo en URLs con «lenta» — que quedan cargando
     * hasta el fin de `navSettleMs` (el comando sigue en vuelo).
     */
    private class ReporterWebView : WebViewDriver {
        val bus = WebViewEventBus()
        val loadedUrls = CopyOnWriteArrayList<String>()

        override fun pageEventsSince(): Flow<WebViewPageEvent> = bus.since()

        override suspend fun evaluateJavascript(script: String): String =
            when {
                script.contains("__hermes.snapshot") ->
                    jsResult("""{"success":true,"snapshot":"- text \"x\"","element_count":1}""")
                script.startsWith("window.__hermes.") -> jsResult("""{"success":true}""")
                else -> "null"
            }

        override suspend fun loadUrl(url: String) {
            loadedUrls += url
            bus.emit { seq -> WebViewPageEvent.PageStarted(seq, url) }
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
        const val REAL_WAIT_MS = 15_000L
        const val POLL_MS = 25L

        /** Delays reales del executor: ventanas cortas pero de verdad. */
        val TIMEOUTS =
            WebViewController.Timeouts(
                commandMs = 8_000,
                navSettleMs = 600,
                networkCalmMs = 60,
                actionSettleMs = 40,
                actionNavPeekMs = 80,
            )

        /**
         * Guion mínimo: el `prompt.submit` de sistema no casa ningún `when` —
         * `default_turn` lo absorbe con un complete limpio.
         */
        val SCRIPT_ECHO =
            """
            {
              "name": "g1_reporter",
              "turns": [],
              "default_turn": {
                "steps": [
                  { "event": "message.start" },
                  { "event": "message.complete", "payload": { "status": "complete" } }
                ]
              }
            }
            """.trimIndent()
    }
}
