package ai.hermes.mama.testing

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Una conexión WebSocket viva del [FakeGateway]: enruta frames entrantes
 * (llamadas RPC cliente→servidor y respuestas a peticiones `srq-*` nuestras) y
 * serializa los frames salientes.
 *
 * Las peticiones `srq-*` que se emitan por este socket mueren con él
 * ([onDisconnected] las falla) pero PERMANECEN en `open_requests` de su sesión
 * para la re-entrega de §2.2 — el cliente que reconecte las recibe de nuevo.
 */
class WsConnection internal constructor(
    internal val ws: DefaultWebSocketServerSession,
    val identity: FakeIdentity,
    internal val gateway: FakeGateway,
) {
    private val sendMutex = Mutex()

    /** Acciones post-respuesta: el turno del guion arranca DESPUÉS de contestar `prompt.submit`. */
    private val afterResponse = ArrayDeque<suspend () -> Unit>()

    /** Scope del handler WS: las corrutinas del guion se cancelan si el socket muere. */
    val scope: CoroutineScope
        get() = ws

    suspend fun send(frame: JsonObject) {
        sendMutex.withLock {
            ws.send(Frame.Text(frame.toString()))
        }
    }

    /** Envía tolerante a socket muerto (broadcasts a conexiones que se fueron). */
    suspend fun sendQuietly(frame: JsonObject) {
        try {
            send(frame)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            gateway.log("envío a socket muerto descartado (${e::class.simpleName})")
        }
    }

    suspend fun emitEvent(
        type: String,
        session: FakeSession?,
        payload: JsonObject,
        broadcast: Boolean = false,
    ) {
        gateway.emitEvent(this, type, session, payload, broadcast)
    }

    /** Cierre de WS desde un paso `close_socket` del guion. */
    suspend fun closeWith(
        code: Short,
        reason: String,
    ) {
        ws.close(CloseReason(code, reason))
    }

    // --- recepción ---

    /**
     * Enruta un frame §2.2: respuesta a `srq-*` nuestra, llamada RPC del cliente
     * (id numérico) o notificación. Lo malformado se loguea y el socket sigue.
     */
    suspend fun onText(text: String) {
        val frame =
            try {
                gateway.json.parseToJsonElement(text)
            } catch (ignored: SerializationException) {
                gateway.log("frame JSON inválido (${text.length} chars)")
                return
            }
        val obj = frame as? JsonObject ?: return gateway.log("frame no-objeto ignorado")
        val id = obj["id"]
        val method = (obj["method"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        when {
            // Respuesta a una petición servidor→cliente nuestra (id string).
            method == null && id is JsonPrimitive && id.isString ->
                resolveServerRequest(id.content, obj)

            // Respuesta con id numérico: no es nuestra (los ids numéricos son del cliente).
            method == null && id != null -> gateway.log("respuesta sin petición abierta ignorada")

            // Llamada RPC del cliente (id numérico ⇒ hay que responder).
            id != null && method != null -> dispatchClientCall(id, method, obj["params"])

            // id sin method: petición inválida (§2.2: -32600 Invalid Request).
            id != null -> sendInvalidRequest(id)

            // Notificación cliente→servidor (sin id): tolerada, sin respuesta.
            else -> gateway.log("notificación '$method' ignorada")
        }
    }

    private suspend fun sendInvalidRequest(id: JsonElement) {
        sendError(id, JSON_RPC_INVALID_REQUEST, "invalid request: falta 'method'")
    }

    /** `params` de la llamada: ausente → {}, objeto → tal cual, otro tipo → -32602. */
    private suspend fun dispatchClientCall(
        id: JsonElement,
        method: String,
        params: JsonElement?,
    ) {
        when {
            params == null -> dispatchCall(id, method, JsonObject(emptyMap()))
            params is JsonObject -> dispatchCall(id, method, params)
            // `"params": "x"` o array: params inválidos, no params vacíos.
            else -> sendError(id, JSON_RPC_INVALID_PARAMS, "invalid params: debe ser un objeto")
        }
    }

    private suspend fun dispatchCall(
        id: JsonElement,
        method: String,
        params: JsonObject,
    ) {
        gateway.receivedCalls.add(
            buildJsonObject {
                put("method", method)
                put("params", params)
            },
        )
        try {
            val result = gateway.dispatcher.dispatch(this, method, params)
            send(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("result", result)
                },
            )
        } catch (e: RpcErrorException) {
            sendError(id, e.code, e.message, e.data)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            // Param con tipo inesperado (p. ej. {"session_id":{}} → str() lanza):
            // la conexión NO muere — responde -32602 como un dispatcher real.
            sendError(id, JSON_RPC_INVALID_PARAMS, "invalid params: ${e.message}")
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            gateway.log("dispatch de '$method' falló (${e::class.simpleName}): ${e.message}")
            sendError(id, JSON_RPC_INTERNAL_ERROR, "internal error")
        }
        drainAfterResponse()
    }

    private suspend fun sendError(
        id: JsonElement,
        code: Int,
        message: String,
        data: JsonElement? = null,
    ) {
        send(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put(
                    "error",
                    buildJsonObject {
                        put("code", code)
                        put("message", message)
                        if (data != null) {
                            put("data", data)
                        }
                    },
                )
            },
        )
    }

    private suspend fun drainAfterResponse() {
        while (true) {
            val action = afterResponse.removeFirstOrNull() ?: break
            action()
        }
    }

    /** Encola trabajo que corre tras enviar la respuesta RPC en curso (arranque de turnos). */
    fun enqueueAfterResponse(action: suspend () -> Unit) {
        afterResponse.addLast(action)
    }

    // --- peticiones servidor→cliente (§2.5) ---

    /**
     * Emite `{"jsonrpc":"2.0","id":"srq-…","method":…,"params":…}` y deja la
     * petición ABIERTA hasta que el cliente responda (frame con el mismo id),
     * se resuelva por `request.answer`/`approval.respond` o se cancele.
     */
    internal suspend fun sendServerRequest(
        session: FakeSession,
        method: String,
        params: JsonObject,
        forcedId: String?,
    ): OpenRequest {
        val id = forcedId ?: gateway.store.nextSrqId()
        val merged =
            buildJsonObject {
                // session_id runtime se autocompleta; request_id para approval.
                put("session_id", session.runtimeId)
                if (method == "approval") {
                    put("request_id", gateway.store.nextRequestId())
                }
                params.forEach { (k, v) -> put(k, v) }
            }
        val open = OpenRequest(id = id, method = method, params = merged, session = session, conn = this)
        gateway.store.openRequestsById[id] = open
        session.openRequests[id] = open
        if (method == "approval") {
            val requestId = merged["request_id"]?.jsonPrimitive?.contentOrNull ?: id
            session.pendingApprovals[requestId] =
                PendingApproval(requestId = requestId, srqId = id, session = session, params = merged)
        }
        send(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", merged)
            },
        )
        return open
    }

    /** `{"id":"srq-N","result"|"error":…}` del cliente: correlaciona y despierta al guion. */
    private fun resolveServerRequest(
        id: String,
        frame: JsonObject,
    ) {
        val open = gateway.store.openRequestsById.remove(id)
        if (open == null) {
            gateway.log("respuesta a '$id' sin petición abierta (ya respondida o ajena)")
            return
        }
        settleRequest(open, frame)
    }

    /** Cierra una petición abierta registrando la respuesta; la usan frame, request.answer y approval.respond. */
    internal fun settleRequest(
        open: OpenRequest,
        responseFrame: JsonObject,
    ) {
        open.session.openRequests.remove(open.id)
        if (open.method == "approval") {
            val requestId = open.params["request_id"]?.jsonPrimitive?.contentOrNull
            if (requestId != null) {
                open.session.pendingApprovals.remove(requestId)
            }
        }
        gateway.answeredRequests.add(
            AnsweredRequest(
                id = open.id,
                method = open.method,
                result = responseFrame["result"],
                error = responseFrame["error"],
            ),
        )
        open.response.complete(responseFrame)
    }

    /** Retira la petición (request.cancel o interrupt) completando al que espera con un frame de cancelación. */
    internal fun withdrawRequest(
        open: OpenRequest,
        reason: String,
    ) {
        if (gateway.store.openRequestsById.remove(open.id) == null) {
            return
        }
        open.session.openRequests.remove(open.id)
        open.response.complete(
            buildJsonObject {
                put(
                    "error",
                    buildJsonObject {
                        put("code", -32800)
                        put("message", "request cancelled: $reason")
                    },
                )
            },
        )
    }

    /** El socket murió: las peticiones emitidas por él dejan de esperar (siguen abiertas para re-entrega). */
    fun onDisconnected() {
        gateway.store.openRequestsById.values
            .filter { it.conn === this }
            .forEach { open ->
                // La respuesta ya no puede llegar por ESTE socket: despierta al
                // guion (que muere con el scope del socket) pero conserva la
                // entrada en open_requests para el próximo cliente.
                open.response.complete(
                    buildJsonObject {
                        put(
                            "error",
                            buildJsonObject {
                                put("code", -32001)
                                put("message", "client disconnected")
                            },
                        )
                    },
                )
            }
        gateway.store.pendingCommands.values
            .filter { it.conn === this }
            .forEach { cmd ->
                cmd.result.complete(
                    buildJsonObject {
                        put("ok", false)
                        put("error", "client disconnected")
                    },
                )
            }
    }

    private companion object {
        const val JSON_RPC_INVALID_REQUEST = -32600
        const val JSON_RPC_INVALID_PARAMS = -32602
        const val JSON_RPC_INTERNAL_ERROR = -32603
    }
}

/** Error JSON-RPC hacia el cliente (`{"error":{code,message,data?}}`). */
class RpcErrorException(
    val code: Int,
    override val message: String,
    val data: JsonElement? = null,
) : Exception(message)
