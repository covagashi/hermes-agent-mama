package ai.hermes.mama.gateway

import ai.hermes.mama.contract.ApprovalChoice
import ai.hermes.mama.contract.ApprovalRequestParams
import ai.hermes.mama.contract.ApprovalResult
import ai.hermes.mama.contract.ClarifyRequestParams
import ai.hermes.mama.contract.ClarifyResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Petición servidor→cliente ya clasificada por [GatewayClient] (ROADMAP §2.5,
 * tarea B4). Envuelve el [ServerRequest] crudo del canal: [id]/[method]/
 * [replayed] se delegan y las respuestas siguen siendo idempotentes — la
 * primera gana y las siguientes son no-op (responder dos veces no emite un
 * segundo frame, igual que en B1).
 */
sealed class TypedServerRequest {
    internal abstract val raw: ServerRequest

    /** `id` string tal cual llegó (viaja de vuelta en la respuesta). */
    val id: String
        get() = raw.id

    /** `method` crudo — `approval`, `clarify` o cualquier otro ([UnsupportedRequest]). */
    val method: String
        get() = raw.method

    /** `true` si llegó re-entregada dentro de `open_requests` de un result (reconexión, la re-entrega es de B1). */
    val replayed: Boolean
        get() = raw.replayed

    /** `true` una vez que se aceptó una respuesta ([ServerRequest.respond]/[ServerRequest.fail]). */
    val isAnswered: Boolean
        get() = raw.isAnswered
}

/**
 * `approval` (§2.5): Hermes pide permiso para ejecutar una acción. La app sólo
 * ofrece Sí (`once`) / No (`deny`) — nunca `always`/`session`, así que no hay
 * respuesta genérica por `ApprovalChoice`. [params] trae `command`/`description`
 * (ya redactados por el servidor), `choices`, `toolName`…
 */
class ApprovalRequest internal constructor(
    internal override val raw: ServerRequest,
    val params: ApprovalRequestParams,
    private val json: Json,
) : TypedServerRequest() {
    /** Sesión a la que pertenece la aprobación (runtime id). */
    val sessionId: String
        get() = params.sessionId

    /** `request_id` tal cual vino; también sirve para `approval.respond`. */
    val requestId: String
        get() = params.requestId

    /** Responde Sí — `{"choice":"once"}`. `false` si ya estaba respondida o el canal murió. */
    fun approve(): Boolean = respondChoice(ApprovalChoice.ONCE)

    /** Responde No — `{"choice":"deny"}`. `false` si ya estaba respondida o el canal murió. */
    fun deny(): Boolean = respondChoice(ApprovalChoice.DENY)

    private fun respondChoice(choice: ApprovalChoice): Boolean =
        raw.respond(json.encodeToJsonElement(ApprovalResult.serializer(), ApprovalResult(choice)))
}

/**
 * `clarify` (§2.5): Hermes hace una pregunta — única ([ClarifyRequestParams.question]
 * con `choices`/`multiSelect`) o lote ([ClarifyRequestParams.questions], cada una
 * con su `qid`).
 */
class ClarifyRequest internal constructor(
    internal override val raw: ServerRequest,
    val params: ClarifyRequestParams,
    private val json: Json,
) : TypedServerRequest() {
    /** Sesión a la que pertenece la pregunta (runtime id). */
    val sessionId: String
        get() = params.sessionId

    /** Responde la pregunta única — `{"answer": text}` (`""` = skip). */
    fun answer(answer: String): Boolean =
        raw.respond(json.encodeToJsonElement(ClarifyResult.serializer(), ClarifyResult(answer = answer)))

    /** Responde un lote — `{"answers": {qid: respuesta}}` para todo el set. */
    fun answerAll(answers: Map<String, String>): Boolean =
        raw.respond(json.encodeToJsonElement(ClarifyResult.serializer(), ClarifyResult(answers = answers)))

    /**
     * Cierra sin responder — `result {}` explícito, sin `answer` ni `answers`
     * = cancel-all (doc del schema). No depende de `explicitNulls` del Json:
     * `ClarifyResult()` codificado con `explicitNulls = true` mandaría
     * `{"answer":null,"answers":null}`, que no es lo mismo en el wire.
     */
    fun dismiss(): Boolean = raw.respond(JsonObject(emptyMap()))
}

/**
 * Cualquier otro método — `secret`, `sudo`, `vault.*`, `mcp.setup`, `preview.*`,
 * `terminal.read`, `window.read`, `tour`… — o una `approval`/`clarify` con params
 * que no decodifican: esta app no puede atenderlo. Su `-32601` (method not
 * found) se envía automáticamente al construirla (§2.5: no bloquear al backend);
 * colectarla sólo sirve para mostrar el aviso humano ("Hermes necesita algo que
 * esta app no puede dar; pídeselo a tu persona de confianza").
 */
class UnsupportedRequest internal constructor(
    internal override val raw: ServerRequest,
) : TypedServerRequest() {
    init {
        raw.fail(
            JsonRpcChannel.JSON_RPC_METHOD_NOT_FOUND,
            "unsupported server request: ${raw.method}",
        )
    }
}
