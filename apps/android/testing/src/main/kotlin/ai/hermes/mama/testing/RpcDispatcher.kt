package ai.hermes.mama.testing

import ai.hermes.mama.testing.FakeGateway.Companion.str
import ai.hermes.mama.testing.FakeGatewayScript.TurnScript
import kotlinx.coroutines.delay
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
            put("message_count", session.messageCount())
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
            val messages = session.messagesSnapshot()
            put("session_id", session.runtimeId)
            put("stored_session_id", session.storedId)
            put("message_count", messages.size)
            put("messages", JsonArray(messages))
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
        val session = requireStoredSession(params)
        // El resume real sube la sesión al registro VIVO (su _resume_* crea o
        // reutiliza el runtime): desde aquí session.delete → 4023, no deleted.
        store.markLive(session)
        return buildJsonObject {
            val messages = session.messagesSnapshot()
            put("session_id", session.runtimeId)
            put("stored_session_id", session.storedId)
            // El real devuelve session_key (stored) y `resumed` = el id pedido.
            put("session_key", session.storedId)
            put("message_count", messages.size)
            put("messages", JsonArray(messages))
            put("info", liveInfo(session))
            put("resumed", session.storedId)
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
        val session = requireRuntimeSession(params)
        return buildJsonObject {
            val messages = session.messagesSnapshot()
            put("count", messages.size)
            put("messages", JsonArray(messages))
        }
    }

    private suspend fun sessionTitle(
        conn: WsConnection,
        params: JsonObject,
    ): JsonElement {
        val session = requireRuntimeSession(params)
        if (params["title"] == null) {
            // Lectura (real: {"title": …, "session_key": …}).
            return buildJsonObject {
                put("title", session.title)
                put("session_key", session.storedId)
            }
        }
        val newTitle =
            params.str("title")?.takeIf { it.isNotBlank() }
                ?: throw RpcErrorException(TITLE_REQUIRED, "title required")
        session.title = newTitle
        conn.enqueueAfterResponse {
            // El real emite session.info al renombrar por RPC (el evento
            // session.title lo emite el AUTO-titling tras el turno, no el RPC).
            conn.emitEvent("session.info", session, liveInfo(session))
            gateway.broadcastSessionsChanged()
        }
        return buildJsonObject {
            put("pending", false)
            put("title", newTitle)
        }
    }

    private suspend fun sessionDelete(
        conn: WsConnection,
        params: JsonObject,
    ): JsonElement {
        val id = params.str("session_id")
        if (id.isNullOrEmpty()) {
            rpcError(SESSION_ID_REQUIRED, "session_id required")
        }
        // Orden del real (methods_session.py): primero el registro de sesiones
        // VIVAS — 4023 aunque el turno esté en reposo —, luego el stored (4007).
        if (store.isLive(id)) {
            rpcError(SESSION_ACTIVE, "cannot delete an active session")
        }
        val session = store.remove(id) ?: rpcError(SESSION_NOT_FOUND_STORED, "session not found")
        conn.enqueueAfterResponse { gateway.broadcastSessionsChanged() }
        return buildJsonObject { put("deleted", session.storedId) }
    }

    private suspend fun sessionInterrupt(
        @Suppress("unused") conn: WsConnection,
        params: JsonObject,
    ): JsonElement {
        val session = requireRuntimeSession(params)
        if (params.str("expected_hosted_task_id") != null) {
            // Rama not_interrupted del real (methods_session.py): el fake nunca
            // aloja tareas compute-host, así que el expected jamás coincide.
            return buildJsonObject {
                put("status", "not_interrupted")
                put("interrupted", false)
            }
        }
        // Interrupt del real es INCONDICIONAL sobre una sesión viva: responde
        // {"status":"interrupted"} incluso sin turno (su _interrupt_session_turn
        // es un no-op sin turno). Cancela el job VIVO si lo hay.
        session.turnJob?.cancel()
        return buildJsonObject { put("status", "interrupted") }
    }

    private suspend fun sessionEventsSince(
        @Suppress("unused") conn: WsConnection,
        params: JsonObject,
    ): JsonElement {
        // El real NO resuelve la sesión (event_replay va por sid): un id
        // desconocido devuelve replay vacío; `last_seen` no entero → -32602.
        val lastSeenEl = params["last_seen"]
        val lastSeen =
            when {
                lastSeenEl == null || lastSeenEl is JsonNull -> 0L
                lastSeenEl is JsonPrimitive ->
                    lastSeenEl.longOrNull
                        ?: throw RpcErrorException(
                            JSON_RPC_INVALID_PARAMS,
                            "invalid params: last_seen must be an integer",
                        )

                else ->
                    throw RpcErrorException(
                        JSON_RPC_INVALID_PARAMS,
                        "invalid params: last_seen must be an integer",
                    )
            }
        val session = store.resolveRuntime(params.str("session_id"))
        val missed =
            session?.eventLogSnapshot().orEmpty().filter {
                (it["seq"]?.jsonPrimitive?.longOrNull ?: 0L) > lastSeen
            }
        return buildJsonObject {
            put("events", JsonArray(missed))
            put("latest_seq", session?.seq?.get() ?: 0L)
            put("truncated", false)
            put("count", missed.size)
            put("epoch", gateway.script.replayEpoch)
            put(
                "open_requests",
                session?.let { openRequests(it) } ?: JsonArray(emptyList<JsonElement>()),
            )
        }
    }

    // --- prompt ---

    private suspend fun promptSubmit(
        conn: WsConnection,
        params: JsonObject,
    ): JsonElement {
        val session = requireRuntimeSession(params)
        val text = promptText(params["text"])
        val turn = gateway.script.turnFor(text) ?: FALLBACK_TURN
        turn.submitError?.let { throw RpcErrorException(it.code, it.message) }
        // `submit_delay_ms`: latencia del servidor antes de aceptar el submit —
        // la fila `user` aún no existe y la app muestra su burbuja optimista.
        if (turn.submitDelayMs > 0) {
            delay(turn.submitDelayMs)
        }

        val rowId = session.messageCount() + 1L
        session.addMessage(
            buildJsonObject {
                put("role", "user")
                put("text", text)
                put("timestamp", nowSeconds())
                put("row_id", rowId)
            },
        )
        session.preview = text.take(PREVIEW_CHARS)
        val queued = session.turnMutex.isLocked
        conn.enqueueAfterResponse {
            // El job se publica en session.turnJob DENTRO del runner tras
            // adquirir el mutex: interrupt/delete deben cortar el turno VIVO,
            // no el que espera encolado.
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
        val session = requireRuntimeSession(params)
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
        val session = requireRuntimeSession(params)
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
                                // Default del real (methods_prompt.py): "deny", no "once".
                                put("choice", choice ?: "deny")
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

    /**
     * Puerta compartida de `browser.controller.*` (methods_browser_control.py):
     * todas fallan cerrado con **4403** — nunca -32602 — si falta flag, sesión
     * del transport, identidad autenticada o scope registrado.
     */
    private fun controllerSession(
        conn: WsConnection,
        params: JsonObject,
    ): FakeSession {
        if (!conn.identity.authenticated) {
            throw RpcErrorException(BROWSER_FORBIDDEN, IDENTITY_REQUIRED)
        }
        return store.resolveRuntime(params.str("session_id"))
            ?: throw RpcErrorException(BROWSER_FORBIDDEN, "session is not owned by this transport")
    }

    /** Scope del controlador registrado POR ESTA conexión en la sesión (is_owner del broker). */
    private fun requireControllerScope(
        conn: WsConnection,
        params: JsonObject,
        missingMessage: String = NO_CONTROLLER,
    ): ControllerRegistration {
        val session = controllerSession(conn, params)
        return session.controllers.values.firstOrNull { it.conn === conn }
            ?: throw RpcErrorException(BROWSER_FORBIDDEN, missingMessage)
    }

    private suspend fun browserRegister(
        conn: WsConnection,
        params: JsonObject,
    ): JsonElement {
        if (!gateway.script.browser.enabled) {
            rpcError(BROWSER_FORBIDDEN, "browser.extension_control.enabled is not set")
        }
        val version = params["protocol_version"] as? JsonPrimitive
        if (version == null || version.isString || version.intOrNull != BROWSER_PROTOCOL_VERSION) {
            rpcError(
                BROWSER_FORBIDDEN,
                "unsupported browser-control protocol version; expected $BROWSER_PROTOCOL_VERSION",
            )
        }
        val session = controllerSession(conn, params)
        val controllerId = params.str("controller_id").orEmpty()
        val profileId = params.str("browser_profile_id").orEmpty()
        if (controllerId.isEmpty() || profileId.isEmpty() || session.profile.isEmpty()) {
            rpcError(
                BROWSER_FORBIDDEN,
                "controller_id, browser_profile_id, and server session profile are required",
            )
        }
        val requested =
            (params["capabilities"] as? JsonArray).orEmpty().mapNotNull {
                (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
            }
        val capabilities = filterCapabilities(requested)
        if (capabilities.isEmpty()) {
            rpcError(BROWSER_FORBIDDEN, "no permitted controller capabilities requested")
        }
        session.controllers[controllerId] =
            ControllerRegistration(
                controllerId = controllerId,
                browserProfileId = profileId,
                capabilities = capabilities,
                session = session,
                conn = conn,
            )
        return buildJsonObject {
            put(
                "scope",
                buildJsonObject {
                    put("principal_id", "${conn.identity.provider}:${conn.identity.userId}")
                    put("profile_id", session.profile)
                    put("session_id", session.runtimeId)
                    put("controller_id", controllerId)
                    put("browser_profile_id", profileId)
                    put("transport_family", CONTROLLER_TRANSPORT_FAMILY)
                    put("capabilities", JsonArray(capabilities.sorted().map { JsonPrimitive(it) }))
                },
            )
        }
    }

    /** Allowlist del broker real: base + artifacts; las dev sólo con developer_mode del guion. */
    private fun filterCapabilities(requested: List<String>): Set<String> {
        val allowed =
            if (gateway.script.browser.developerMode) {
                BROWSER_CAPABILITIES + BROWSER_DEV_CAPABILITIES
            } else {
                BROWSER_CAPABILITIES
            }
        return requested.filterTo(mutableSetOf()) { it in allowed }
    }

    private suspend fun browserResult(
        conn: WsConnection,
        params: JsonObject,
    ): JsonElement {
        val scope = requireControllerScope(conn, params)
        val commandId =
            params.str("command_id")
                ?: rpcError(BROWSER_FORBIDDEN, "command_id required")
        val pending = store.pendingCommands[commandId]
        // El broker rechaza (accepted=false) un result de scope ajeno, no 4403.
        val accepted =
            pending != null &&
                pending.session === scope.session &&
                pending.controllerId == scope.controllerId &&
                store.pendingCommands.remove(commandId, pending)
        if (accepted) {
            pending.result.complete(params)
            gateway.browserCommandResults.add(
                AnsweredRequest(
                    id = pending.commandId,
                    method = pending.action,
                    result = params["result"],
                    error = params["error"],
                ),
            )
        }
        return buildJsonObject { put("accepted", accepted) }
    }

    private suspend fun browserHeartbeat(
        conn: WsConnection,
        params: JsonObject,
    ): JsonElement {
        requireControllerScope(conn, params)
        return buildJsonObject { put("ok", true) }
    }

    private suspend fun browserDetach(
        conn: WsConnection,
        params: JsonObject,
    ): JsonElement {
        val scope = requireControllerScope(conn, params, missingMessage = NOT_OWNED)
        scope.session.controllers.values
            .removeIf { it.conn === conn }
        return buildJsonObject { put("detached", true) }
    }

    // --- adjuntos ---

    private suspend fun imageAttach(
        @Suppress("unused") conn: WsConnection,
        params: JsonObject,
    ): JsonElement {
        requireRuntimeSession(params)
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
        requireRuntimeSession(params)
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

    /**
     * RPC con sesión VIVA (prompt.submit, session.history, interrupt, approvals,
     * attach…): el backend resuelve por id RUNTIME y da **4001** si no está
     * (`_sess_nowait`). Sin fallback por título/stored — un id erróneo falla.
     */
    private fun requireRuntimeSession(params: JsonObject): FakeSession =
        store.resolveRuntime(params.str("session_id"))
            ?: throw RpcErrorException(
                SESSION_NOT_FOUND_RUNTIME,
                "session not found: ${params.str("session_id") ?: "<null>"}",
            )

    /**
     * RPC sobre el REGISTRO stored (session.resume/delete): el backend resuelve
     * por `session_key` — falta → 4006, desconocido → 4007.
     */
    private fun requireStoredSession(params: JsonObject): FakeSession {
        val id = params.str("session_id")
        if (id.isNullOrEmpty()) {
            throw RpcErrorException(SESSION_ID_REQUIRED, "session_id required")
        }
        return store.resolveStored(id)
            ?: throw RpcErrorException(SESSION_NOT_FOUND_STORED, "session not found")
    }

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

    /** `throw RpcErrorException` sin `throw` en el call-site (un solo punto para el lint). */
    private fun rpcError(
        code: Int,
        message: String,
    ): Nothing = throw RpcErrorException(code, message)

    companion object {
        private const val JSON_RPC_METHOD_NOT_FOUND = -32601
        private const val JSON_RPC_INVALID_PARAMS = -32602

        // Códigos del backend real (tui_gateway): sesión runtime muerta → 4001;
        // session_id ausente → 4006; stored desconocido → 4007; delete de sesión
        // viva → 4023; puertas del controlador de navegador → 4403.
        private const val SESSION_NOT_FOUND_RUNTIME = 4001
        private const val SESSION_ID_REQUIRED = 4006
        private const val SESSION_NOT_FOUND_STORED = 4007
        private const val TITLE_REQUIRED = 4021
        private const val SESSION_ACTIVE = 4023
        private const val BROWSER_FORBIDDEN = 4403
        private const val BROWSER_PROTOCOL_VERSION = 1
        private const val CONTROLLER_TRANSPORT_FAMILY = "cloud-ticket-ws"
        private const val IDENTITY_REQUIRED = "authenticated controller identity required"
        private const val NO_CONTROLLER = "no controller registered for this session"
        private const val NOT_OWNED = "controller scope is owned by a different transport"
        private const val PREVIEW_CHARS = 80

        /** `BROWSER_CONTROL_CAPABILITIES` + artifact caps del broker real (allowlist §2.6). */
        private val BROWSER_CAPABILITIES =
            setOf(
                "controller.noop",
                "browser_back",
                "browser_click",
                "browser_navigate",
                "browser_press",
                "browser_screenshot",
                "browser_scroll",
                "browser_snapshot",
                "browser_tab_activate",
                "browser_tabs",
                "browser_type",
                "browser_artifact_download",
                "browser_artifact_upload",
            )

        /** `BROWSER_CONTROL_DEVELOPER_CAPABILITIES` — sólo con `browser.developer_mode` del guion. */
        private val BROWSER_DEV_CAPABILITIES = setOf("browser_cdp", "browser_evaluate")

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
