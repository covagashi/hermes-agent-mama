package ai.hermes.mama.testing

import ai.hermes.mama.testing.FakeGateway.Companion.str
import ai.hermes.mama.testing.FakeGatewayScript.TurnScript
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Tabla de métodos cliente→servidor del [FakeGateway] (ROADMAP §2.3): cada
 * método conocido tiene su handler; lo desconocido responde `-32601` para no
 * bloquear al cliente (§2.2).
 *
 * Los handlers devuelven el `result` tal cual viaja por el cable. Errores de
 * dominio → [RpcErrorException] (la conexión lo convierte en `error` JSON-RPC).
 */
@Suppress("TooManyFunctions")
internal class RpcDispatcher(
    private val gateway: FakeGateway,
) {
    private val store
        get() = gateway.store

    private val handlers: Map<String, suspend (WsConnection, JsonObject) -> JsonElement> =
        mapOf(
            "gateway.ping" to ::ping,
            "gateway.capabilities" to ::capabilities,
            "session.list" to ::sessionList,
            "session.create" to ::sessionCreate,
            "session.resume" to ::sessionResume,
            "session.history" to ::sessionHistory,
            "session.title" to ::sessionTitle,
            "session.delete" to ::sessionDelete,
            "session.interrupt" to ::sessionInterrupt,
            // Alias pedido en la tarea B5: el botón Parar corta el guion igual que session.interrupt.
            "prompt.stop" to ::sessionInterrupt,
            "session.events.since" to ::sessionEventsSince,
            "prompt.submit" to ::promptSubmit,
            "approval.pending" to ::approvalPending,
            "approval.respond" to ::approvalRespond,
            "request.answer" to ::requestAnswer,
            "browser.controller.register" to ::browserRegister,
            "browser.controller.result" to ::browserResult,
            "browser.controller.heartbeat" to ::browserHeartbeat,
            "browser.controller.detach" to ::browserDetach,
            "image.attach_bytes" to ::imageAttach,
            "file.attach" to ::fileAttach,
        )

    suspend fun dispatch(
        conn: WsConnection,
        method: String,
        params: JsonObject,
    ): JsonElement {
        val handler =
            handlers[method]
                ?: throw RpcErrorException(JSON_RPC_METHOD_NOT_FOUND, "method not found: $method")
        return handler(conn, params)
    }

    // --- gateway ---

    /** OJO (errata del roadmap corregida): `gateway.ping` por WS devuelve `OkResult`, no `PingResult`. */
    private suspend fun ping(
        @Suppress("unused") conn: WsConnection,
        @Suppress("unused") params: JsonObject,
    ): JsonElement = buildJsonObject { put("ok", true) }

    private suspend fun capabilities(
        @Suppress("unused") conn: WsConnection,
        @Suppress("unused") params: JsonObject,
    ): JsonElement = buildJsonObject { put("per_session_exclusive_submit", false) }

    // --- sesiones ---

    private suspend fun sessionList(
        @Suppress("unused") conn: WsConnection,
        params: JsonObject,
    ): JsonElement {
        val limit = params["limit"]?.jsonPrimitive?.longOrNull
        val includeHidden = params["include_hidden"]?.jsonPrimitive?.booleanOrNull ?: false
        val titleFilter = params.str("title")
        val rows =
            store.sessions.values
                .filter { includeHidden || !it.hidden }
                .filter { titleFilter == null || it.title == titleFilter }
                .sortedByDescending { it.startedAt }
                .let { if (limit != null && limit >= 0) it.take(limit.toInt()) else it }
                .map(::sessionRow)
        return buildJsonObject { put("sessions", JsonArray(rows)) }
    }

    private fun sessionRow(session: FakeSession): JsonObject =
        buildJsonObject {
            put("id", session.storedId)
            put("title", session.title)
            put("preview", session.preview)
            put("started_at", session.startedAt)
            put("message_count", session.messages.size)
            put("source", session.source)
        }

    private fun liveInfo(session: FakeSession): JsonObject =
        buildJsonObject {
            put("approval_mode", "manual")
            put("running", session.running)
            put("title", session.title)
            put("stored_session_id", session.storedId)
        }

    private suspend fun sessionCreate(
        conn: WsConnection,
        params: JsonObject,
    ): JsonElement {
        val session =
            FakeSession(
                storedId = store.nextStoredId(),
                runtimeId = store.nextRuntimeId(),
                title = params.str("title") ?: "Chat fake",
                preview = "",
                startedAt = nowSeconds(),
                source = params.str("source") ?: "android",
                hidden = params["hidden"]?.jsonPrimitive?.booleanOrNull ?: false,
                initialMessages =
                    (params["messages"] as? JsonArray).orEmpty().mapIndexedNotNull { i, el ->
                        (el as? JsonObject)?.let { seed -> seedToTranscript(seed, i) }
                    },
            )
        store.add(session)
        conn.enqueueAfterResponse { gateway.broadcastSessionsChanged() }
        return buildJsonObject {
            put("session_id", session.runtimeId)
            put("stored_session_id", session.storedId)
            put("message_count", session.messages.size)
            put("messages", JsonArray(session.messages))
            put("info", liveInfo(session))
        }
    }

    /** `SeedMessage` → fila de transcript (`content` es alias legacy de `text`). */
    private fun seedToTranscript(
        seed: JsonObject,
        index: Int,
    ): JsonObject =
        buildJsonObject {
            put("role", seed["role"] ?: JsonPrimitive("user"))
            put("text", seed["text"] ?: seed["content"] ?: JsonPrimitive(""))
            put("timestamp", nowSeconds())
            put("row_id", index + 1L)
        }

    private suspend fun sessionResume(
        @Suppress("unused") conn: WsConnection,
        params: JsonObject,
    ): JsonElement {
        val session = requireSession(params)
        return buildJsonObject {
            put("session_id", session.runtimeId)
            put("stored_session_id", session.storedId)
            put("message_count", session.messages.size)
            put("messages", JsonArray(session.messages))
            put("info", liveInfo(session))
            put("resumed", "resumed")
            put("running", session.running)
            put("status", if (session.running) "running" else "idle")
            openRequests(session)?.let { put("open_requests", it) }
            pendingApproval(session)?.let { put("pending_approval", it) }
        }
    }

    private suspend fun sessionHistory(
        @Suppress("unused") conn: WsConnection,
        params: JsonObject,
    ): JsonElement {
        val session = requireSession(params)
        return buildJsonObject {
            put("count", session.messages.size)
            put("messages", JsonArray(session.messages))
        }
    }

    private suspend fun sessionTitle(
        conn: WsConnection,
        params: JsonObject,
    ): JsonElement {
        val session = requireSession(params)
        val newTitle = params.str("title") ?: return buildJsonObject { put("title", session.title) }
        session.title = newTitle
        val stored = session.storedId
        conn.enqueueAfterResponse {
            conn.emitEvent(
                "session.title",
                session,
                buildJsonObject {
                    // El payload lleva el STORED id (como el _on_session_title real).
                    put("session_id", stored)
                    put("title", newTitle)
                },
            )
            gateway.broadcastSessionsChanged()
        }
        return buildJsonObject {
            put("title", newTitle)
            put("session_key", stored)
            put("pending", false)
        }
    }

    private suspend fun sessionDelete(
        conn: WsConnection,
        params: JsonObject,
    ): JsonElement {
        val session = requireSession(params)
        session.turnJob?.cancel()
        store.remove(session.storedId)
        conn.enqueueAfterResponse { gateway.broadcastSessionsChanged() }
        return buildJsonObject { put("deleted", session.storedId) }
    }

    private suspend fun sessionInterrupt(
        @Suppress("unused") conn: WsConnection,
        params: JsonObject,
    ): JsonElement {
        val session = requireSession(params)
        val job = session.turnJob
        return if (job == null || !session.running) {
            buildJsonObject { put("status", "not_interrupted") }
        } else {
            job.cancel()
            buildJsonObject {
                put("status", "interrupted")
                put("interrupted", true)
            }
        }
    }

    private suspend fun sessionEventsSince(
        @Suppress("unused") conn: WsConnection,
        params: JsonObject,
    ): JsonElement {
        val session = requireSession(params)
        val lastSeen = params["last_seen"]?.jsonPrimitive?.longOrNull ?: 0L
        val missed =
            session.eventLog.filter {
                (it["seq"]?.jsonPrimitive?.longOrNull ?: 0L) > lastSeen
            }
        return buildJsonObject {
            put("events", JsonArray(missed))
            put("latest_seq", session.seq.get())
            put("truncated", false)
            put("count", missed.size)
            put("epoch", gateway.script.replayEpoch)
            put("open_requests", openRequests(session) ?: JsonArray(emptyList<JsonElement>()))
        }
    }

    // --- prompt ---

    private suspend fun promptSubmit(
        conn: WsConnection,
        params: JsonObject,
    ): JsonElement {
        val session = requireSession(params)
        val text = promptText(params["text"])
        val turn = gateway.script.turnFor(text) ?: FALLBACK_TURN
        turn.submitError?.let { throw RpcErrorException(it.code, it.message) }

        session.messages.add(
            buildJsonObject {
                put("role", "user")
                put("text", text)
                put("timestamp", nowSeconds())
                put("row_id", session.messages.size + 1L)
            },
        )
        session.preview = text.take(PREVIEW_CHARS)
        val queued = session.turnMutex.isLocked
        conn.enqueueAfterResponse {
            session.turnJob =
                conn.scope.launch {
                    TurnRunner(gateway, conn, session, turn).run()
                }
        }
        return turn.submitStatus ?: buildJsonObject { put("status", if (queued) "queued" else "streaming") }
    }

    /** `prompt.text` suele ser string; un payload estructurado se resume a texto plano. */
    private fun promptText(text: JsonElement?): String =
        when (text) {
            null, JsonNull -> ""
            is JsonPrimitive -> text.contentOrNull ?: text.toString()
            else -> text.toString()
        }

    // --- approvals / peticiones ---

    private suspend fun approvalPending(
        @Suppress("unused") conn: WsConnection,
        params: JsonObject,
    ): JsonElement {
        val session = requireSession(params)
        val approvals =
            session.pendingApprovals.values.map { pending ->
                // Los params del `approval` ya son el PendingApproval del contrato.
                buildJsonObject {
                    pending.params.forEach { (k, v) ->
                        if (k != "session_id") {
                            put(k, v)
                        }
                    }
                }
            }
        return buildJsonObject { put("approvals", JsonArray(approvals)) }
    }

    private suspend fun approvalRespond(
        conn: WsConnection,
        params: JsonObject,
    ): JsonElement {
        val session = requireSession(params)
        val choice = params.str("choice")
        val respondAll = params["all"]?.jsonPrimitive?.booleanOrNull ?: false
        val requestId = params.str("request_id")
        val targets =
            when {
                respondAll -> session.pendingApprovals.values.toList()
                requestId != null -> listOfNotNull(session.pendingApprovals[requestId])
                else ->
                    session.pendingApprovals.values
                        .sortedBy { it.requestId }
                        .take(1)
            }
        targets.forEach { pending ->
            session.pendingApprovals.remove(pending.requestId)
            val open = store.openRequestsById.remove(pending.srqId)
            if (open != null) {
                conn.settleRequest(
                    open,
                    buildJsonObject {
                        put(
                            "result",
                            buildJsonObject {
                                put("choice", choice ?: "once")
                            },
                        )
                    },
                )
            }
        }
        return buildJsonObject { put("resolved", targets.size) }
    }

    private suspend fun requestAnswer(
        conn: WsConnection,
        params: JsonObject,
    ): JsonElement {
        val id = params.str("id") ?: throw RpcErrorException(JSON_RPC_INVALID_PARAMS, "request.answer sin 'id'")
        val result = params["result"] as? JsonObject ?: JsonObject(emptyMap())
        val open = store.openRequestsById.remove(id)
        return if (open == null) {
            buildJsonObject { put("status", "expired") }
        } else {
            conn.settleRequest(open, buildJsonObject { put("result", result) })
            buildJsonObject { put("status", "ok") }
        }
    }

    // --- controlador de navegador (§2.6) ---

    private suspend fun browserRegister(
        conn: WsConnection,
        params: JsonObject,
    ): JsonElement {
        val session = requireSession(params)
        val version = params["protocol_version"] as? JsonPrimitive
        val versionOk = version?.intOrNull == 1 || version?.contentOrNull == "1"
        if (!versionOk) {
            // Mismo código que el broker real cuando el flag está apagado o la versión no casa.
            throw RpcErrorException(BROWSER_CONTROL_REJECTED, "browser controller not available")
        }
        val controllerId =
            params.str("controller_id")
                ?: throw RpcErrorException(JSON_RPC_INVALID_PARAMS, "falta controller_id")
        val profileId = params.str("browser_profile_id") ?: "mama-webview"
        val capabilities =
            (params["capabilities"] as? JsonArray).orEmpty().mapNotNull {
                it.jsonPrimitive.contentOrNull
            }
        session.controllers[controllerId] =
            ControllerRegistration(
                controllerId = controllerId,
                browserProfileId = profileId,
                capabilities = capabilities,
                session = session,
            )
        return buildJsonObject {
            put(
                "scope",
                buildJsonObject {
                    put("principal_id", "${conn.identity.provider}:${conn.identity.userId}")
                    put("profile_id", "default")
                    put("session_id", session.runtimeId)
                    put("controller_id", controllerId)
                    put("browser_profile_id", profileId)
                    put("transport_family", "websocket")
                    put("capabilities", JsonArray(capabilities.map { JsonPrimitive(it) }))
                },
            )
        }
    }

    private suspend fun browserResult(
        @Suppress("unused") conn: WsConnection,
        params: JsonObject,
    ): JsonElement {
        val commandId = params.str("command_id")
        val pending = commandId?.let { store.pendingCommands.remove(it) }
        return if (pending == null) {
            buildJsonObject { put("accepted", false) }
        } else {
            pending.result.complete(params)
            gateway.browserCommandResults.add(
                AnsweredRequest(
                    id = pending.commandId,
                    method = pending.action,
                    result = params["result"],
                    error = params["error"],
                ),
            )
            buildJsonObject { put("accepted", true) }
        }
    }

    private suspend fun browserHeartbeat(
        @Suppress("unused") conn: WsConnection,
        params: JsonObject,
    ): JsonElement {
        requireSession(params)
        return buildJsonObject { put("ok", true) }
    }

    private suspend fun browserDetach(
        @Suppress("unused") conn: WsConnection,
        params: JsonObject,
    ): JsonElement {
        val session = requireSession(params)
        session.controllers.clear()
        return buildJsonObject { put("detached", true) }
    }

    // --- adjuntos ---

    private suspend fun imageAttach(
        @Suppress("unused") conn: WsConnection,
        params: JsonObject,
    ): JsonElement {
        requireSession(params)
        return buildJsonObject {
            put("attached", true)
            put("name", params.str("filename") ?: "imagen.jpg")
            put("width", 1024)
            put("height", 768)
        }
    }

    private suspend fun fileAttach(
        @Suppress("unused") conn: WsConnection,
        params: JsonObject,
    ): JsonElement {
        requireSession(params)
        val name = params.str("name") ?: "documento.pdf"
        return buildJsonObject {
            put("attached", true)
            put("name", name)
            put("path", "/fake/uploads/$name")
            put("ref_path", "/fake/uploads/$name")
            put("ref_text", "[contenido fake de $name]")
            put("uploaded", true)
        }
    }

    // --- helpers ---

    private fun requireSession(params: JsonObject): FakeSession =
        store.resolve(params.str("session_id"))
            ?: throw RpcErrorException(
                JSON_RPC_INVALID_PARAMS,
                "session not found: ${params.str("session_id") ?: "<null>"}",
            )

    private fun openRequests(session: FakeSession): JsonArray? =
        session.openRequests.values
            .takeIf { it.isNotEmpty() }
            ?.let { entries ->
                JsonArray(
                    entries.map { open ->
                        buildJsonObject {
                            put("id", open.id)
                            put("method", open.method)
                            put("params", open.params)
                        }
                    },
                )
            }

    private fun pendingApproval(session: FakeSession): JsonObject? =
        session.pendingApprovals.values.firstOrNull()?.let { pending ->
            buildJsonObject {
                pending.params.forEach { (k, v) ->
                    if (k != "session_id") {
                        put(k, v)
                    }
                }
            }
        }

    private fun nowSeconds(): Double = System.currentTimeMillis() / 1000.0

    companion object {
        private const val JSON_RPC_METHOD_NOT_FOUND = -32601
        private const val JSON_RPC_INVALID_PARAMS = -32602
        private const val BROWSER_CONTROL_REJECTED = 4403
        private const val PREVIEW_CHARS = 80

        /** Turno usado cuando el guion no casa nada: el prompt se ve y se señala el error. */
        private val FALLBACK_TURN =
            TurnScript(
                matcher = null,
                submitStatus = null,
                submitError = null,
                steps =
                    listOf(
                        FakeGatewayScript.ScriptStep.Event(
                            type = "message.start",
                            payload = JsonObject(emptyMap()),
                            broadcast = false,
                        ),
                        FakeGatewayScript.ScriptStep.Event(
                            type = "message.complete",
                            payload =
                                buildJsonObject {
                                    put("status", "error")
                                    put("error", "guion sin turno para este prompt")
                                    put("failure_reason", "fake_no_script")
                                },
                            broadcast = false,
                        ),
                    ),
            )
    }
}
