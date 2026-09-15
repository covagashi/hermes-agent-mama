package ai.hermes.mama.core.controller

import ai.hermes.mama.contract.EventTypes
import ai.hermes.mama.contract.RpcMethods
import ai.hermes.mama.gateway.GatewayClient
import ai.hermes.mama.gateway.JsonRpcChannel
import ai.hermes.mama.gateway.JsonRpcException
import ai.hermes.mama.gateway.Transport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Contrato de [ControllerSession] (ROADMAP §5/F3, §2.6) sobre transport en
 * memoria + reloj virtual: registro, heartbeat de 20 s, ruteo de
 * comandos/cancelaciones, señal de auto-navegación, errores y re-registro por
 * generación de client. La integración con sockets reales y FakeGateway está
 * en `ControllerSessionGatewayTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ControllerSessionTest {
    private val warnings = CopyOnWriteArrayList<String>()

    private fun TestScope.newHarness(heartbeat: Duration = ControllerSession.DEFAULT_HEARTBEAT_INTERVAL): Harness {
        val transport = ScriptedTransport()
        val channel = JsonRpcChannel(transport = transport, scope = backgroundScope)
        val client = GatewayClient(channel = channel, scope = backgroundScope)
        val webView = FakeWebView()
        val executor = WebViewController(webView, backgroundScope)
        val clients = MutableSharedFlow<GatewayClient>(replay = 1)
        val session =
            ControllerSession(
                scope = backgroundScope,
                clients = clients,
                executor = executor,
                controllerId = CONTROLLER_ID,
                heartbeatInterval = heartbeat,
                logger = warnings::add,
            )
        return Harness(transport, client, clients, webView, executor, session)
    }

    private class Harness(
        val transport: ScriptedTransport,
        val client: GatewayClient,
        val clients: MutableSharedFlow<GatewayClient>,
        val webView: FakeWebView,
        val executor: WebViewController,
        val session: ControllerSession,
    ) {
        fun start() {
            session.start()
            clients.tryEmit(client)
        }
    }

    // ------------------------------------------------------------- registro

    @Test
    fun `registro envia ids de controlador y perfil, protocolo 1 y las capabilities exactas`() =
        runTest {
            val h = newHarness()
            h.start()
            h.session.attach(SESSION_ID)
            runCurrent()

            assertIs<ControllerState.Attached>(h.session.state.value)
            val params =
                h.transport
                    .registerFrames()
                    .single()
                    .getValue("params")
                    .jsonObject
            assertEquals(SESSION_ID, params.getValue("session_id").jsonPrimitive.content)
            assertEquals(CONTROLLER_ID, params.getValue("controller_id").jsonPrimitive.content)
            assertEquals(
                ControllerSession.DEFAULT_BROWSER_PROFILE_ID,
                params.getValue("browser_profile_id").jsonPrimitive.content,
            )
            assertEquals(JsonPrimitive(1), params.getValue("protocol_version"))
            val capabilities = params.getValue("capabilities").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(BrowserCommand.Actions.CAPABILITIES, capabilities)
            // §5/F3: ni artefactos ni CDP/evaluate — `hermes serve` no los sirve.
            assertFalse(capabilities.any { it.startsWith("browser_artifact") })
            assertFalse(capabilities.contains("browser_cdp") || capabilities.contains("browser_evaluate"))
        }

    @Test
    fun `attach repetido sobre la misma sesion no duplica el registro`() =
        runTest {
            val h = newHarness()
            h.start()
            h.session.attach(SESSION_ID)
            h.session.attach(SESSION_ID)
            runCurrent()

            assertIs<ControllerState.Attached>(h.session.state.value)
            assertEquals(1, h.transport.registerFrames().size)
        }

    @Test
    fun `attach a otra sesion desliga la anterior con detach`() =
        runTest {
            val h = newHarness()
            h.start()
            h.session.attach(SESSION_ID)
            runCurrent()

            h.session.attach(OTHER_SESSION_ID)
            runCurrent()

            val detach =
                h.transport
                    .detachFrames()
                    .single()
                    .getValue("params")
                    .jsonObject
            assertEquals(SESSION_ID, detach.getValue("session_id").jsonPrimitive.content)
            val registers = h.transport.registerFrames()
            assertEquals(2, registers.size)
            assertEquals(
                OTHER_SESSION_ID,
                registers
                    .last()
                    .getValue("params")
                    .jsonObject
                    .getValue("session_id")
                    .jsonPrimitive.content,
            )
            assertIs<ControllerState.Attached>(h.session.state.value)
        }

    // ------------------------------------------------------------- heartbeat

    @Test
    fun `heartbeat sale cada 20 segundos mientras el registro esta vivo`() =
        runTest {
            val h = newHarness()
            h.start()
            h.session.attach(SESSION_ID)
            runCurrent()

            advanceTimeBy(19_999)
            runCurrent()
            assertEquals(0, h.transport.heartbeatFrames().size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(1, h.transport.heartbeatFrames().size)
            advanceTimeBy(20_000)
            runCurrent()
            assertEquals(2, h.transport.heartbeatFrames().size)
        }

    @Test
    fun `un heartbeat rechazado por el servidor no mata el bucle`() =
        runTest {
            val h = newHarness(heartbeat = 5.milliseconds)
            h.start()
            h.session.attach(SESSION_ID)
            runCurrent()

            h.transport.heartbeatError = JsonPrimitive("boom")
            advanceTimeBy(5)
            runCurrent()
            assertEquals(1, h.transport.heartbeatFrames().size)

            h.transport.heartbeatError = null
            advanceTimeBy(5)
            runCurrent()
            assertEquals(2, h.transport.heartbeatFrames().size)
            assertIs<ControllerState.Attached>(h.session.state.value)
        }

    // -------------------------------------------------------------- comandos

    @Test
    fun `result ok viaja en params-result y el fallo en params-error`() =
        runTest {
            val h = newHarness()
            h.start()
            h.session.attach(SESSION_ID)
            runCurrent()

            h.transport.emitCommand(SESSION_ID, commandId = "cmd-1", action = "browser_tabs")
            runCurrent()
            val okParams =
                h.transport
                    .resultFrames()
                    .single()
                    .getValue("params")
                    .jsonObject
            assertEquals("cmd-1", okParams.getValue("command_id").jsonPrimitive.content)
            assertTrue(okParams.getValue("ok").jsonPrimitive.boolean)
            assertTrue(
                okParams
                    .getValue("result")
                    .jsonPrimitive.content
                    .contains("\"success\":true"),
            )
            assertFalse("error" in okParams, "result ok no lleva params.error")

            h.transport.emitCommand(
                SESSION_ID,
                commandId = "cmd-2",
                action = "browser_tab_activate",
                arguments = """{"id":"9"}""",
            )
            runCurrent()
            val failParams =
                h.transport
                    .resultFrames()
                    .last()
                    .getValue("params")
                    .jsonObject
            assertEquals("cmd-2", failParams.getValue("command_id").jsonPrimitive.content)
            assertFalse(failParams.getValue("ok").jsonPrimitive.boolean)
            assertFalse("result" in failParams, "fallo no lleva params.result")
            assertTrue(
                failParams
                    .getValue("error")
                    .jsonPrimitive.content
                    .contains("No tab found"),
            )
        }

    @Test
    fun `comando de otra sesion, controlador o perfil no se ejecuta ni responde`() =
        runTest {
            val h = newHarness()
            h.start()
            h.session.attach(SESSION_ID)
            runCurrent()

            h.transport.emitCommand(
                OTHER_SESSION_ID,
                commandId = "cmd-aj-1",
                action = "browser_click",
                arguments = """{"ref":"@e5"}""",
            )
            h.transport.emitCommand(
                SESSION_ID,
                commandId = "cmd-aj-2",
                action = "browser_click",
                arguments = """{"ref":"@e5"}""",
                controllerId = "otro-controlador",
            )
            h.transport.emitCommand(
                SESSION_ID,
                commandId = "cmd-aj-3",
                action = "browser_click",
                arguments = """{"ref":"@e5"}""",
                browserProfileId = "otro-perfil",
            )
            h.transport.emitCommand(
                SESSION_ID,
                commandId = "cmd-propia",
                action = "browser_click",
                arguments = """{"ref":"@e5"}""",
            )
            // El click lleva 300 ms de settle + 500 ms de peek de navegación
            // (§5/F2): hay que adelantar el reloj virtual para ver el result.
            advanceTimeBy(1_000)
            runCurrent()

            val answered =
                h.transport.resultFrames().map {
                    it
                        .getValue("params")
                        .jsonObject
                        .getValue("command_id")
                        .jsonPrimitive.content
                }
            assertEquals(listOf("cmd-propia"), answered)
            assertEquals(1, h.webView.evaluatedScripts.count { it.startsWith("window.__hermes.click") })
        }

    @Test
    fun `browser-controller-cancel aborta el comando en vuelo`() =
        runTest {
            val h = newHarness()
            h.start()
            h.session.attach(SESSION_ID)
            runCurrent()

            h.transport.emitCommand(
                SESSION_ID,
                commandId = "cmd-nav",
                action = "browser_navigate",
                arguments = """{"url":"https://hermes.example.invalid/lenta"}""",
            )
            runCurrent()
            assertEquals(listOf("https://hermes.example.invalid/lenta"), h.webView.loadedUrls)

            h.transport.emitCancel(SESSION_ID, commandId = "cmd-nav")
            runCurrent()

            val params =
                h.transport
                    .resultFrames()
                    .single()
                    .getValue("params")
                    .jsonObject
            assertEquals("cmd-nav", params.getValue("command_id").jsonPrimitive.content)
            assertFalse(params.getValue("ok").jsonPrimitive.boolean)
            assertTrue(
                params
                    .getValue("error")
                    .jsonPrimitive.content
                    .contains("cancelled"),
            )
        }

    // ----------------------------------------------------------- auto-attach

    @Test
    fun `primer tool-start browser-star emite NavigateToBrowser y auto-registra una sola vez`() =
        runTest {
            val h = newHarness()
            val signals = mutableListOf<ControllerSignal>()
            backgroundScope.launch { h.session.signals.collect { signals += it } }
            h.start()
            runCurrent()

            h.transport.emitToolStart(SESSION_ID, name = "browser_navigate")
            runCurrent()

            assertEquals<List<ControllerSignal>>(
                listOf(ControllerSignal.NavigateToBrowser(SESSION_ID)),
                signals,
            )
            assertEquals(1, h.transport.registerFrames().size)
            assertIs<ControllerState.Attached>(h.session.state.value)

            // Ni otro browser_* ni un tool ajeno re-señalan ni re-registran.
            h.transport.emitToolStart(SESSION_ID, name = "browser_click")
            h.transport.emitToolStart(SESSION_ID, name = "web_search")
            runCurrent()
            assertEquals(1, signals.size)
            assertEquals(1, h.transport.registerFrames().size)
        }

    @Test
    fun `tool-start con el controlador ya pedido para otra sesion no re-senala ni re-registra`() =
        runTest {
            val h = newHarness()
            val signals = mutableListOf<ControllerSignal>()
            backgroundScope.launch { h.session.signals.collect { signals += it } }
            h.start()
            h.session.attach(SESSION_ID)
            runCurrent()

            // El controlador ya está ligado a SESSION_ID: un browser_* de otra
            // sesión no dispara ni señal ni registro (la app re-ligaría con attach).
            h.transport.emitToolStart(OTHER_SESSION_ID, name = "browser_navigate")
            runCurrent()

            assertTrue(signals.isEmpty())
            assertEquals(1, h.transport.registerFrames().size)
        }

    // ---------------------------------------------------------------- errores

    @Test
    fun `registro rechazado 4403 expone ServerNotEnabled sin heartbeat`() =
        runTest {
            val h = newHarness()
            h.transport.registerError =
                buildJsonObject {
                    put("code", 4403)
                    put("message", "browser.extension_control.enabled is not set")
                }
            h.start()
            h.session.attach(SESSION_ID)
            runCurrent()

            assertIs<ControllerState.ServerNotEnabled>(h.session.state.value)
            assertEquals(0, h.transport.heartbeatFrames().size)
        }

    @Test
    fun `otro error de registro expone RegistrationFailed y attach reintenta`() =
        runTest {
            val h = newHarness()
            h.transport.registerError =
                buildJsonObject {
                    put("code", -32000)
                    put("message", "boom")
                }
            h.start()
            h.session.attach(SESSION_ID)
            runCurrent()

            val failed = h.session.state.value
            assertIs<ControllerState.RegistrationFailed>(failed)
            assertIs<JsonRpcException>(failed.cause)
            assertEquals(-32000, (failed.cause as JsonRpcException).code)

            h.transport.registerError = null
            h.session.attach(SESSION_ID)
            runCurrent()
            assertIs<ControllerState.Attached>(h.session.state.value)
            assertEquals(2, h.transport.registerFrames().size)
        }

    // ------------------------------------------------- nueva generación / fin

    @Test
    fun `una nueva generacion de client re-registra y mueve el heartbeat`() =
        runTest {
            val h = newHarness()
            h.start()
            h.session.attach(SESSION_ID)
            runCurrent()

            val transport2 = ScriptedTransport()
            val client2 =
                GatewayClient(
                    channel = JsonRpcChannel(transport = transport2, scope = backgroundScope),
                    scope = backgroundScope,
                )
            h.clients.emit(client2)
            runCurrent()

            assertIs<ControllerState.Attached>(h.session.state.value)
            assertEquals(1, transport2.registerFrames().size)

            advanceTimeBy(20_000)
            runCurrent()
            assertEquals(1, transport2.heartbeatFrames().size)
            assertEquals(0, h.transport.heartbeatFrames().size, "el canal viejo no recibe heartbeats")
        }

    @Test
    fun `detach envia browser-controller-detach, para el heartbeat y vuelve a Idle`() =
        runTest {
            val h = newHarness()
            h.start()
            h.session.attach(SESSION_ID)
            runCurrent()
            advanceTimeBy(20_000)
            runCurrent()
            assertEquals(1, h.transport.heartbeatFrames().size)

            h.session.detach()
            runCurrent()

            assertIs<ControllerState.Idle>(h.session.state.value)
            val detach =
                h.transport
                    .detachFrames()
                    .single()
                    .getValue("params")
                    .jsonObject
            assertEquals(SESSION_ID, detach.getValue("session_id").jsonPrimitive.content)
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(1, h.transport.heartbeatFrames().size, "sin heartbeats tras detach")
        }

    @Test
    fun `close hace detach y ninguna generacion nueva vuelve a registrar`() =
        runTest {
            val h = newHarness()
            h.start()
            h.session.attach(SESSION_ID)
            runCurrent()

            h.session.close()
            runCurrent()
            assertEquals(1, h.transport.detachFrames().size)
            assertIs<ControllerState.Idle>(h.session.state.value)

            val transport2 = ScriptedTransport()
            val client2 =
                GatewayClient(
                    channel = JsonRpcChannel(transport = transport2, scope = backgroundScope),
                    scope = backgroundScope,
                )
            h.clients.emit(client2)
            runCurrent()
            assertEquals(0, transport2.registerFrames().size)
        }

    // --------------------------------------------------------- transport fake

    /**
     * [Transport] en memoria que responde solo a los RPC de `browser.controller.*`
     * y `gateway.ping` (como haría el broker real), deja inyectar eventos y graba
     * cada frame para las aserciones.
     */
    private class ScriptedTransport : Transport {
        val sent = CopyOnWriteArrayList<String>()

        private val _incoming = Channel<String>(capacity = Channel.UNLIMITED)
        override val incoming: Flow<String> = _incoming.receiveAsFlow()

        @Volatile
        var closed = false
            private set

        /** Si no es null, `browser.controller.register` responde con este `error` JSON-RPC. */
        @Volatile
        var registerError: JsonObject? = null

        /** Si no es null, `browser.controller.heartbeat` responde `{"error":…}` con este mensaje. */
        @Volatile
        var heartbeatError: JsonPrimitive? = null

        override suspend fun send(text: String) {
            check(!closed) { "send sobre un transport cerrado" }
            sent += text
            val frame = Json.parseToJsonElement(text).jsonObject
            val method = frame["method"]?.jsonPrimitive?.contentOrNull ?: return
            val id = frame["id"]?.jsonPrimitive?.longOrNull ?: return
            when (method) {
                RpcMethods.GATEWAY_PING -> emit("""{"id":$id,"result":{"ok":true}}""")
                RpcMethods.BROWSER_CONTROLLER_REGISTER -> {
                    val error = registerError
                    if (error == null) {
                        emit(
                            """{"id":$id,"result":{"scope":{""" +
                                """"principal_id":"basic:u_fake_usuario","profile_id":"default",""" +
                                """"session_id":"$SESSION_ID","controller_id":"$CONTROLLER_ID",""" +
                                """"browser_profile_id":"${ControllerSession.DEFAULT_BROWSER_PROFILE_ID}",""" +
                                """"transport_family":"webview","capabilities":["controller.noop"]}}}""",
                        )
                    } else {
                        emit("""{"id":$id,"error":$error}""")
                    }
                }
                RpcMethods.BROWSER_CONTROLLER_RESULT -> emit("""{"id":$id,"result":{"accepted":true}}""")
                RpcMethods.BROWSER_CONTROLLER_HEARTBEAT -> {
                    val error = heartbeatError
                    if (error == null) {
                        emit("""{"id":$id,"result":{"ok":true}}""")
                    } else {
                        emit("""{"id":$id,"error":{"code":-32000,"message":$error}}""")
                    }
                }
                RpcMethods.BROWSER_CONTROLLER_DETACH -> emit("""{"id":$id,"result":{"detached":true}}""")
            }
        }

        suspend fun emit(text: String) {
            _incoming.send(text)
        }

        suspend fun emitCommand(
            sessionId: String,
            commandId: String,
            action: String,
            arguments: String = "{}",
            controllerId: String? = CONTROLLER_ID,
            browserProfileId: String? = ControllerSession.DEFAULT_BROWSER_PROFILE_ID,
        ) = emit(
            buildJsonObject {
                put("method", "event")
                putJsonObject("params") {
                    put("type", EventTypes.BROWSER_CONTROLLER_COMMAND)
                    put("session_id", sessionId)
                    putJsonObject("payload") {
                        put("command_id", commandId)
                        put("action", action)
                        put("arguments", Json.parseToJsonElement(arguments))
                        put("controller_id", controllerId?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("browser_profile_id", browserProfileId?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("tool_call_id", "tc-test-1")
                    }
                }
            }.toString(),
        )

        suspend fun emitCancel(
            sessionId: String,
            commandId: String,
        ) = emit(
            buildJsonObject {
                put("method", "event")
                putJsonObject("params") {
                    put("type", EventTypes.BROWSER_CONTROLLER_CANCEL)
                    put("session_id", sessionId)
                    putJsonObject("payload") {
                        put("command_id", commandId)
                        put("tool_call_id", "tc-test-1")
                    }
                }
            }.toString(),
        )

        suspend fun emitToolStart(
            sessionId: String,
            name: String,
        ) = emit(
            buildJsonObject {
                put("method", "event")
                putJsonObject("params") {
                    put("type", EventTypes.TOOL_START)
                    put("session_id", sessionId)
                    putJsonObject("payload") {
                        put("tool_id", "tool-test-1")
                        put("name", name)
                    }
                }
            }.toString(),
        )

        fun framesByMethod(method: String): List<JsonObject> =
            sent
                .map { Json.parseToJsonElement(it).jsonObject }
                .filter { it["method"]?.jsonPrimitive?.contentOrNull == method }

        fun registerFrames(): List<JsonObject> = framesByMethod(RpcMethods.BROWSER_CONTROLLER_REGISTER)

        fun resultFrames(): List<JsonObject> = framesByMethod(RpcMethods.BROWSER_CONTROLLER_RESULT)

        fun heartbeatFrames(): List<JsonObject> = framesByMethod(RpcMethods.BROWSER_CONTROLLER_HEARTBEAT)

        fun detachFrames(): List<JsonObject> = framesByMethod(RpcMethods.BROWSER_CONTROLLER_DETACH)

        override suspend fun close() {
            closed = true
            _incoming.close()
        }
    }

    private companion object {
        const val CONTROLLER_ID = "android-test-f3"
        const val SESSION_ID = "sess_test_1"
        const val OTHER_SESSION_ID = "sess_test_2"
    }
}
