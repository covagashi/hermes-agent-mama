package ai.hermes.mama.testing

import ai.hermes.mama.testing.FakeGateway.Companion.str
import ai.hermes.mama.testing.FakeGatewayScript.ScriptStep
import ai.hermes.mama.testing.FakeGatewayScript.TurnScript
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.coroutines.coroutineContext

/**
 * Ejecuta los `steps` de un turno del guion sobre una conexión: emite los
 * eventos del streaming (`message.start`/`delta`/`complete`), lanza peticiones
 * `srq-*` y espera la respuesta del cliente, dispara comandos de navegador y
 * espera su `browser.controller.result`.
 *
 * La corrutina vive en el scope de la conexión: si el socket muere, el turno
 * muere con él (los `srq` abiertos quedan para re-entrega por `open_requests`).
 * `session.interrupt` la cancela: el `finally` cierra la burbuja con
 * `message.complete {status:"interrupted"}` y retira las peticiones abiertas
 * de la sesión con `request.cancel {reason:"interrupted"}`, como el real.
 */
internal class TurnRunner(
    private val gateway: FakeGateway,
    private val conn: WsConnection,
    private val session: FakeSession,
    private val turn: TurnScript,
) {
    private var lastResult: JsonElement = JsonNull
    private val streamed = StringBuilder()
    private var startEmitted = false
    private var completeEmitted = false
    private var lastSrqId: String? = null
    private var terminalError: String? = null

    /**
     * Corre el turno bajo `session.turnMutex` (un prompt a la vez por chat, como
     * el backend real). El `finally` corre en `NonCancellable` para que la
     * burbuja siempre se cierre: `complete` normal ya la cerró el guion;
     * `interrupted` tras `session.interrupt`; `error` si un paso rompió.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun run() {
        session.turnMutex.withLock {
            // El job se publica AQUÍ, ya con el mutex: session.interrupt/delete
            // cancelan el turno VIVO — nunca un submit encolado esperando el lock.
            session.turnJob = coroutineContext.job
            session.running = true
            emitSessionInfo()
            try {
                for (step in turn.steps) {
                    execute(step)
                }
            } catch (e: TurnAbort) {
                terminalError = e.errorMessage
            } catch (e: CancellationException) {
                // session.interrupt / socket muerto: el finally cierra la burbuja.
                throw e
            } catch (e: Exception) {
                // Un paso roto del guion no debe tumbar la conexión: error + complete.
                terminalError = "turn step failed: ${e::class.simpleName}"
                gateway.log("paso del guion falló (${e::class.simpleName}): ${e.message}")
            } finally {
                withContext(NonCancellable) {
                    if (terminalError != null) {
                        emitEvent("error", buildJsonObject { put("message", terminalError ?: "turn aborted") })
                    }
                    if (startEmitted && !completeEmitted) {
                        emit(
                            "message.complete",
                            buildJsonObject {
                                if (terminalError != null) {
                                    put("status", "error")
                                    put("error", terminalError)
                                    put("partial", streamed.isNotEmpty())
                                } else {
                                    put("status", "interrupted")
                                    put("partial", true)
                                }
                            },
                        )
                    }
                    withdrawOpenRequests()
                    session.running = false
                    session.turnJob = null
                    emitSessionInfo()
                }
            }
        }
    }

    private suspend fun execute(step: ScriptStep) {
        when (step) {
            is ScriptStep.Event -> emit(step.type, substitute(step.payload) as JsonObject, step.broadcast)
            is ScriptStep.Sleep -> delay(step.millis)
            is ScriptStep.ServerRequest -> runServerRequest(step)
            is ScriptStep.BrowserCommand -> runBrowserCommand(step)
            is ScriptStep.BrowserCancel -> runBrowserCancel(step)
            is ScriptStep.CancelRequest -> runCancelRequest(step)
            is ScriptStep.ToolPair -> runToolPair(step)
            is ScriptStep.RenameSession -> runRename(step.title)
            is ScriptStep.CloseSocket -> {
                conn.closeWith(step.code, step.reason)
                throw TurnAbort(errorMessage = null)
            }
        }
    }

    // --- peticiones servidor→cliente (§2.5) ---

    private suspend fun runServerRequest(step: ScriptStep.ServerRequest) {
        val open = conn.sendServerRequest(session, step.method, substitute(step.params) as JsonObject, step.id)
        lastSrqId = open.id
        if (!step.awaitResponse) {
            return
        }
        val frame =
            try {
                withTimeout(step.timeoutMs) { open.response.await() }
            } catch (ignored: TimeoutCancellationException) {
                // El real emite request.cancel {id,method,reason:"timeout"} al
                // vencer (server_requests.py) — el cliente debe verla, no sólo
                // la retirada silenciosa de open_requests.
                emitRequestCancel(open, "timeout")
                conn.withdrawRequest(open, "timeout")
                throw TurnAbort("timeout esperando respuesta a ${step.method} (${open.id})")
            }
        lastResult = frame["result"] ?: frame["error"] ?: JsonNull
    }

    // --- controlador de navegador (§2.6) ---

    private suspend fun runBrowserCommand(step: ScriptStep.BrowserCommand) {
        val controller = session.controllers.values.firstOrNull()
        if (controller == null) {
            emitEvent(
                "error",
                buildJsonObject { put("message", "sin controlador de navegador registrado") },
            )
            lastResult =
                buildJsonObject {
                    put("success", false)
                    put("error", "no browser controller registered")
                }
            return
        }
        val commandId = step.commandId ?: gateway.store.nextCommandId()
        val toolCallId = step.toolCallId ?: gateway.store.nextToolCallId()
        val pending =
            PendingBrowserCommand(
                commandId = commandId,
                action = step.action,
                toolCallId = toolCallId,
                controllerId = controller.controllerId,
                session = session,
                conn = controller.conn,
            )
        gateway.store.pendingCommands[commandId] = pending
        session.lastCommandId = commandId
        // El broker envía el frame al TRANSPORT del controlador, no al del prompt.
        controller.conn.emitEvent(
            "browser.controller.command",
            session,
            buildJsonObject {
                put("command_id", commandId)
                put("action", step.action)
                put("arguments", substitute(step.arguments))
                put("controller_id", controller.controllerId)
                put("browser_profile_id", controller.browserProfileId)
                // El frame del broker real siempre lleva tool_call_id (null si no hay).
                put("tool_call_id", toolCallId?.let { JsonPrimitive(it) } ?: JsonNull)
            },
        )
        if (!step.awaitResult) {
            return
        }
        val result =
            try {
                withTimeout(step.timeoutMs) { pending.result.await() }
            } catch (ignored: TimeoutCancellationException) {
                gateway.store.pendingCommands.remove(commandId)
                // El broker avisa al controlador con browser.controller.cancel
                // {command_id, tool_call_id} cuando vence su espera.
                controller.conn.emitEvent(
                    "browser.controller.cancel",
                    session,
                    buildJsonObject {
                        put("command_id", commandId)
                        put("tool_call_id", toolCallId?.let { JsonPrimitive(it) } ?: JsonNull)
                    },
                )
                throw TurnAbort("timeout esperando browser.controller.result ($commandId)")
            }
        lastResult = result["result"] ?: result
    }

    private suspend fun runBrowserCancel(step: ScriptStep.BrowserCancel) {
        val commandId = step.commandId ?: session.lastCommandId
        if (commandId == null) {
            gateway.log("browser_cancel sin comando previo")
            return
        }
        val pending = gateway.store.pendingCommands.remove(commandId)
        (pending?.conn ?: conn).emitEvent(
            "browser.controller.cancel",
            session,
            buildJsonObject {
                put("command_id", commandId)
                put("tool_call_id", pending?.toolCallId?.let { JsonPrimitive(it) } ?: JsonNull)
            },
        )
        pending?.result?.complete(
            buildJsonObject {
                put("ok", false)
                put("error", "command cancelled")
            },
        )
    }

    private suspend fun runCancelRequest(step: ScriptStep.CancelRequest) {
        val id = step.requestId ?: lastSrqId
        if (id == null) {
            gateway.log("cancel_request sin petición previa")
            return
        }
        val open = gateway.store.openRequestsById[id] ?: return gateway.log("cancel_request: '$id' no está abierta")
        emitRequestCancel(open, step.reason)
        conn.withdrawRequest(open, step.reason)
    }

    /** `request.cancel {id,method,reason}` con seq — el frame que el real emite al retirar una srq. */
    private suspend fun emitRequestCancel(
        open: OpenRequest,
        reason: String,
    ) {
        emit(
            "request.cancel",
            buildJsonObject {
                put("id", open.id)
                put("method", open.method)
                put("reason", reason)
            },
        )
    }

    // --- azúcares ---

    private suspend fun runToolPair(step: ScriptStep.ToolPair) {
        val toolId = gateway.store.nextToolId()
        emit(
            "tool.start",
            buildJsonObject {
                put("tool_id", toolId)
                put("name", step.name)
                step.context?.let { put("context", it) }
            },
        )
        if (step.sleepMs > 0) {
            delay(step.sleepMs)
        }
        emit(
            "tool.complete",
            buildJsonObject {
                put("tool_id", toolId)
                put("name", step.name)
                step.summary?.let { put("summary", it) }
            },
        )
    }

    private suspend fun runRename(title: String) {
        session.title = title
        emit(
            "session.title",
            buildJsonObject {
                put("session_id", session.storedId)
                put("title", title)
            },
        )
        gateway.broadcastSessionsChanged()
    }

    // --- emisión y bookkeeping ---

    private suspend fun emit(
        type: String,
        payload: JsonObject,
        broadcast: Boolean = false,
    ) {
        when (type) {
            "message.start" -> startEmitted = true
            "message.delta" -> streamed.append(payload["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
            "message.complete" -> {
                completeEmitted = true
                appendAssistantRow(payload)
            }
        }
        conn.emitEvent(type, session, payload, broadcast)
    }

    private suspend fun emitEvent(
        type: String,
        payload: JsonObject,
    ) {
        conn.emitEvent(type, session, payload, broadcast = false)
    }

    private suspend fun emitSessionInfo() {
        conn.emitEvent(
            "session.info",
            session,
            buildJsonObject {
                put("running", session.running)
                put("title", session.title)
                put("stored_session_id", session.storedId)
            },
        )
    }

    private fun appendAssistantRow(completePayload: JsonObject) {
        val text = completePayload["text"]?.jsonPrimitive?.contentOrNull ?: streamed.toString()
        val rowId = session.messageCount() + 1L
        session.addMessage(
            buildJsonObject {
                put("role", "assistant")
                put("text", text)
                put("timestamp", System.currentTimeMillis() / 1000.0)
                put("row_id", rowId)
                if (completePayload["error"] != null && completePayload["error"] !is JsonNull) {
                    put("display_kind", "error")
                }
            },
        )
        session.preview = text.take(PREVIEW_CHARS)
        streamed.setLength(0)
    }

    /**
     * Tras interrupt: retira las `srq` y los comandos de navegador abiertos de
     * ESTA sesión con su evento de cancelación (el real usa reason
     * `"interrupted"`; el broker avisa con `browser.controller.cancel`).
     */
    private suspend fun withdrawOpenRequests() {
        session.openRequests.values.toList().forEach { open ->
            emitRequestCancel(open, "interrupted")
            conn.withdrawRequest(open, "interrupted")
        }
        gateway.store.pendingCommands.values
            .filter { it.session === session }
            .forEach { cmd ->
                gateway.store.pendingCommands.remove(cmd.commandId)
                cmd.conn.emitEvent(
                    "browser.controller.cancel",
                    session,
                    buildJsonObject {
                        put("command_id", cmd.commandId)
                        put("tool_call_id", cmd.toolCallId?.let { JsonPrimitive(it) } ?: JsonNull)
                    },
                )
                cmd.result.complete(
                    buildJsonObject {
                        put("ok", false)
                        put("error", "turn interrupted")
                    },
                )
            }
    }

    // --- interpolación {{last_result}} ---

    private fun substitute(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> JsonObject(element.mapValues { (_, v) -> substitute(v) })
            is JsonArray -> JsonArray(element.map { substitute(it) })
            is JsonPrimitive -> if (element.isString) substituteString(element.content) else element
            else -> element
        }

    /**
     * `{{last_result}}` como string COMPLETO devuelve el JSON resuelto (tipos
     * preservados); embebido en un string más largo se sustituye por su forma de
     * texto — "Respondiste: {{last_result.choice}}." → "Respondiste: once.".
     * Un token sin resolver queda literal (visible: el fake no traga errores).
     */
    private fun substituteString(value: String): JsonElement =
        when {
            // Token a solas → el JSON resuelto entero (tipos preservados).
            value == LAST_RESULT_TOKEN -> lastResult
            // String completo = un único token con path → su valor JSON.
            TOKEN_PATH_REGEX.matches(value) ->
                resolveResultPath(value.removePrefix(LAST_RESULT_PREFIX).removeSuffix("}}"))
                    ?: JsonPrimitive(value)
            // Sin tokens: devolver tal cual.
            !value.contains(TOKEN_HEAD) -> JsonPrimitive(value)
            // Tokens embebidos: cada uno a su forma textual (o literal si no resuelve).
            else -> JsonPrimitive(TOKEN_REGEX.replace(value) { match -> renderToken(match.value) })
        }

    /** Forma textual de un `{{last_result[.path]}}`: primitivo → contenido; resto → JSON compacto. */
    private fun renderToken(token: String): String {
        val path = token.removePrefix("{{").removeSuffix("}}").removePrefix("last_result")
        val resolved =
            if (path.isEmpty()) {
                lastResult
            } else {
                resolveResultPath(path.removePrefix("."))
            } ?: return token
        return when {
            resolved is JsonNull -> "null"
            resolved is JsonPrimitive && resolved.isString -> resolved.content
            else -> resolved.toString()
        }
    }

    private fun resolveResultPath(path: String): JsonElement? {
        var current: JsonElement = lastResult
        for (part in path.split('.')) {
            current = (current as? JsonObject)?.get(part) ?: return null
        }
        return current
    }

    /** Abort controlado del turno (no es un fallo del canal): timeout, socket cerrado, etc. */
    private class TurnAbort(
        val errorMessage: String?,
    ) : CancellationException(errorMessage)

    private companion object {
        const val LAST_RESULT_TOKEN = "{{last_result}}"
        const val LAST_RESULT_PREFIX = "{{last_result."
        const val TOKEN_HEAD = "{{last_result"
        val TOKEN_REGEX = Regex("""\{\{last_result(\.[A-Za-z0-9_.-]+)?\}\}""")

        /** El string entero es UN token `{{last_result.campo…}}` (sin texto alrededor). */
        val TOKEN_PATH_REGEX = Regex("""\{\{last_result\.[A-Za-z0-9_.-]+\}\}""")
        const val PREVIEW_CHARS = 80
    }
}
