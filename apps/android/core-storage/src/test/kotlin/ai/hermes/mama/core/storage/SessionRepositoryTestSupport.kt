package ai.hermes.mama.core.storage

import ai.hermes.mama.contract.SessionListRow
import ai.hermes.mama.contract.SessionLiveInfo
import ai.hermes.mama.contract.SessionResumeResult
import ai.hermes.mama.contract.TranscriptMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Soporte compartido de los tests de [SessionRepository] (B6). */

internal const val TEST_NOW_SECONDS = 1_700_000_000.0

internal val EMPTY_PAYLOAD: JsonObject = buildJsonObject { }

internal fun TestScope.newRepository(
    gateway: SessionGateway,
    db: MamaDatabase,
): SessionRepository =
    SessionRepository(
        gateway = gateway,
        db = db,
        scope = backgroundScope,
        nowEpochSeconds = { TEST_NOW_SECONDS },
    )

/**
 * Sondea [probe] hasta que dé no-null o se agote el tiempo.
 *
 * Room corre sus escrituras en un executor REAL, no en el scheduler virtual de
 * `runTest`: lo que llega por un evento (un `message.complete` que persiste,
 * un `session.title` que renombra) hay que esperarlo sondeando — los `delay`
 * del bucle van por `Dispatchers.Default` (reloj real), no por el virtual.
 */
internal suspend fun <T> eventually(
    timeoutMs: Long = 5_000,
    probe: suspend () -> T?,
): T {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        probe()?.let { return it }
        check(System.currentTimeMillis() < deadline) { "eventually() agotó ${timeoutMs}ms" }
        withContext(Dispatchers.Default) { delay(10) }
    }
}

internal fun row(
    id: String,
    title: String = "t-$id",
) = SessionListRow(id = id, title = title, startedAt = 100.0)

/**
 * `SessionResumeResult` de mentira: los caminos reales de resume devuelven
 * `session_key` (la punta de linaje resuelta) — se replica por defecto —
 * mientras `stored_session_id` sólo aparece en create/resume-de-draft.
 */
internal fun resumeResult(
    runtimeId: String,
    storedId: String,
    messages: List<TranscriptMessage> = emptyList(),
    sessionKey: String? = storedId,
    storedSessionId: String? = storedId,
    running: Boolean? = null,
) = SessionResumeResult(
    sessionId = runtimeId,
    messageCount = messages.size.toLong(),
    messages = messages,
    info = SessionLiveInfo(),
    storedSessionId = storedSessionId,
    sessionKey = sessionKey,
    running = running,
)

internal fun textPayload(text: String): JsonObject = buildJsonObject { put("text", text) }
