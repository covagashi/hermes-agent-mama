package ai.hermes.mama.gateway

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
import ai.hermes.mama.contract.SessionResumeParams
import ai.hermes.mama.contract.SessionResumeResult
import ai.hermes.mama.contract.SessionTitleParams
import ai.hermes.mama.contract.SessionTitleResult

/*
 * Sobrecargas `session.*` "forma de la app" (§2.3): fijan `source:"android"`
 * donde aplica y toman el id correcto (*stored* en resume/delete, *runtime* en
 * el resto). Las versiones con DTO completo están en
 * `GatewayClientSessionRpc.kt`.
 */

/** `session.create` con la forma de la app: `{title?, source:"android"}` (§2.3). */
suspend fun GatewayClient.createSession(title: String? = null): SessionCreateResult =
    createSession(SessionCreateParams(title = title, source = GatewayClient.APP_SOURCE))

/** `session.resume` con la forma de la app: `{session_id: STORED, source:"android"}` (§2.3). */
suspend fun GatewayClient.resumeSession(storedSessionId: String): SessionResumeResult =
    resumeSession(SessionResumeParams(sessionId = storedSessionId, source = GatewayClient.APP_SOURCE))

/** `session.history` por runtime `session_id`. */
suspend fun GatewayClient.sessionHistory(sessionId: String): SessionHistoryResult =
    sessionHistory(SessionHistoryParams(sessionId = sessionId))

/** `session.title` por runtime `session_id`. */
suspend fun GatewayClient.renameSession(
    sessionId: String,
    title: String,
): SessionTitleResult = renameSession(SessionTitleParams(sessionId = sessionId, title = title))

/** `session.delete` por *stored* id. */
suspend fun GatewayClient.deleteSession(storedSessionId: String): SessionDeleteResult =
    deleteSession(SessionDeleteParams(sessionId = storedSessionId))

/** `session.interrupt` por runtime `session_id`. */
suspend fun GatewayClient.interruptSession(sessionId: String): SessionInterruptResult =
    interruptSession(SessionInterruptParams(sessionId = sessionId))

/** `session.events.since` por runtime `session_id`. */
suspend fun GatewayClient.sessionEventsSince(
    sessionId: String,
    lastSeen: Long,
): SessionEventsSinceResult = sessionEventsSince(SessionEventsSinceParams(sessionId = sessionId, lastSeen = lastSeen))
