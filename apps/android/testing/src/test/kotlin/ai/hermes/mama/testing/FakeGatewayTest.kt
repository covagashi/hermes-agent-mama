package ai.hermes.mama.testing

import ai.hermes.mama.contract.EventTypes
import ai.hermes.mama.contract.RpcMethods
import ai.hermes.mama.contract.ServerRequests
import ai.hermes.mama.gateway.GatewayEvent
import ai.hermes.mama.gateway.JsonRpcChannel
import ai.hermes.mama.gateway.JsonRpcException
import ai.hermes.mama.gateway.ServerRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * `FakeGateway` contra el canal cliente REAL de B1 por un WebSocket REAL
 * (ROADMAP §5, B5): el camino completo `JsonRpcChannel` → OkHttp → Ktor CIO →
 * `RpcDispatcher`/`TurnRunner` queda cubierto de punta a punta.
 */
class FakeGatewayTest {
    private var gateway: FakeGateway? = null
    private val clients = java.util.concurrent.CopyOnWriteArrayList<okhttp3.OkHttpClient>()

    @AfterEach
    fun tearDown() {
        gateway?.close()
        gateway = null
        clients.forEach {
            it.dispatcher.executorService.shutdown()
            it.connectionPool.evictAll()
        }
        clients.clear()
    }

    private fun startGateway(scriptName: String): FakeGateway =
        FakeGateway(FakeGatewayScript.load(scriptName), logger = {}).start().also { gateway = it }

    private fun newClient(): okhttp3.OkHttpClient = OkHttpWsTransport.defaultClient().also(clients::add)

    /** Canal nuevo sobre el socket del fake (dev, SIN ticket → identidad no autenticada). */
    private suspend fun CoroutineScope.connect(gw: FakeGateway): JsonRpcChannel = channelTo(gw.wsUrl, newClient())

    /** Canal AUTENTICADO (login → ws-ticket → `?ticket=`): la identidad sellada que exige §2.6. */
    private suspend fun CoroutineScope.connectAuthenticated(gw: FakeGateway): JsonRpcChannel {
        val client = newClient()
        val ticket = mintTicket(gw, client)
        return channelTo("${gw.wsUrl}?ticket=$ticket", client)
    }

    private suspend fun createSession(channel: JsonRpcChannel): JsonObject = createFakeSession(channel)

    private fun JsonObject.sessionId(): String = fakeSessionId()

    @Test
    fun `ping responde ok true`() =
        runBlocking {
            val gw = startGateway("hola_mundo")
            val channel = connect(gw)
            try {
                val result = channel.call(RpcMethods.GATEWAY_PING)
                assertEquals(JsonPrimitive(true), (result as JsonObject)["ok"])
            } finally {
                channel.close()
            }
        }

    @Test
    fun `gateway ready es el primer evento del socket`() =
        runBlocking {
            val gw = startGateway("hola_mundo")
            val channel = connect(gw)
            try {
                val ready = withTimeout(TIMEOUT_S.seconds) { channel.events.first() }
                assertEquals(EventTypes.GATEWAY_READY, ready.type)
                val payload = ready.payload as JsonObject
                assertEquals("epoch-hola-1", payload["replay_epoch"]?.jsonPrimitive?.contentOrNull)
            } finally {
                channel.close()
            }
        }

    @Test
    fun `hola produce start, 3 deltas y complete en orden`() =
        runBlocking {
            val gw = startGateway("hola_mundo")
            val channel = connect(gw)
            try {
                val session = createSession(channel)
                val sid = session.sessionId()
                // Los 5 eventos del guion (start + 3 deltas + complete) llegan por
                // orden tras la respuesta de prompt.submit; session.info{running}
                // puede intercalarse y no forma parte de la burbuja.
                val received =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(TIMEOUT_S.seconds) {
                            channel.events
                                .filter { it.sessionId == sid && it.type.startsWith("message.") }
                                .take(EXPECTED_TURN_EVENTS)
                                .toList()
                        }
                    }
                val submit =
                    channel.call(
                        RpcMethods.PROMPT_SUBMIT,
                        buildJsonObject {
                            put("session_id", sid)
                            put("text", "hola")
                        },
                    ) as JsonObject
                assertEquals("streaming", submit["status"]?.jsonPrimitive?.contentOrNull)

                val types = received.await().map(GatewayEvent::type)
                assertEquals(
                    listOf(
                        EventTypes.MESSAGE_START,
                        EventTypes.MESSAGE_DELTA,
                        EventTypes.MESSAGE_DELTA,
                        EventTypes.MESSAGE_DELTA,
                        EventTypes.MESSAGE_COMPLETE,
                    ),
                    types,
                )
                val deltas =
                    received.await().drop(1).take(3).map {
                        (it.payload as JsonObject)["text"]?.jsonPrimitive?.contentOrNull
                    }
                assertEquals(listOf("¡Hola! ", "¿Qué tal ", "estás hoy?"), deltas)
                // seq monótono por sesión (§2.2)
                val seqs = received.await().mapNotNull(GatewayEvent::seq)
                assertEquals(seqs, seqs.sorted())
            } finally {
                channel.close()
            }
        }

    @Test
    fun `session list y resume devuelven el chat semilla`() =
        runBlocking {
            val gw = startGateway("hola_mundo")
            val channel = connect(gw)
            try {
                val list =
                    channel.call(RpcMethods.SESSION_LIST, buildJsonObject { }) as JsonObject
                val sessions = list.getValue("sessions") as JsonArray
                val seed =
                    sessions
                        .map { it as JsonObject }
                        .first { it["id"]?.jsonPrimitive?.contentOrNull == "stored_hola" }
                assertEquals("Pedidos y facturas", seed["title"]?.jsonPrimitive?.contentOrNull)

                val resume =
                    channel.call(
                        RpcMethods.SESSION_RESUME,
                        buildJsonObject { put("session_id", "stored_hola") },
                    ) as JsonObject
                assertEquals("sess_hola", resume.sessionId())
                assertEquals("stored_hola", resume["stored_session_id"]?.jsonPrimitive?.contentOrNull)
                val messages = resume.getValue("messages") as JsonArray
                assertEquals(2, messages.size)

                val history =
                    channel.call(
                        RpcMethods.SESSION_HISTORY,
                        buildJsonObject { put("session_id", "sess_hola") },
                    ) as JsonObject
                assertEquals(JsonPrimitive(2), history["count"])
            } finally {
                channel.close()
            }
        }

    @Test
    fun `approval llega como srq y la respuesta desbloquea el turno`() =
        runBlocking {
            val gw = startGateway("approval")
            val channel = connect(gw)
            try {
                val sid = createSession(channel).sessionId()
                val request =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(TIMEOUT_S.seconds) { channel.serverRequests.first() }
                    }
                channel.call(
                    RpcMethods.PROMPT_SUBMIT,
                    buildJsonObject {
                        put("session_id", sid)
                        put("text", "aprueba el correo")
                    },
                )
                val srq: ServerRequest = request.await()
                assertEquals(ServerRequests.APPROVAL, srq.method)
                assertTrue(srq.id.startsWith("srq-"), "id de petición del servidor debe ser srq-*: ${srq.id}")
                assertEquals(sid, srq.params["session_id"]?.jsonPrimitive?.contentOrNull)
                assertNotNull(srq.params["request_id"])
                // El delta final interpola {{last_result.choice}} DENTRO del
                // string: suscribirse ANTES de responder para no perderlo (los
                // eventos no se repiten: suscribir después los pierde).
                val interpolated =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(TIMEOUT_S.seconds) {
                            channel.events
                                .filter { it.sessionId == sid && it.type == EventTypes.MESSAGE_DELTA }
                                .filter {
                                    (it.payload as JsonObject)["text"]
                                        ?.jsonPrimitive
                                        ?.contentOrNull
                                        ?.startsWith("Respondiste:") == true
                                }.first()
                        }
                    }
                val complete =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(TIMEOUT_S.seconds) {
                            channel.events
                                .filter { it.sessionId == sid && it.type == EventTypes.MESSAGE_COMPLETE }
                                .first()
                        }
                    }
                // La respuesta con el MISMO id string desbloquea el guion.
                srq.respond(buildJsonObject { put("choice", "once") })

                // "Respondiste: {{last_result.choice}}." → "Respondiste: once." (M1).
                val deltaText =
                    (interpolated.await().payload as JsonObject)["text"]?.jsonPrimitive?.contentOrNull
                assertEquals("Respondiste: once.", deltaText, "la interpolación debe resolver, no quedar literal")
                val completeStatus = (complete.await().payload as JsonObject)["status"]?.jsonPrimitive?.contentOrNull
                assertEquals("complete", completeStatus)

                val answered = gw.answeredRequests.firstOrNull { it.id == srq.id }
                assertNotNull(answered)
                assertEquals(
                    "once",
                    (answered.result as? JsonObject)?.get("choice")?.jsonPrimitive?.contentOrNull,
                )
            } finally {
                channel.close()
            }
        }

    @Test
    fun `clarify por lotes llega y guarda las respuestas`() =
        runBlocking {
            val gw = startGateway("clarify")
            val channel = connect(gw)
            try {
                val sid = createSession(channel).sessionId()
                val request =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(TIMEOUT_S.seconds) { channel.serverRequests.first() }
                    }
                channel.call(
                    RpcMethods.PROMPT_SUBMIT,
                    buildJsonObject {
                        put("session_id", sid)
                        put("text", "quiero pedirlo")
                    },
                )
                val srq = request.await()
                assertEquals(ServerRequests.CLARIFY, srq.method)
                val questions = srq.params["questions"] as JsonArray
                assertEquals(2, questions.size)
                val complete =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(TIMEOUT_S.seconds) {
                            channel.events
                                .filter { it.sessionId == sid && it.type == EventTypes.MESSAGE_COMPLETE }
                                .first()
                        }
                    }
                srq.respond(
                    buildJsonObject {
                        put(
                            "answers",
                            buildJsonObject {
                                put("color", "rojo")
                                put("cuando", "mañana")
                            },
                        )
                    },
                )
                complete.await()
                val answered = gw.answeredRequests.firstOrNull { it.id == srq.id }
                val answers = (answered?.result as? JsonObject)?.get("answers") as? JsonObject
                assertEquals("rojo", answers?.get("color")?.jsonPrimitive?.contentOrNull)
            } finally {
                channel.close()
            }
        }

    @Test
    fun `los comandos de navegador se emiten y capturan resultados`() =
        runBlocking {
            val gw = startGateway("browser")
            // §2.6: el registro exige identidad AUTENTICADA (ticket sellado).
            val channel = connectAuthenticated(gw)
            try {
                val sid = createSession(channel).sessionId()
                registerBrowserController(channel, sid)
                // Cola permanente: el guion emite cada comando sólo tras el
                // result del anterior — la suscripción no debe caer entre medias.
                val commandQueue = Channel<JsonObject>(Channel.UNLIMITED)
                val collector =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        channel.events
                            .filter { it.type == EventTypes.BROWSER_CONTROLLER_COMMAND }
                            .collect { commandQueue.send(it.payload as JsonObject) }
                    }
                try {
                    val complete =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            withTimeout(TIMEOUT_S.seconds) {
                                channel.events
                                    .filter { it.sessionId == sid && it.type == EventTypes.MESSAGE_COMPLETE }
                                    .first()
                            }
                        }
                    channel.call(
                        RpcMethods.PROMPT_SUBMIT,
                        buildJsonObject {
                            put("session_id", sid)
                            put("text", "ábreme la web de pedidos")
                        },
                    )
                    val actions =
                        List(EXPECTED_BROWSER_COMMANDS) {
                            answerNextBrowserCommand(channel, sid, commandQueue)
                        }
                    assertEquals(
                        listOf("browser_navigate", "browser_snapshot", "browser_click"),
                        actions,
                    )
                    complete.await()
                } finally {
                    collector.cancel()
                }
                assertEquals(EXPECTED_BROWSER_COMMANDS, gw.browserCommandResults.size)
            } finally {
                channel.close()
            }
        }

    private suspend fun registerBrowserController(
        channel: JsonRpcChannel,
        sessionId: String,
    ) {
        channel.call(
            RpcMethods.BROWSER_CONTROLLER_REGISTER,
            buildJsonObject {
                put("session_id", sessionId)
                put("controller_id", "android-test")
                put("browser_profile_id", "mama-webview")
                put("protocol_version", 1)
                put("capabilities", JsonArray(listOf(JsonPrimitive("browser_navigate"))))
            },
        )
    }

    /** Espera el siguiente `browser.controller.command` y responde `{ok:true,result}`; devuelve su `action`. */
    private suspend fun answerNextBrowserCommand(
        channel: JsonRpcChannel,
        sessionId: String,
        commandQueue: Channel<JsonObject>,
    ): String? {
        val payload = withTimeout(TIMEOUT_S.seconds) { commandQueue.receive() }
        check("tool_call_id" in payload) {
            "browser.controller.command sin tool_call_id (el broker siempre lo envía): $payload"
        }
        channel.call(
            RpcMethods.BROWSER_CONTROLLER_RESULT,
            buildJsonObject {
                put("session_id", sessionId)
                put("command_id", payload["command_id"]?.jsonPrimitive?.contentOrNull)
                put("ok", true)
                put("result", """{"success":true}""")
            },
        )
        return payload["action"]?.jsonPrimitive?.contentOrNull
    }

    @Test
    fun `session interrupt corta el turno con complete interrupted`() =
        runBlocking {
            val gw = startGateway("lento")
            val channel = connect(gw)
            try {
                val sid = createSession(channel).sessionId()
                val firstDelta =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(TIMEOUT_S.seconds) {
                            channel.events
                                .filter { it.sessionId == sid && it.type == EventTypes.MESSAGE_DELTA }
                                .first()
                        }
                    }
                channel.call(
                    RpcMethods.PROMPT_SUBMIT,
                    buildJsonObject {
                        put("session_id", sid)
                        put("text", "cuéntame algo largo")
                    },
                )
                firstDelta.await()
                val complete =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(TIMEOUT_S.seconds) {
                            channel.events
                                .filter { it.sessionId == sid && it.type == EventTypes.MESSAGE_COMPLETE }
                                .first()
                        }
                    }
                val interrupt =
                    channel.call(
                        RpcMethods.SESSION_INTERRUPT,
                        buildJsonObject { put("session_id", sid) },
                    ) as JsonObject
                assertEquals("interrupted", interrupt["status"]?.jsonPrimitive?.contentOrNull)
                val completeStatus = (complete.await().payload as JsonObject)["status"]?.jsonPrimitive?.contentOrNull
                assertEquals("interrupted", completeStatus)
            } finally {
                channel.close()
            }
        }

    @Test
    fun `interrupt con submit encolado corta el turno VIVO no el encolado`() =
        runBlocking {
            val gw = startGateway("lento")
            val channel = connect(gw)
            try {
                val sid = createSession(channel).sessionId()
                val completes =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(TIMEOUT_S.seconds) {
                            channel.events
                                .filter { it.sessionId == sid && it.type == EventTypes.MESSAGE_COMPLETE }
                                .take(1)
                                .toList()
                        }
                    }
                val firstDelta =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(TIMEOUT_S.seconds) {
                            channel.events
                                .filter { it.sessionId == sid && it.type == EventTypes.MESSAGE_DELTA }
                                .first()
                        }
                    }
                // Turno A: arranca y emite su primer delta.
                channel.call(
                    RpcMethods.PROMPT_SUBMIT,
                    buildJsonObject {
                        put("session_id", sid)
                        put("text", "cuéntame algo largo")
                    },
                )
                firstDelta.await()
                // Turno B: queda ENCOLADO esperando el mutex (su job NO es el vivo).
                val submitB =
                    channel.call(
                        RpcMethods.PROMPT_SUBMIT,
                        buildJsonObject {
                            put("session_id", sid)
                            put("text", "y otra cosa más")
                        },
                    ) as JsonObject
                assertEquals("queued", submitB["status"]?.jsonPrimitive?.contentOrNull)

                val interrupt =
                    channel.call(
                        RpcMethods.SESSION_INTERRUPT,
                        buildJsonObject { put("session_id", sid) },
                    ) as JsonObject
                // El real responde exactamente {"status":"interrupted"} — sin extras.
                assertEquals(setOf("status"), interrupt.keys, "interrupt debe devolver sólo {status}")
                assertEquals("interrupted", interrupt["status"]?.jsonPrimitive?.contentOrNull)

                // El primer complete es del turno VIVO (interrumpido), no del encolado.
                val first = completes.await().single()
                assertEquals(
                    "interrupted",
                    (first.payload as JsonObject)["status"]?.jsonPrimitive?.contentOrNull,
                    "interrupt debe cortar el turno vivo, no el que esperaba el mutex",
                )
            } finally {
                channel.close()
            }
        }

    @Test
    fun `approval respond sin choice responde deny como el real`() =
        runBlocking {
            val gw = startGateway("approval")
            val channel = connect(gw)
            try {
                val sid = createSession(channel).sessionId()
                val request =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(TIMEOUT_S.seconds) { channel.serverRequests.first() }
                    }
                channel.call(
                    RpcMethods.PROMPT_SUBMIT,
                    buildJsonObject {
                        put("session_id", sid)
                        put("text", "aprueba el correo")
                    },
                )
                val srq = request.await()
                val complete =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(TIMEOUT_S.seconds) {
                            channel.events
                                .filter { it.sessionId == sid && it.type == EventTypes.MESSAGE_COMPLETE }
                                .first()
                        }
                    }
                // approval.respond por RPC SIN choice → el real fija "deny" (no "once").
                channel.call(
                    RpcMethods.APPROVAL_RESPOND,
                    buildJsonObject {
                        put("session_id", sid)
                        put("request_id", srq.params["request_id"]?.jsonPrimitive?.contentOrNull)
                    },
                )
                complete.await()
                val answered = gw.answeredRequests.firstOrNull { it.id == srq.id }
                assertNotNull(answered)
                assertEquals(
                    "deny",
                    (answered.result as? JsonObject)?.get("choice")?.jsonPrimitive?.contentOrNull,
                    "approval.respond sin choice debe fijar deny (default del backend real)",
                )
            } finally {
                channel.close()
            }
        }

    @Test
    fun `metodo desconocido responde -32601`() =
        runBlocking {
            val gw = startGateway("hola_mundo")
            val channel = connect(gw)
            try {
                val error =
                    assertFailsWith<JsonRpcException> {
                        channel.call("metodo.inexistente")
                    }
                assertEquals(-32601, error.code)
            } finally {
                channel.close()
            }
        }

    @Test
    fun `submit del guion de error devuelve el error configurado`() =
        runBlocking {
            val gw = startGateway("error")
            val channel = connect(gw)
            try {
                val sid = createSession(channel).sessionId()
                val error =
                    assertFailsWith<JsonRpcException> {
                        channel.call(
                            RpcMethods.PROMPT_SUBMIT,
                            buildJsonObject {
                                put("session_id", sid)
                                put("text", "boom")
                            },
                        )
                    }
                assertEquals(-32000, error.code)

                // Un prompt normal del guion de error cierra la burbuja con status error.
                val complete =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(TIMEOUT_S.seconds) {
                            channel.events
                                .filter { it.sessionId == sid && it.type == EventTypes.MESSAGE_COMPLETE }
                                .first()
                        }
                    }
                channel.call(
                    RpcMethods.PROMPT_SUBMIT,
                    buildJsonObject {
                        put("session_id", sid)
                        put("text", "hola")
                    },
                )
                assertEquals(
                    "error",
                    (complete.await().payload as JsonObject)["status"]?.jsonPrimitive?.contentOrNull,
                )
            } finally {
                channel.close()
            }
        }

    @Test
    fun `events since re-entrega los eventos perdidos con seq`() =
        runBlocking {
            val gw = startGateway("hola_mundo")
            val channel = connect(gw)
            try {
                val sid = createSession(channel).sessionId()
                val complete =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(TIMEOUT_S.seconds) {
                            channel.events
                                .filter { it.sessionId == sid && it.type == EventTypes.MESSAGE_COMPLETE }
                                .first()
                        }
                    }
                channel.call(
                    RpcMethods.PROMPT_SUBMIT,
                    buildJsonObject {
                        put("session_id", sid)
                        put("text", "hola")
                    },
                )
                complete.await()
                val since =
                    channel.call(
                        RpcMethods.SESSION_EVENTS_SINCE,
                        buildJsonObject {
                            put("session_id", sid)
                            put("last_seen", 0)
                        },
                    ) as JsonObject
                val events = since.getValue("events") as JsonArray
                assertTrue(events.size >= EXPECTED_TURN_EVENTS)
                assertEquals("epoch-hola-1", since["epoch"]?.jsonPrimitive?.contentOrNull)
            } finally {
                channel.close()
            }
        }

    private companion object {
        const val TIMEOUT_S = 15
        const val EXPECTED_TURN_EVENTS = 5
        const val EXPECTED_BROWSER_COMMANDS = 3
    }
}
