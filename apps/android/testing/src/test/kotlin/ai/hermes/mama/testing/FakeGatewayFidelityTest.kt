package ai.hermes.mama.testing

import ai.hermes.mama.contract.EventTypes
import ai.hermes.mama.contract.RpcMethods
import ai.hermes.mama.gateway.JsonRpcChannel
import ai.hermes.mama.gateway.JsonRpcException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * Fidelidad del [FakeGateway] contra el backend real (revisión B5): códigos de
 * dominio (4001/4006/4007/4021/4023/4403), shape exacta de `gateway.ready`,
 * cancelaciones `request.cancel`, params malformados → -32602 y las puertas
 * fail-closed de `browser.controller.*`. El fake debe CAZAR las desviaciones
 * del cliente, no tolerarlas.
 */
class FakeGatewayFidelityTest {
    private var gateway: FakeGateway? = null
    private val clients = CopyOnWriteArrayList<OkHttpClient>()

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

    private fun newClient(): OkHttpClient = OkHttpWsTransport.defaultClient().also(clients::add)

    private suspend fun CoroutineScope.connect(gw: FakeGateway): JsonRpcChannel = channelTo(gw.wsUrl, newClient())

    private suspend fun CoroutineScope.connectAuthenticated(gw: FakeGateway): JsonRpcChannel {
        val client = newClient()
        val ticket = mintTicket(gw, client)
        return channelTo("${gw.wsUrl}?ticket=$ticket", client)
    }

    /** Lanza la llamada y exige el error JSON-RPC esperado (nunca un éxito). */
    private suspend fun expectRpcError(
        code: Int,
        call: suspend () -> Any?,
    ): JsonRpcException {
        val error = assertFailsWith<JsonRpcException> { call() }
        assertEquals(code, error.code, "se esperaba error $code y llegó ${error.code}: ${error.message}")
        return error
    }

    // --- códigos de sesión del backend real ---

    @Test
    fun `session ids erroneos devuelven los codigos del backend`() =
        runBlocking {
            val gw = startGateway("sesiones")
            val channel = connect(gw)
            try {
                // Runtime muerto/desconocido → 4001 (_sess_nowait).
                expectRpcError(CODE_RUNTIME_NOT_FOUND) {
                    channel.call(
                        RpcMethods.SESSION_HISTORY,
                        buildJsonObject { put("session_id", "sess_que_no_existe") },
                    )
                }
                expectRpcError(CODE_RUNTIME_NOT_FOUND) {
                    channel.call(
                        RpcMethods.PROMPT_SUBMIT,
                        buildJsonObject {
                            put("session_id", "sess_que_no_existe")
                            put("text", "hola")
                        },
                    )
                }
                // Stored: falta session_id → 4006; desconocido → 4007.
                expectRpcError(CODE_SESSION_ID_REQUIRED) {
                    channel.call(RpcMethods.SESSION_RESUME, buildJsonObject { })
                }
                expectRpcError(CODE_STORED_NOT_FOUND) {
                    channel.call(
                        RpcMethods.SESSION_RESUME,
                        buildJsonObject { put("session_id", "stored_que_no_existe") },
                    )
                }
                expectRpcError(CODE_SESSION_ID_REQUIRED) {
                    channel.call(RpcMethods.SESSION_DELETE, buildJsonObject { })
                }
                expectRpcError(CODE_STORED_NOT_FOUND) {
                    channel.call(
                        RpcMethods.SESSION_DELETE,
                        buildJsonObject { put("session_id", "stored_que_no_existe") },
                    )
                }
            } finally {
                channel.close()
            }
        }

    @Test
    fun `session delete sigue el orden del real - viva 4023, stored-only borrable, resume la hace viva`() =
        runBlocking {
            val gw = startGateway("sesiones")
            val channel = connect(gw)
            try {
                // Seed CON runtime_id: viva desde el arranque → 4023 (aunque esté en reposo).
                expectRpcError(CODE_SESSION_ACTIVE) {
                    channel.call(
                        RpcMethods.SESSION_DELETE,
                        buildJsonObject { put("session_id", "stored_viva") },
                    )
                }
                // Seed SIN runtime_id: stored-only → se borra.
                val deleted =
                    channel.call(
                        RpcMethods.SESSION_DELETE,
                        buildJsonObject { put("session_id", "stored_fria") },
                    ) as JsonObject
                assertEquals("stored_fria", deleted["deleted"]?.jsonPrimitive?.contentOrNull)

                // Otra stored-only: RESUME la sube al registro vivo → 4023 después.
                val resume =
                    channel.call(
                        RpcMethods.SESSION_RESUME,
                        buildJsonObject { put("session_id", "stored_otra") },
                    ) as JsonObject
                assertEquals("stored_otra", resume["session_key"]?.jsonPrimitive?.contentOrNull)
                expectRpcError(CODE_SESSION_ACTIVE) {
                    channel.call(
                        RpcMethods.SESSION_DELETE,
                        buildJsonObject { put("session_id", "stored_otra") },
                    )
                }
            } finally {
                channel.close()
            }
        }

    @Test
    fun `resume sube la sesion al registro vivo y delete devuelve 4023`() =
        runBlocking {
            val gw = startGateway("sesiones")
            val channel = connect(gw)
            try {
                // Creo una sesión nueva: create la deja VIVA (como el real).
                val live = createFakeSession(channel)
                expectRpcError(CODE_SESSION_ACTIVE) {
                    channel.call(
                        RpcMethods.SESSION_DELETE,
                        buildJsonObject { put("session_id", live["stored_session_id"]?.jsonPrimitive?.contentOrNull) },
                    )
                }
            } finally {
                channel.close()
            }
        }

    @Test
    fun `session title - lectura devuelve session_key, vacio da 4021, escritura emite session info`() =
        runBlocking {
            val gw = startGateway("hola_mundo")
            val channel = connect(gw)
            try {
                val sid = createFakeSession(channel).fakeSessionId()
                // Escritura: {pending:false, title} + evento session.info (como el real).
                val info =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(TIMEOUT_S.seconds) {
                            channel.events
                                .filter { it.sessionId == sid && it.type == EventTypes.SESSION_INFO }
                                .first()
                        }
                    }
                val written =
                    channel.call(
                        RpcMethods.SESSION_TITLE,
                        buildJsonObject {
                            put("session_id", sid)
                            put("title", "Facturas del mes")
                        },
                    ) as JsonObject
                assertEquals("Facturas del mes", written["title"]?.jsonPrimitive?.contentOrNull)
                assertEquals(JsonPrimitive(false), written["pending"])
                val infoTitle = (info.await().payload as JsonObject)["title"]?.jsonPrimitive?.contentOrNull
                assertEquals("Facturas del mes", infoTitle, "session.info debe llevar el título nuevo")

                // Lectura: {title, session_key} con el STORED id.
                val read =
                    channel.call(
                        RpcMethods.SESSION_TITLE,
                        buildJsonObject { put("session_id", sid) },
                    ) as JsonObject
                assertEquals("Facturas del mes", read["title"]?.jsonPrimitive?.contentOrNull)
                assertNotNull(read["session_key"])

                // Título vacío → 4021 (el real exige texto no-blanco).
                expectRpcError(CODE_TITLE_REQUIRED) {
                    channel.call(
                        RpcMethods.SESSION_TITLE,
                        buildJsonObject {
                            put("session_id", sid)
                            put("title", "   ")
                        },
                    )
                }
            } finally {
                channel.close()
            }
        }

    // --- método inexistente / params malformados ---

    @Test
    fun `prompt stop no existe - responde -32601`() =
        runBlocking {
            val gw = startGateway("hola_mundo")
            val channel = connect(gw)
            try {
                expectRpcError(JSON_RPC_METHOD_NOT_FOUND) {
                    channel.call("prompt.stop", buildJsonObject { put("session_id", "sess_x") })
                }
            } finally {
                channel.close()
            }
        }

    @Test
    fun `params con tipo incorrecto responden -32602 y el socket sobrevive`() =
        runBlocking {
            val gw = startGateway("hola_mundo")
            val channel = connect(gw)
            try {
                expectRpcError(JSON_RPC_INVALID_PARAMS) {
                    channel.call(
                        RpcMethods.SESSION_HISTORY,
                        buildJsonObject { put("session_id", buildJsonObject { }) },
                    )
                }
                expectRpcError(JSON_RPC_INVALID_PARAMS) {
                    channel.call(
                        RpcMethods.SESSION_LIST,
                        buildJsonObject { put("limit", buildJsonObject { }) },
                    )
                }
                // El socket sigue vivo tras los -32602 (antes se moría sin responder).
                val ping = channel.call(RpcMethods.GATEWAY_PING) as JsonObject
                assertEquals(JsonPrimitive(true), ping["ok"])
            } finally {
                channel.close()
            }
        }

    @Test
    fun `params que no son objeto responden -32602`() =
        runBlocking {
            val gw = startGateway("hola_mundo")
            val transport = OkHttpWsTransport.connect(gw.wsUrl, newClient())
            try {
                transport.send("""{"id":77,"method":"gateway.ping","params":"esto no es objeto"}""")
                val raw =
                    withTimeout(TIMEOUT_S.seconds) {
                        transport.incoming.filter { it.contains("\"id\":77") }.first()
                    }
                val frame = Json.parseToJsonElement(raw) as JsonObject
                assertEquals(
                    JSON_RPC_INVALID_PARAMS,
                    (frame["error"] as JsonObject)["code"]?.jsonPrimitive?.contentOrNull?.toInt(),
                )
            } finally {
                transport.close()
            }
        }

    @Test
    fun `events since con sid desconocido devuelve replay vacio y last_seen malo da -32602`() =
        runBlocking {
            val gw = startGateway("hola_mundo")
            val channel = connect(gw)
            try {
                // El real NO resuelve la sesión: sid desconocido → replay vacío.
                val replay =
                    channel.call(
                        RpcMethods.SESSION_EVENTS_SINCE,
                        buildJsonObject {
                            put("session_id", "sess_que_no_existe")
                            put("last_seen", 0)
                        },
                    ) as JsonObject
                assertEquals(0, (replay["events"] as JsonArray).size)
                assertEquals(JsonPrimitive(0), replay["count"])

                expectRpcError(JSON_RPC_INVALID_PARAMS) {
                    channel.call(
                        RpcMethods.SESSION_EVENTS_SINCE,
                        buildJsonObject {
                            put("session_id", "sess_x")
                            put("last_seen", "abc")
                        },
                    )
                }
            } finally {
                channel.close()
            }
        }

    // --- gateway.ready ---

    @Test
    fun `gateway ready solo va a su conexion y sin session_id ni seq`() =
        runBlocking {
            val gw = startGateway("hola_mundo")
            val first = connect(gw)
            try {
                val ready1 = withTimeout(TIMEOUT_S.seconds) { first.events.first() }
                assertEquals(EventTypes.GATEWAY_READY, ready1.type)
                // El real omite session_id y seq en el ready (ws.py).
                assertNull(ready1.sessionId, "gateway.ready no debe llevar session_id")
                assertNull(ready1.seq, "gateway.ready no debe llevar seq")

                // Una segunda conexión recibe SU ready; la primera NO un duplicado.
                val second = connect(gw)
                try {
                    val ready2 = withTimeout(TIMEOUT_S.seconds) { second.events.first() }
                    assertEquals(EventTypes.GATEWAY_READY, ready2.type)
                    val rebroadcast =
                        withTimeoutOrNull(READY_QUIET_MS) {
                            first.events.filter { it.type == EventTypes.GATEWAY_READY }.first()
                        }
                    assertNull(rebroadcast, "gateway.ready de otra conexión no debe re-emitirse")
                } finally {
                    second.close()
                }
            } finally {
                first.close()
            }
        }

    // --- request.cancel ---

    @Test
    fun `srq que vence emite request cancel con reason timeout`() =
        runBlocking {
            val gw = startGateway("request_cancel")
            val channel = connect(gw)
            try {
                val sid = createFakeSession(channel).fakeSessionId()
                val request =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(TIMEOUT_S.seconds) { channel.serverRequests.first() }
                    }
                val cancel =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(TIMEOUT_S.seconds) {
                            channel.events
                                .filter { it.sessionId == sid && it.type == EventTypes.REQUEST_CANCEL }
                                .first()
                        }
                    }
                channel.call(
                    RpcMethods.PROMPT_SUBMIT,
                    buildJsonObject {
                        put("session_id", sid)
                        put("text", "esto va a hacer timeout")
                    },
                )
                val srq = request.await()
                // SIN responder: timeout_ms=600 del guion → request.cancel{reason:"timeout"}.
                val payload = cancel.await().payload as JsonObject
                assertEquals(srq.id, payload["id"]?.jsonPrimitive?.contentOrNull)
                assertEquals("approval", payload["method"]?.jsonPrimitive?.contentOrNull)
                assertEquals("timeout", payload["reason"]?.jsonPrimitive?.contentOrNull)
            } finally {
                channel.close()
            }
        }

    @Test
    fun `cancel request del guion retira la srq con su motivo`() =
        runBlocking {
            val gw = startGateway("request_cancel")
            val channel = connect(gw)
            try {
                val sid = createFakeSession(channel).fakeSessionId()
                val request =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(TIMEOUT_S.seconds) { channel.serverRequests.first() }
                    }
                val cancel =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(TIMEOUT_S.seconds) {
                            channel.events
                                .filter { it.sessionId == sid && it.type == EventTypes.REQUEST_CANCEL }
                                .first()
                        }
                    }
                channel.call(
                    RpcMethods.PROMPT_SUBMIT,
                    buildJsonObject {
                        put("session_id", sid)
                        put("text", "algo normal")
                    },
                )
                val srq = request.await()
                val payload = cancel.await().payload as JsonObject
                assertEquals(srq.id, payload["id"]?.jsonPrimitive?.contentOrNull)
                assertEquals("answered_elsewhere", payload["reason"]?.jsonPrimitive?.contentOrNull)

                // La srq ya no figura en open_requests del replay.
                val since =
                    channel.call(
                        RpcMethods.SESSION_EVENTS_SINCE,
                        buildJsonObject {
                            put("session_id", sid)
                            put("last_seen", 0)
                        },
                    ) as JsonObject
                assertEquals(0, (since["open_requests"] as JsonArray).size)
            } finally {
                channel.close()
            }
        }

    // --- puertas fail-closed del controlador de navegador (4403) ---

    @Test
    fun `browser register sin identidad autenticada da 4403`() =
        runBlocking {
            val gw = startGateway("browser")
            // Conexión dev SIN ticket: entra al socket pero el controlador no.
            val channel = connect(gw)
            try {
                val sid = createFakeSession(channel).fakeSessionId()
                expectRpcError(BROWSER_FORBIDDEN) {
                    channel.call(
                        RpcMethods.BROWSER_CONTROLLER_REGISTER,
                        buildJsonObject {
                            put("session_id", sid)
                            put("controller_id", "android-test")
                            put("browser_profile_id", "mama-webview")
                            put("protocol_version", 1)
                            put("capabilities", JsonArray(listOf(JsonPrimitive("browser_navigate"))))
                        },
                    )
                }
            } finally {
                channel.close()
            }
        }

    @Test
    fun `browser register rechaza version y capabilities invalidas con 4403`() =
        runBlocking {
            val gw = startGateway("browser")
            val channel = connectAuthenticated(gw)
            try {
                val sid = createFakeSession(channel).fakeSessionId()

                fun register(
                    version: JsonPrimitive,
                    capabilities: List<JsonPrimitive>,
                ) = buildJsonObject {
                    put("session_id", sid)
                    put("controller_id", "android-test")
                    put("browser_profile_id", "mama-webview")
                    put("protocol_version", version)
                    put("capabilities", JsonArray(capabilities))
                }
                // Versión != 1 → 4403 (el real exige protocol_version EXACTO 1).
                expectRpcError(BROWSER_FORBIDDEN) {
                    channel.call(
                        RpcMethods.BROWSER_CONTROLLER_REGISTER,
                        register(JsonPrimitive(2), listOf(JsonPrimitive("browser_navigate"))),
                    )
                }
                // Versión como STRING → 4403 (no coercion laxa).
                expectRpcError(BROWSER_FORBIDDEN) {
                    channel.call(
                        RpcMethods.BROWSER_CONTROLLER_REGISTER,
                        register(JsonPrimitive("1"), listOf(JsonPrimitive("browser_navigate"))),
                    )
                }
                // Capabilities vacías → 4403.
                expectRpcError(BROWSER_FORBIDDEN) {
                    channel.call(
                        RpcMethods.BROWSER_CONTROLLER_REGISTER,
                        register(JsonPrimitive(1), emptyList()),
                    )
                }
                // Capabilities fuera del allowlist → 4403 tras el filtrado.
                expectRpcError(BROWSER_FORBIDDEN) {
                    channel.call(
                        RpcMethods.BROWSER_CONTROLLER_REGISTER,
                        register(JsonPrimitive(1), listOf(JsonPrimitive("cap_inventada"))),
                    )
                }
                // Dev-capability sin developer_mode → filtrada → 4403.
                expectRpcError(BROWSER_FORBIDDEN) {
                    channel.call(
                        RpcMethods.BROWSER_CONTROLLER_REGISTER,
                        register(JsonPrimitive(1), listOf(JsonPrimitive("browser_cdp"))),
                    )
                }
            } finally {
                channel.close()
            }
        }

    @Test
    fun `browser register con el flag apagado da 4403 aun autenticado`() =
        runBlocking {
            val gw = startGateway("browser_off")
            val channel = connectAuthenticated(gw)
            try {
                val sid = createFakeSession(channel).fakeSessionId()
                expectRpcError(BROWSER_FORBIDDEN) {
                    channel.call(
                        RpcMethods.BROWSER_CONTROLLER_REGISTER,
                        buildJsonObject {
                            put("session_id", sid)
                            put("controller_id", "android-test")
                            put("browser_profile_id", "mama-webview")
                            put("protocol_version", 1)
                            put("capabilities", JsonArray(listOf(JsonPrimitive("browser_navigate"))))
                        },
                    )
                }
            } finally {
                channel.close()
            }
        }

    @Test
    fun `browser heartbeat result y detach sin registro dan 4403`() =
        runBlocking {
            val gw = startGateway("browser")
            val channel = connectAuthenticated(gw)
            try {
                val sid = createFakeSession(channel).fakeSessionId()
                expectRpcError(BROWSER_FORBIDDEN) {
                    channel.call(
                        RpcMethods.BROWSER_CONTROLLER_HEARTBEAT,
                        buildJsonObject { put("session_id", sid) },
                    )
                }
                expectRpcError(BROWSER_FORBIDDEN) {
                    channel.call(
                        RpcMethods.BROWSER_CONTROLLER_RESULT,
                        buildJsonObject {
                            put("session_id", sid)
                            put("command_id", "cmd-x")
                            put("ok", true)
                        },
                    )
                }
                expectRpcError(BROWSER_FORBIDDEN) {
                    channel.call(
                        RpcMethods.BROWSER_CONTROLLER_DETACH,
                        buildJsonObject { put("session_id", sid) },
                    )
                }
            } finally {
                channel.close()
            }
        }

    @Test
    fun `browser result de otra conexion para un comando ajeno devuelve accepted false`() =
        runBlocking {
            val gw = startGateway("browser")
            val owner = connectAuthenticated(gw)
            val intruder = connectAuthenticated(gw)
            try {
                val sid = createFakeSession(owner).fakeSessionId()
                // Ambas conexiones registran controlador (scope por conexión).
                for ((conn, controllerId) in listOf(owner to "android-owner", intruder to "android-intruder")) {
                    conn.call(
                        RpcMethods.BROWSER_CONTROLLER_REGISTER,
                        buildJsonObject {
                            put("session_id", sid)
                            put("controller_id", controllerId)
                            put("browser_profile_id", "mama-webview")
                            put("protocol_version", 1)
                            put("capabilities", JsonArray(listOf(JsonPrimitive("browser_navigate"))))
                        },
                    )
                }
                // Un result para un command_id inexistente/ajeno → {accepted:false},
                // NO un error: así lo hace el broker real con scope ajeno.
                val result =
                    intruder.call(
                        RpcMethods.BROWSER_CONTROLLER_RESULT,
                        buildJsonObject {
                            put("session_id", sid)
                            put("command_id", "cmd-que-no-existe")
                            put("ok", true)
                            put("result", "{}")
                        },
                    ) as JsonObject
                assertEquals(JsonPrimitive(false), result["accepted"])
            } finally {
                owner.close()
                intruder.close()
            }
        }

    @Test
    fun `interrupt devuelve not interrupted solo con expected hosted task id`() =
        runBlocking {
            val gw = startGateway("hola_mundo")
            val channel = connect(gw)
            try {
                val sid = createFakeSession(channel).fakeSessionId()
                val notInterrupted =
                    channel.call(
                        RpcMethods.SESSION_INTERRUPT,
                        buildJsonObject {
                            put("session_id", sid)
                            put("expected_hosted_task_id", "task-x")
                        },
                    ) as JsonObject
                assertEquals("not_interrupted", notInterrupted["status"]?.jsonPrimitive?.contentOrNull)
                assertEquals(JsonPrimitive(false), notInterrupted["interrupted"])

                // Sin el parámetro: el real responde interrupted aunque no corra nada.
                val plain =
                    channel.call(
                        RpcMethods.SESSION_INTERRUPT,
                        buildJsonObject { put("session_id", sid) },
                    ) as JsonObject
                assertEquals(setOf("status"), plain.keys)
                assertEquals("interrupted", plain["status"]?.jsonPrimitive?.contentOrNull)
            } finally {
                channel.close()
            }
        }

    private companion object {
        const val TIMEOUT_S = 15
        const val READY_QUIET_MS = 500L
        const val JSON_RPC_METHOD_NOT_FOUND = -32601
        const val JSON_RPC_INVALID_PARAMS = -32602
        const val CODE_RUNTIME_NOT_FOUND = 4001
        const val CODE_SESSION_ID_REQUIRED = 4006
        const val CODE_STORED_NOT_FOUND = 4007
        const val CODE_TITLE_REQUIRED = 4021
        const val CODE_SESSION_ACTIVE = 4023
        const val BROWSER_FORBIDDEN = 4403
    }
}
