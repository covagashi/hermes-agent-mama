@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package ai.hermes.mama.gateway

import ai.hermes.mama.contract.ApprovalChoice
import ai.hermes.mama.contract.ApprovalPendingParams
import ai.hermes.mama.contract.ApprovalPendingResult
import ai.hermes.mama.contract.ApprovalRespondParams
import ai.hermes.mama.contract.ApprovalRespondResult
import ai.hermes.mama.contract.ClarifyResult
import ai.hermes.mama.contract.RequestAnswerParams
import ai.hermes.mama.contract.RequestAnswerResult
import ai.hermes.mama.contract.RpcMethods
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/*
 * Métodos `approval.*` y `request.answer` de §2.3/§2.5 sobre GatewayClient:
 * sirven para re-sincronizar y responder aprobaciones/clarificaciones que
 * llegaron por otro camino (`approval.pending`, `open_requests` tras
 * reconexión). Las peticiones en vivo se atienden con ApprovalRequest y
 * ClarifyRequest de `GatewayClient.serverRequests`.
 */

/** `approval.pending` — aprobaciones pendientes tras una reconexión (§2.3). */
suspend fun GatewayClient.pendingApprovals(params: ApprovalPendingParams): ApprovalPendingResult =
    rpc(RpcMethods.APPROVAL_PENDING, params, ApprovalPendingParams.serializer(), ApprovalPendingResult.serializer())

/** `approval.pending` por runtime `session_id`. */
suspend fun GatewayClient.pendingApprovals(sessionId: String): ApprovalPendingResult =
    pendingApprovals(ApprovalPendingParams(sessionId = sessionId))

/** `approval.respond` — Sí/No sobre una aprobación vista vía [pendingApprovals] (§2.5: sólo `once`/`deny`). */
suspend fun GatewayClient.respondApproval(params: ApprovalRespondParams): ApprovalRespondResult =
    rpc(RpcMethods.APPROVAL_RESPOND, params, ApprovalRespondParams.serializer(), ApprovalRespondResult.serializer())

/**
 * `approval.respond` por `request_id` con la elección tipada — §2.5: la app
 * sólo ofrece Sí (`once`) / No (`deny`); `always`/`session` no existen en la
 * UI y aquí se rechazan antes de salir al wire.
 */
suspend fun GatewayClient.respondApproval(
    sessionId: String,
    requestId: String,
    choice: ApprovalChoice,
): ApprovalRespondResult {
    require(choice == ApprovalChoice.ONCE || choice == ApprovalChoice.DENY) {
        "la app sólo responde once/deny (§2.5): choice=$choice"
    }
    return respondApproval(
        ApprovalRespondParams(
            sessionId = sessionId,
            requestId = requestId,
            choice = choice.wireName(),
        ),
    )
}

/** `request.answer` — responde una petición que llegó como replay (`open_requests`), §2.3. */
suspend fun GatewayClient.answerRequest(params: RequestAnswerParams): RequestAnswerResult =
    rpc(RpcMethods.REQUEST_ANSWER, params, RequestAnswerParams.serializer(), RequestAnswerResult.serializer())

/** `request.answer` con el `result` ya montado. */
suspend fun GatewayClient.answerRequest(
    id: String,
    result: JsonObject,
): RequestAnswerResult = answerRequest(RequestAnswerParams(id = id, result = result))

/** Atajo de [answerRequest] para una clarify de pregunta única: `{"answer": …}` (`""` = skip). */
suspend fun GatewayClient.answerClarifyRequest(
    id: String,
    answer: String,
): RequestAnswerResult =
    answerRequest(
        id,
        json.encodeToJsonElement(ClarifyResult.serializer(), ClarifyResult(answer = answer)).jsonObject,
    )

/** Atajo de [answerRequest] para una clarify en lote: `{"answers": {qid: respuesta}}`. */
suspend fun GatewayClient.answerClarifyRequest(
    id: String,
    answers: Map<String, String>,
): RequestAnswerResult =
    answerRequest(
        id,
        json.encodeToJsonElement(ClarifyResult.serializer(), ClarifyResult(answers = answers)).jsonObject,
    )

/** Nombre de wire del enum generado (`ONCE` → `"once"`) sin duplicar literales del contrato. */
private fun ApprovalChoice.wireName(): String = ApprovalChoice.serializer().descriptor.getElementName(ordinal)
