package ai.hermes.mama.testing

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * Guion de un [FakeGateway] (ROADMAP §5, tarea B5): un JSON en `testing/scripts/`
 * que declara la autenticación, las sesiones semilla y qué emite el gateway por
 * cada `prompt.submit`.
 *
 * Formato (todo opcional salvo lo indicado):
 *
 * ```json
 * {
 *   "name": "hola_mundo",
 *   "description": "texto libre",
 *   "auth": {"username": "usuario", "password": "mama", "user_id": "…",
 *            "display_name": "…", "email": "…", "rate_limited": false},
 *   "require_ws_ticket": false,
 *   "replay_epoch": "epoch-fake",
 *   "sessions": [{"id": "stored_x", "runtime_id": "sess_x", "title": "…",
 *                 "preview": "…", "started_at": 0.0, "source": "android",
 *                 "hidden": false, "messages": [TranscriptMessage…]}],
 *   "turns": [{"when": {"text_contains": "hola" | "text_regex": "…"},
 *              "submit_status": {"status": "queued"},
 *              "submit_error": {"code": -32000, "message": "…"},
 *              "steps": […]}],
 *   "default_turn": {"steps": […]}
 * }
 * ```
 *
 * Pasos (`steps`, se ejecutan en orden; un solo tipo por paso):
 *
 * - `{"event": "<type>", "payload": {…}, "broadcast": true}` — evento §2.4;
 *   por defecto va con el `session_id` runtime de la sesión del turno.
 * - `{"delta": "texto"}` — atajo de `message.delta {text}`.
 * - `{"sleep_ms": N}` — pausa (streaming/interrupt realistas).
 * - `{"request": {"method": "approval"|"clarify"|…, "params": {…},
 *   "id": "srq-x", "timeout_ms": N, "await": true}}` — petición
 *   servidor→cliente §2.5 con id string; el fake ESPERA la respuesta salvo
 *   `"await": false`. `session_id`/`request_id` se autocompletan si faltan.
 * - `{"browser_command": {"action": "browser_*", "arguments": {…},
 *   "command_id": "cmd-x", "timeout_ms": N, "await": true}}` — emite
 *   `browser.controller.command` y espera el `browser.controller.result`.
 * - `{"browser_cancel": {"command_id": "cmd-x"}}` — `browser.controller.cancel`
 *   (sin `command_id`: el último emitido).
 * - `{"cancel_request": {"id": "srq-x", "reason": "…"}}` — `request.cancel`
 *   (sin `id`: la última emitida).
 * - `{"tool": {"name": "web_search", "context": "…", "summary": "…",
 *   "sleep_ms": N}}` — par `tool.start` + `tool.complete`.
 * - `{"session_title": "nuevo título"}` — renombra + evento `session.title` +
 *   `sessions.changed`.
 * - `{"close_socket": {"code": 1000, "reason": "…"}}` — cierra el WebSocket
 *   (tests de reconexión).
 *
 * En los payloads de `event`/`delta` se puede interpolar la última respuesta de
 * una petición servidor→cliente o comando de navegador: `"{{last_result}}"`
 * inserta el result entero y `"{{last_result.campo}}"` un campo (p. ej.
 * `"{{last_result.choice}}"` tras un `approval`).
 */
class FakeGatewayScript internal constructor(
    val name: String,
    val description: String,
    val auth: AuthConfig,
    val requireWsTicket: Boolean,
    val replayEpoch: String,
    val skin: JsonObject,
    val sessions: List<SessionSeed>,
    val turns: List<TurnScript>,
    val defaultTurn: TurnScript?,
) {
    /** Credenciales del proveedor `basic` que acepta `password-login`. */
    data class AuthConfig(
        val provider: String = "basic",
        val username: String = "usuario",
        val password: String = "mama",
        val userId: String = "u_fake_usuario",
        val displayName: String = "Usuario Fake",
        val email: String = "usuario@hermes.example.invalid",
        val rateLimited: Boolean = false,
    )

    /** Sesión precargada del guion (aparece en `session.list`). */
    data class SessionSeed(
        val storedId: String,
        val runtimeId: String?,
        val title: String,
        val preview: String,
        val startedAt: Double,
        val source: String,
        val hidden: Boolean,
        val messages: List<JsonObject>,
    )

    /** Un turno del guion: qué responde `prompt.submit` y qué emite después. */
    data class TurnScript(
        val matcher: PromptMatcher?,
        val submitStatus: JsonObject?,
        val submitError: RpcErrorSpec?,
        val steps: List<ScriptStep>,
    )

    /** Filtro `when` de un turno; `null` en un turno = coincide siempre. */
    data class PromptMatcher(
        val textContains: String?,
        val textRegex: Regex?,
    ) {
        fun matches(text: String): Boolean =
            (textContains == null || text.contains(textContains, ignoreCase = true)) &&
                (textRegex == null || textRegex.containsMatchIn(text))
    }

    /** `submit_error`: `prompt.submit` falla con este error JSON-RPC. */
    data class RpcErrorSpec(
        val code: Int,
        val message: String,
    )

    /** Un paso del guion; exactamente una variante por entrada de `steps`. */
    sealed interface ScriptStep {
        /** Evento §2.4; `broadcast` → `session_id` "" (sessions.changed y similares). */
        data class Event(
            val type: String,
            val payload: JsonObject,
            val broadcast: Boolean,
        ) : ScriptStep

        data class Sleep(
            val millis: Long,
        ) : ScriptStep

        /** Petición servidor→cliente §2.5 (`approval`, `clarify`, …) con id `srq-*`. */
        data class ServerRequest(
            val method: String,
            val params: JsonObject,
            val id: String?,
            val awaitResponse: Boolean,
            val timeoutMs: Long,
        ) : ScriptStep

        /** Comando del controlador de navegador §2.6; espera `browser.controller.result`. */
        data class BrowserCommand(
            val action: String,
            val arguments: JsonObject,
            val commandId: String?,
            val awaitResult: Boolean,
            val timeoutMs: Long,
        ) : ScriptStep

        data class BrowserCancel(
            val commandId: String?,
        ) : ScriptStep

        /** Evento `request.cancel`; retira la petición abierta de `open_requests`. */
        data class CancelRequest(
            val requestId: String?,
            val reason: String,
        ) : ScriptStep

        /** Par `tool.start` + `tool.complete` con pausa opcional. */
        data class ToolPair(
            val name: String,
            val context: String?,
            val summary: String?,
            val sleepMs: Long,
        ) : ScriptStep

        /** Renombra la sesión del turno y emite `session.title` + `sessions.changed`. */
        data class RenameSession(
            val title: String,
        ) : ScriptStep

        /** Cierra el WebSocket (tests de reconexión). */
        data class CloseSocket(
            val code: Short,
            val reason: String,
        ) : ScriptStep
    }

    /** Elige el turno para un `prompt.submit`: primer `turns` que casa, si no `default_turn`. */
    fun turnFor(text: String): TurnScript? =
        turns.firstOrNull { turn -> turn.matcher == null || turn.matcher.matches(text) } ?: defaultTurn

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Guion mínimo de respaldo cuando un test no necesita archivo. */
        fun echo(): FakeGatewayScript =
            parse(
                """{"name":"echo","turns":[],"default_turn":{"steps":[
                {"event":"message.start"},{"delta":"fake echo"},
                {"event":"message.complete","payload":{"status":"complete"}}]}}""",
                source = "echo",
            )

        /**
         * Carga un guion por nombre (`hola_mundo` → classpath `scripts/hola_mundo.json`
         * o fichero `scripts/hola_mundo.json` bajo el módulo) o por ruta a un `.json`.
         */
        fun load(nameOrPath: String): FakeGatewayScript {
            val trimmed = nameOrPath.trim()
            require(trimmed.isNotEmpty()) { "nombre de guion vacío" }
            val loaded =
                resolveScriptText(trimmed)
                    ?: throw FakeScriptException(
                        "guion '$trimmed' no encontrado: ni recurso /scripts/$trimmed.json ni fichero",
                    )
            return parse(loaded.text, source = loaded.source)
        }

        private data class LoadedScript(
            val text: String,
            val source: String,
        )

        /** Prueba cada ubicación candidata en orden y devuelve la primera que existe. */
        private fun resolveScriptText(nameOrPath: String): LoadedScript? {
            val looksLikePath =
                nameOrPath.endsWith(".json") || nameOrPath.contains('/') || nameOrPath.contains('\\')
            val candidates =
                if (looksLikePath) {
                    listOf(
                        { loadFile(File(nameOrPath)) },
                        { loadFile(File("scripts").resolve(nameOrPath.removePrefix("scripts/"))) },
                    )
                } else {
                    listOf(
                        {
                            FakeGatewayScript::class.java
                                .getResource("/scripts/$nameOrPath.json")
                                ?.let { LoadedScript(it.readText(), "classpath:/scripts/$nameOrPath.json") }
                        },
                        { loadFile(File("scripts/$nameOrPath.json")) },
                    )
                }
            return candidates.firstNotNullOfOrNull { it() }
        }

        private fun loadFile(file: File): LoadedScript? =
            if (file.isFile) LoadedScript(file.readText(), file.path) else null

        /** Parsea el JSON de un guion; errores claros con la ruta del campo que falla. */
        fun parse(
            text: String,
            source: String = "<inline>",
        ): FakeGatewayScript {
            val root =
                try {
                    json.parseToJsonElement(text)
                } catch (e: SerializationException) {
                    throw FakeScriptException(
                        "$source: JSON inválido (${e.message?.lineSequence()?.first()})",
                        e,
                    )
                }
            val obj =
                root as? JsonObject
                    ?: throw FakeScriptException("$source: la raíz del guion debe ser un objeto JSON")

            val auth = parseAuth(obj["auth"] as? JsonObject, source)
            val sessions =
                (obj["sessions"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapIndexed { i, el ->
                    parseSession(el.objAt("$source: sessions[$i]"), i)
                }
            val turns =
                (obj["turns"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapIndexed { i, el ->
                    parseTurn(el.objAt("$source: turns[$i]"), "$source: turns[$i]")
                }
            val defaultTurn =
                (obj["default_turn"] as? JsonObject)?.let { parseTurn(it, "$source: default_turn") }
            return FakeGatewayScript(
                name = obj["name"].strOrNull() ?: source.substringAfterLast('/').removeSuffix(".json"),
                description = obj["description"].strOrNull().orEmpty(),
                auth = auth,
                requireWsTicket = obj["require_ws_ticket"].boolOrNull() ?: false,
                replayEpoch = obj["replay_epoch"].strOrNull() ?: "epoch-fake",
                skin = obj["skin"] as? JsonObject ?: JsonObject(emptyMap()),
                sessions = sessions,
                turns = turns,
                defaultTurn = defaultTurn,
            )
        }

        private fun parseAuth(
            obj: JsonObject?,
            source: String,
        ): AuthConfig {
            if (obj == null) {
                return AuthConfig()
            }
            return AuthConfig(
                provider = obj["provider"].strOrNull() ?: "basic",
                username = obj["username"].strOrNull() ?: "usuario",
                password = obj["password"].strOrNull() ?: "mama",
                userId = obj["user_id"].strOrNull() ?: "u_fake_usuario",
                displayName = obj["display_name"].strOrNull() ?: "Usuario Fake",
                email = obj["email"].strOrNull() ?: "usuario@hermes.example.invalid",
                rateLimited = obj["rate_limited"].boolOrNull() ?: false,
            ).also {
                if (it.username.isBlank() || it.password.isBlank()) {
                    throw FakeScriptException("$source: auth.username/password no pueden estar vacíos")
                }
            }
        }

        private fun parseSession(
            obj: JsonObject,
            index: Int,
        ): SessionSeed =
            SessionSeed(
                storedId = obj["id"].strOrNull() ?: "stored_seed_$index",
                runtimeId = obj["runtime_id"].strOrNull(),
                title = obj["title"].strOrNull().orEmpty(),
                preview = obj["preview"].strOrNull().orEmpty(),
                startedAt = obj["started_at"].numOrNull()?.toDouble() ?: 0.0,
                source = obj["source"].strOrNull() ?: "android",
                hidden = obj["hidden"].boolOrNull() ?: false,
                messages =
                    (obj["messages"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull {
                        it as? JsonObject
                    },
            )

        private fun parseTurn(
            obj: JsonObject,
            at: String,
        ): TurnScript {
            val matcher = parseMatcher(obj["when"] as? JsonObject, at)
            val submitError =
                (obj["submit_error"] as? JsonObject)?.let { err ->
                    RpcErrorSpec(
                        code = err["code"].numOrNull()?.toInt() ?: -32000,
                        message = err["message"].strOrNull() ?: "submit_error del guion",
                    )
                }
            // `steps` es opcional: un turno de puro `submit_error` no emite nada.
            val steps =
                (obj["steps"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapIndexed { i, el ->
                    StepParser.parse(el.objAt("$at.steps[$i]"), "$at.steps[$i]")
                }
            return TurnScript(
                matcher = matcher,
                submitStatus = obj["submit_status"] as? JsonObject,
                submitError = submitError,
                steps = steps,
            )
        }

        private fun parseMatcher(
            obj: JsonObject?,
            at: String,
        ): PromptMatcher? {
            if (obj == null) {
                return null
            }
            val regex =
                obj["text_regex"].strOrNull()?.let { pattern ->
                    try {
                        Regex(pattern, RegexOption.IGNORE_CASE)
                    } catch (e: IllegalArgumentException) {
                        throw FakeScriptException("$at.when: regex inválida '$pattern' (${e.message})", e)
                    }
                }
            return PromptMatcher(
                textContains = obj["text_contains"].strOrNull(),
                textRegex = regex,
            )
        }

        private fun kotlinx.serialization.json.JsonElement?.strOrNull(): String? =
            (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        private fun kotlinx.serialization.json.JsonElement?.numOrNull(): Number? {
            val p = this as? JsonPrimitive ?: return null
            return p.longOrNull ?: p.doubleOrNull ?: p.intOrNull
        }

        private fun kotlinx.serialization.json.JsonElement?.boolOrNull(): Boolean? =
            (this as? JsonPrimitive)?.booleanOrNull

        private fun kotlinx.serialization.json.JsonElement?.objAt(at: String): JsonObject =
            this as? JsonObject ?: throw FakeScriptException("$at: debe ser un objeto JSON")

        private const val DEFAULT_REQUEST_TIMEOUT_MS = 30_000L

        /** Timeout del broker real (§2.6): 30 s por comando. */
        private const val BROWSER_COMMAND_TIMEOUT_MS = 30_000L

        /** Claves de paso reconocidas (exactamente una por entrada de `steps`). */
        private val STEP_KEYS =
            listOf(
                "event",
                "delta",
                "sleep_ms",
                "request",
                "browser_command",
                "browser_cancel",
                "cancel_request",
                "tool",
                "session_title",
                "close_socket",
            )

        /** Parser de `steps` (extraído del companion: un paso = una clave de [STEP_KEYS]). */
        private object StepParser {
            fun parse(
                obj: JsonObject,
                at: String,
            ): ScriptStep {
                val present = STEP_KEYS.filter { obj.containsKey(it) }
                if (present.size != 1) {
                    throw FakeScriptException(
                        "$at: un paso lleva exactamente una clave de $STEP_KEYS; encontradas: $present",
                    )
                }
                return when (present.single()) {
                    "event" -> parseEventStep(obj, at)
                    "delta" -> parseDeltaStep(obj, at)
                    "sleep_ms" -> parseSleepStep(obj, at)
                    "request" -> parseRequestStep(obj, at)
                    "browser_command" -> parseBrowserCommandStep(obj, at)
                    "browser_cancel" -> parseBrowserCancelStep(obj)
                    "cancel_request" -> parseCancelRequestStep(obj)
                    "tool" -> parseToolStep(obj, at)
                    "session_title" -> parseRenameStep(obj, at)
                    "close_socket" -> parseCloseSocketStep(obj)
                    else -> throw FakeScriptException("$at: paso desconocido")
                }
            }

            private fun parseEventStep(
                obj: JsonObject,
                at: String,
            ): ScriptStep.Event =
                ScriptStep.Event(
                    type =
                        obj["event"].strOrNull()
                            ?: throw FakeScriptException("$at: 'event' debe ser string"),
                    payload = obj["payload"] as? JsonObject ?: JsonObject(emptyMap()),
                    broadcast = obj["broadcast"].boolOrNull() ?: false,
                )

            private fun parseDeltaStep(
                obj: JsonObject,
                at: String,
            ): ScriptStep.Event =
                ScriptStep.Event(
                    type = "message.delta",
                    payload =
                        JsonObject(
                            mapOf(
                                "text" to
                                    JsonPrimitive(
                                        obj["delta"].strOrNull()
                                            ?: throw FakeScriptException("$at: 'delta' debe ser string"),
                                    ),
                            ),
                        ),
                    broadcast = false,
                )

            private fun parseSleepStep(
                obj: JsonObject,
                at: String,
            ): ScriptStep.Sleep =
                ScriptStep.Sleep(
                    millis =
                        obj["sleep_ms"].numOrNull()?.toLong()
                            ?: throw FakeScriptException("$at: 'sleep_ms' debe ser número"),
                )

            private fun parseRequestStep(
                obj: JsonObject,
                at: String,
            ): ScriptStep.ServerRequest {
                val req = obj["request"].objAt("$at.request")
                return ScriptStep.ServerRequest(
                    method =
                        req["method"].strOrNull()
                            ?: throw FakeScriptException("$at.request: falta 'method'"),
                    params = req["params"] as? JsonObject ?: JsonObject(emptyMap()),
                    id = req["id"].strOrNull(),
                    awaitResponse = req["await"].boolOrNull() ?: true,
                    timeoutMs = req["timeout_ms"].numOrNull()?.toLong() ?: DEFAULT_REQUEST_TIMEOUT_MS,
                )
            }

            private fun parseBrowserCommandStep(
                obj: JsonObject,
                at: String,
            ): ScriptStep.BrowserCommand {
                val cmd = obj["browser_command"].objAt("$at.browser_command")
                return ScriptStep.BrowserCommand(
                    action =
                        cmd["action"].strOrNull()
                            ?: throw FakeScriptException("$at.browser_command: falta 'action'"),
                    arguments = cmd["arguments"] as? JsonObject ?: JsonObject(emptyMap()),
                    commandId = cmd["command_id"].strOrNull(),
                    awaitResult = cmd["await"].boolOrNull() ?: true,
                    timeoutMs = cmd["timeout_ms"].numOrNull()?.toLong() ?: BROWSER_COMMAND_TIMEOUT_MS,
                )
            }

            private fun parseBrowserCancelStep(obj: JsonObject): ScriptStep.BrowserCancel {
                val cancel = obj["browser_cancel"] as? JsonObject ?: JsonObject(emptyMap())
                return ScriptStep.BrowserCancel(commandId = cancel["command_id"].strOrNull())
            }

            private fun parseCancelRequestStep(obj: JsonObject): ScriptStep.CancelRequest {
                val cancel = obj["cancel_request"] as? JsonObject ?: JsonObject(emptyMap())
                return ScriptStep.CancelRequest(
                    requestId = cancel["id"].strOrNull(),
                    reason = cancel["reason"].strOrNull() ?: "cancelled",
                )
            }

            private fun parseToolStep(
                obj: JsonObject,
                at: String,
            ): ScriptStep.ToolPair {
                val tool = obj["tool"].objAt("$at.tool")
                return ScriptStep.ToolPair(
                    name =
                        tool["name"].strOrNull()
                            ?: throw FakeScriptException("$at.tool: falta 'name'"),
                    context = tool["context"].strOrNull(),
                    summary = tool["summary"].strOrNull(),
                    sleepMs = tool["sleep_ms"].numOrNull()?.toLong() ?: 0L,
                )
            }

            private fun parseRenameStep(
                obj: JsonObject,
                at: String,
            ): ScriptStep.RenameSession =
                ScriptStep.RenameSession(
                    title =
                        obj["session_title"].strOrNull()
                            ?: throw FakeScriptException("$at: 'session_title' debe ser string"),
                )

            private fun parseCloseSocketStep(obj: JsonObject): ScriptStep.CloseSocket {
                val close = obj["close_socket"] as? JsonObject ?: JsonObject(emptyMap())
                return ScriptStep.CloseSocket(
                    code = close["code"].numOrNull()?.toInt()?.toShort() ?: 1000,
                    reason = close["reason"].strOrNull() ?: "fake close",
                )
            }
        }
    }
}

/** Guion mal formado o no encontrado: el mensaje dice qué campo falta y en qué paso. */
class FakeScriptException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
