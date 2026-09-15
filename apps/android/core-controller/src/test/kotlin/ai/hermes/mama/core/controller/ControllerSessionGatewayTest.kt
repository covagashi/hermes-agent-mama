package ai.hermes.mama.core.controller

import ai.hermes.mama.contract.RpcMethods
import ai.hermes.mama.gateway.ChannelFactory
import ai.hermes.mama.gateway.ConnectParams
import ai.hermes.mama.gateway.ConnectionManager
import ai.hermes.mama.gateway.ConnectionState
import ai.hermes.mama.gateway.GatewayClient
import ai.hermes.mama.gateway.JsonRpcChannel
import ai.hermes.mama.gateway.ReconnectConfig
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
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Integración de [ControllerSession] contra [FakeGateway] por sockets reales
 * (ROADMAP §5/F3, §2.6): registro con capabilities exactas, rechazo 4403,
 * re-registro tras un corte de socket, roundtrip comando→resultado (los campos
 * `result`/`error` del wire), auto-registro por `tool.start` `browser_*` y
 * `browser.controller.detach`.
 *
 * Los delays son reales (sockets de verdad): [realScope] da dispatchers reales
 * al ciclo del manager/sesión y [awaitReal] espera con reloj de verdad, como en
 * `ConnectionManagerTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Timeout(60)
class ControllerSessionGatewayTest {
    private val gateways = CopyOnWriteArrayList<FakeGateway>()
    private val httpClients = CopyOnWriteArrayList<OkHttpClient>()

    @AfterEach
    fun tearDown() {
        // Los ConnectionManager viven sobre realScope() (hijo de backgroundScope):
        // mueren con el test; los sockets se apagan con el cliente OkHttp.
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

    private fun okHttp(): OkHttpClient = WebSocketTransport.defaultClient(CookieJar.NO_COOKIES).also(httpClients::add)

    /** Manager real con tickets frescos por intento (como hará C8) y backoff corto. */
    private fun TestScope.newManager(gateway: FakeGateway): ConnectionManager =
        ConnectionManager(
            scope = realScope(),
            transportFactory = WebSocketTransport.factory(okHttp(), logger = { println("WS: $it") }),
            onBeforeConnect = {
                ConnectParams(
                    "${gateway.wsUrl}?ticket=" +
                        gateway.auth.mintTicket(FakeIdentity("u_fake_usuario", "basic")),
                )
            },
            config =
                ReconnectConfig(
                    initialDelay = 50.milliseconds,
                    maxDelay = 200.milliseconds,
                    jitterFraction = 0.0,
                ),
            channelFactory =
                ChannelFactory { transport, scope, onDead ->
                    JsonRpcChannel(
                        transport = transport,
                        scope = scope,
                        heartbeatInterval = Duration.INFINITE,
                        onDead = onDead,
                    )
                },
        )

    /**
     * El Flow que la app alimentará (C8): un `GatewayClient` por generación de
     * conexión — `shareIn` para que la sesión y el test vean el MISMO client.
     */
    private fun TestScope.clientFlow(manager: ConnectionManager): SharedFlow<GatewayClient> =
        manager.state
            .mapNotNull { (it as? ConnectionState.Connected)?.channel }
            .distinctUntilChanged()
            .map { channel -> GatewayClient(channel, realScope()) }
            .shareIn(realScope(), started = SharingStarted.Eagerly, replay = 1)

    private fun TestScope.newControllerSession(
        clients: SharedFlow<GatewayClient>,
        heartbeat: Duration = ControllerSession.DEFAULT_HEARTBEAT_INTERVAL,
    ): ControllerSession =
        ControllerSession(
            scope = realScope(),
            clients = clients,
            executor =
                WebViewController(
                    FakeWebView(),
                    realScope(),
                    timeouts =
                        WebViewController.Timeouts(
                            commandMs = 5_000,
                            navSettleMs = 800,
                            networkCalmMs = 80,
                            actionSettleMs = 40,
                            actionNavPeekMs = 80,
                        ),
                ),
            controllerId = CONTROLLER_ID,
            heartbeatInterval = heartbeat,
        )

    /** Scope con dispatcher real (delays reales) atado al ciclo del test. */
    @Suppress("InjectDispatcher")
    private fun TestScope.realScope(): CoroutineScope =
        CoroutineScope(backgroundScope.coroutineContext + Dispatchers.Default)

    /** Espera real sobre IO de verdad (sockets); el timeout es de reloj, no virtual. */
    @Suppress("InjectDispatcher")
    private suspend fun <T> awaitReal(
        timeoutMillis: Long = REAL_WAIT_MS,
        block: suspend CoroutineScope.() -> T,
    ): T = withContext(Dispatchers.IO) { withTimeout(timeoutMillis) { block() } }

    /** Espera hasta que [method] haya llegado al menos [min] veces al FakeGateway. */
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
    fun `registro manda session_id, ids y capabilities exactas con protocolo 1`() =
        runTest {
            val gateway = startGateway(FakeGatewayScript.load("browser"))
            val manager = newManager(gateway)
            val clients = clientFlow(manager)
            val session = newControllerSession(clients)
            session.start()
            manager.connect()

            val client = awaitReal { clients.first() }
            val sessionId = awaitReal { client.createSession("Prueba") }.sessionId
            session.attach(sessionId)
            awaitReal { session.state.first { it is ControllerState.Attached } }

            val params =
                callsOf(gateway, RpcMethods.BROWSER_CONTROLLER_REGISTER)
                    .single()
                    .getValue("params")
                    .jsonObject
            assertEquals(sessionId, params.getValue("session_id").jsonPrimitive.content)
            assertEquals(CONTROLLER_ID, params.getValue("controller_id").jsonPrimitive.content)
            assertEquals(
                ControllerSession.DEFAULT_BROWSER_PROFILE_ID,
                params.getValue("browser_profile_id").jsonPrimitive.content,
            )
            assertEquals(JsonPrimitive(1), params.getValue("protocol_version"))
            val capabilities = params.getValue("capabilities").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(BrowserCommand.Actions.CAPABILITIES, capabilities)
            assertTrue(
                capabilities.none {
                    it.startsWith("browser_artifact") || it == "browser_cdp" || it == "browser_evaluate"
                },
                "ni artefactos ni CDP/evaluate: el serve no los da (§5/F3)",
            )
            awaitReal { session.close() }
        }

    @Test
    fun `registro con el flag apagado expone ServerNotEnabled`() =
        runTest {
            val gateway = startGateway(FakeGatewayScript.load("browser_off"))
            val manager = newManager(gateway)
            val clients = clientFlow(manager)
            val session = newControllerSession(clients)
            session.start()
            manager.connect()

            val client = awaitReal { clients.first() }
            val sessionId = awaitReal { client.createSession("Prueba") }.sessionId
            session.attach(sessionId)

            awaitReal { session.state.first { it is ControllerState.ServerNotEnabled } }
            assertEquals(1, callsOf(gateway, RpcMethods.BROWSER_CONTROLLER_REGISTER).size)
            awaitReal { session.close() }
        }

    @Test
    fun `un corte de socket re-registra el mismo controlador en la nueva conexion`() =
        runTest {
            val gateway =
                startGateway(
                    """
                    {
                      "name": "f3_corte",
                      "turns": [
                        { "steps": [ { "close_socket": { "code": 1000, "reason": "corte" } } ] }
                      ]
                    }
                    """.trimIndent(),
                )
            val manager = newManager(gateway)
            val clients = clientFlow(manager)
            val session = newControllerSession(clients)
            session.start()
            manager.connect()

            val client = awaitReal { clients.first() }
            val sessionId = awaitReal { client.createSession("Prueba") }.sessionId
            session.attach(sessionId)
            awaitReal { session.state.first { it is ControllerState.Attached } }

            // El turno cierra el socket del que vino el submit: la respuesta del
            // RPC puede perderse — lo que importa es el corte, no el resultado.
            awaitReal { runCatching { client.submitPrompt(sessionId, "mata el socket") } }
            awaitCalls(gateway, RpcMethods.BROWSER_CONTROLLER_REGISTER, min = 2)
            awaitReal { session.state.first { it is ControllerState.Attached } }

            val registers = callsOf(gateway, RpcMethods.BROWSER_CONTROLLER_REGISTER)
            assertEquals(
                registers
                    .map {
                        it
                            .getValue("params")
                            .jsonObject
                            .getValue("controller_id")
                            .jsonPrimitive.content to
                            it
                                .getValue("params")
                                .jsonObject
                                .getValue("session_id")
                                .jsonPrimitive.content
                    }.toSet()
                    .single(),
                CONTROLLER_ID to sessionId,
                "mismo controlador y sesión en cada generación",
            )
            awaitReal { session.close() }
        }

    @Test
    fun `comando ok responde en result y el fallo en error`() =
        runTest {
            val gateway =
                startGateway(
                    """
                    {
                      "name": "f3_comandos",
                      "turns": [
                        { "steps": [
                            { "event": "message.start" },
                            { "browser_command": { "action": "browser_tabs" } },
                            { "browser_command": { "action": "browser_tab_activate", "arguments": { "id": "9" } } },
                            { "event": "message.complete", "payload": { "status": "complete" } }
                        ] }
                      ]
                    }
                    """.trimIndent(),
                )
            val manager = newManager(gateway)
            val clients = clientFlow(manager)
            val session = newControllerSession(clients)
            session.start()
            manager.connect()

            val client = awaitReal { clients.first() }
            val sessionId = awaitReal { client.createSession("Prueba") }.sessionId
            session.attach(sessionId)
            awaitReal { session.state.first { it is ControllerState.Attached } }

            awaitReal { client.submitPrompt(sessionId, "haz cosas de navegador") }
            awaitReal {
                while (gateway.browserCommandResults.size < 2) {
                    delay(POLL_MS)
                }
            }

            val ok = gateway.browserCommandResults[0]
            assertEquals("browser_tabs", ok.method)
            assertNull(ok.error, "comando con éxito no lleva params.error")
            val okResult = assertNotNull(ok.result)
            assertTrue(okResult.jsonPrimitive.content.contains("\"success\":true"))

            val fail = gateway.browserCommandResults[1]
            assertEquals("browser_tab_activate", fail.method)
            assertNull(fail.result, "el fallo viaja en params.error, no en params.result")
            val failError = assertNotNull(fail.error)
            assertTrue(failError.jsonPrimitive.content.contains("No tab found"))
            awaitReal { session.close() }
        }

    @Test
    fun `tool-start browser-star auto-registra, senala una vez y el heartbeat corre`() =
        runTest {
            val gateway =
                startGateway(
                    """
                    {
                      "name": "f3_auto",
                      "turns": [
                        { "steps": [
                            { "event": "message.start" },
                            { "event": "tool.start", "payload": { "tool_id": "t-1", "name": "browser_navigate" } },
                            { "sleep_ms": 500 },
                            { "event": "tool.start", "payload": { "tool_id": "t-2", "name": "browser_click" } },
                            { "event": "message.complete", "payload": { "status": "complete" } }
                        ] }
                      ]
                    }
                    """.trimIndent(),
                )
            val manager = newManager(gateway)
            val clients = clientFlow(manager)
            val session = newControllerSession(clients, heartbeat = 150.milliseconds)
            val signals = CopyOnWriteArrayList<ControllerSignal>()
            realScope().launch { session.signals.collect { signals += it } }
            session.start()
            manager.connect()

            val client = awaitReal { clients.first() }
            val sessionId = awaitReal { client.createSession("Prueba") }.sessionId
            awaitReal { client.submitPrompt(sessionId, "abre el navegador") }

            awaitCalls(gateway, RpcMethods.BROWSER_CONTROLLER_REGISTER)
            awaitReal { session.state.first { it is ControllerState.Attached } }
            awaitCalls(gateway, RpcMethods.BROWSER_CONTROLLER_HEARTBEAT, min = 2)
            awaitReal {
                while (signals.isEmpty()) {
                    delay(POLL_MS)
                }
                delay(600) // deja pasar el segundo tool.start: no debe repetir señal
            }

            assertEquals<List<ControllerSignal>>(
                listOf(ControllerSignal.NavigateToBrowser(sessionId)),
                signals,
            )
            assertEquals(1, callsOf(gateway, RpcMethods.BROWSER_CONTROLLER_REGISTER).size)
            awaitReal { session.close() }
        }

    @Test
    fun `detach envia browser-controller-detach y queda Idle`() =
        runTest {
            val gateway = startGateway(FakeGatewayScript.load("browser"))
            val manager = newManager(gateway)
            val clients = clientFlow(manager)
            val session = newControllerSession(clients)
            session.start()
            manager.connect()

            val client = awaitReal { clients.first() }
            val sessionId = awaitReal { client.createSession("Prueba") }.sessionId
            session.attach(sessionId)
            awaitReal { session.state.first { it is ControllerState.Attached } }

            awaitReal { session.detach() }
            awaitCalls(gateway, RpcMethods.BROWSER_CONTROLLER_DETACH)
            assertIs<ControllerState.Idle>(session.state.value)
            awaitReal { session.close() }
        }

    private companion object {
        const val CONTROLLER_ID = "android-test-f3"
        const val REAL_WAIT_MS = 15_000L
        const val POLL_MS = 25L
    }
}
