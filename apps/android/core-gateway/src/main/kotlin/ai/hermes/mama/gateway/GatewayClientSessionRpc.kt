package ai.hermes.mama.gateway

import ai.hermes.mama.contract.RpcMethods
import ai.hermes.mama.contract.SessionCreateParams
import ai.hermes.mama.contract.SessionCreateResult
import ai.hermes.mama.contract.SessionDeleteParams
import ai.hermes.mama.contract.SessionDeleteResult
import ai.hermes.mama.contract.SessionEventsSinceParams
import ai.hermes.mama.contract.SessionEventsSinceResult
import ai.hermes.mama.contract.SessionHistoryParams
import ai.hermes.mama.contract.SessionHistoryResult
import ai.hermes.mama.contract.SessionInterruptParams
import ai.hermes.mama.contract.SessionInterruptResult
import ai.hermes.mama.contract.SessionListParams
import ai.hermes.mama.contract.SessionListResult
import ai.hermes.mama.contract.SessionResumeParams
import ai.hermes.mama.contract.SessionResumeResult
import ai.hermes.mama.contract.SessionTitleParams
import ai.hermes.mama.contract.SessionTitleResult

/*
 * Métodos `session.*` de §2.3 sobre GatewayClient: la versión con DTO deja al
 * llamador fijar cualquier campo del contrato. Las sobrecargas con argumentos
 * sueltos (forma de la app) viven en `GatewayClientSessionCalls.kt`.
 *
 * Identificadores (§2.3): `session.list`/`session.delete` trabajan con el
 * *stored* id; `session.resume`/`session.create` devuelven el *runtime* id que
 * usan los eventos, approvals y el resto de métodos.
 */

/** `session.list` — chats existentes (devuelve *stored* ids). */
suspend fun GatewayClient.listSessions(params: SessionListParams = SessionListParams()): SessionListResult =
    rpc(RpcMethods.SESSION_LIST, params, SessionListParams.serializer(), SessionListResult.serializer())

/** `session.create` — chat nuevo; el result trae el *runtime* `session_id` + `stored_session_id`. */
suspend fun GatewayClient.createSession(params: SessionCreateParams): SessionCreateResult =
    rpc(RpcMethods.SESSION_CREATE, params, SessionCreateParams.serializer(), SessionCreateResult.serializer())

/** `session.resume` — abre un chat por *stored* id; el result trae el *runtime* `session_id`. */
suspend fun GatewayClient.resumeSession(params: SessionResumeParams): SessionResumeResult =
    rpc(RpcMethods.SESSION_RESUME, params, SessionResumeParams.serializer(), SessionResumeResult.serializer())

/** `session.history` — transcript completo (recargar, o tras un `replay_epoch` nuevo). */
suspend fun GatewayClient.sessionHistory(params: SessionHistoryParams): SessionHistoryResult =
    rpc(RpcMethods.SESSION_HISTORY, params, SessionHistoryParams.serializer(), SessionHistoryResult.serializer())

/** `session.title` — renombrar un chat. */
suspend fun GatewayClient.renameSession(params: SessionTitleParams): SessionTitleResult =
    rpc(RpcMethods.SESSION_TITLE, params, SessionTitleParams.serializer(), SessionTitleResult.serializer())

/** `session.delete` — borrar (recibe el *stored* id, §2.3). */
suspend fun GatewayClient.deleteSession(params: SessionDeleteParams): SessionDeleteResult =
    rpc(RpcMethods.SESSION_DELETE, params, SessionDeleteParams.serializer(), SessionDeleteResult.serializer())

/** `session.interrupt` — botón «Parar». */
suspend fun GatewayClient.interruptSession(params: SessionInterruptParams): SessionInterruptResult =
    rpc(RpcMethods.SESSION_INTERRUPT, params, SessionInterruptParams.serializer(), SessionInterruptResult.serializer())

/** `session.events.since` — eventos perdidos tras una reconexión (`last_seen` = último `seq` visto). */
suspend fun GatewayClient.sessionEventsSince(params: SessionEventsSinceParams): SessionEventsSinceResult =
    rpc(
        RpcMethods.SESSION_EVENTS_SINCE,
        params,
        SessionEventsSinceParams.serializer(),
        SessionEventsSinceResult.serializer(),
    )
