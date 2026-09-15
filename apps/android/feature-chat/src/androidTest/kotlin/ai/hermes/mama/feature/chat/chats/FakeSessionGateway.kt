package ai.hermes.mama.feature.chat.chats

import ai.hermes.mama.contract.SessionCloseResult
import ai.hermes.mama.contract.SessionCreateResult
import ai.hermes.mama.contract.SessionDeleteResult
import ai.hermes.mama.contract.SessionHistoryResult
import ai.hermes.mama.contract.SessionListResult
import ai.hermes.mama.contract.SessionLiveInfo
import ai.hermes.mama.contract.SessionResumeResult
import ai.hermes.mama.contract.SessionTitleResult
import ai.hermes.mama.core.storage.SessionGateway
import ai.hermes.mama.gateway.GatewayEvent
import ai.hermes.mama.gateway.JsonRpcException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [SessionGateway] en memoria para el APK de tests (C3): mismo contrato que el
 * fake de B6 — RPCs configurables con lambdas `on*`, llamadas grabadas en
 * [calls] y vivacidad del backend (`resume`/`create` dejan el chat vivo y
 * `delete` sobre un vivo responde `4023`, como `methods_session.py`).
 *
 * Vive en `src/androidTest` porque el fake de `core-storage` es `internal` de
 * su propio source-set de test: ni los tests JVM ni los instrumentados de este
 * módulo pueden verlo.
 */
internal class FakeSessionGateway : SessionGateway {
    private val _events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
    override val events: Flow<GatewayEvent> = _events

    /** Llamadas recibidas, `"<metodo>:<sessionId>"` (p. ej. `"resume:s-1"`, `"list"`). */
    val calls = CopyOnWriteArrayList<String>()

    /** Chats vivos en el "servidor": *stored* id → *runtime* id (de `resume`/`create`, baja por `close`). */
    private val liveByStored = ConcurrentHashMap<String, String>()

    var onListSessions: suspend () -> SessionListResult = { SessionListResult(sessions = emptyList()) }
    var onResumeSession: suspend (storedId: String) -> SessionResumeResult = { storedId ->
        SessionResumeResult(
            sessionId = "sess_$storedId",
            messageCount = 0,
            messages = emptyList(),
            info = SessionLiveInfo(),
            storedSessionId = storedId,
            sessionKey = storedId,
        )
    }
    var onSessionHistory: suspend (runtimeId: String) -> SessionHistoryResult = { runtimeId ->
        throw UnsupportedOperationException("sessionHistory no configurado ($runtimeId)")
    }
    var onCreateSession: suspend (title: String?) -> SessionCreateResult = {
        SessionCreateResult(
            sessionId = "sess_new",
            storedSessionId = "stored_new",
            messageCount = 0,
            messages = emptyList(),
            info = SessionLiveInfo(),
        )
    }
    var onDeleteSession: suspend (storedId: String) -> SessionDeleteResult = { storedId ->
        if (liveByStored.containsKey(storedId)) {
            throw JsonRpcException(CANNOT_DELETE_ACTIVE_CODE, "cannot delete an active session")
        }
        SessionDeleteResult(deleted = storedId)
    }
    var onCloseSession: suspend (runtimeId: String) -> SessionCloseResult = { runtimeId ->
        SessionCloseResult(closed = liveByStored.values.remove(runtimeId))
    }
    var onRenameSession: suspend (runtimeId: String, title: String) -> SessionTitleResult = { _, title ->
        SessionTitleResult(title = title)
    }

    override suspend fun listSessions(): SessionListResult {
        calls += "list"
        return onListSessions()
    }

    override suspend fun resumeSession(storedSessionId: String): SessionResumeResult {
        calls += "resume:$storedSessionId"
        return onResumeSession(storedSessionId).also { result ->
            liveByStored[canonicalOf(storedSessionId, result)] = result.sessionId
        }
    }

    override suspend fun sessionHistory(runtimeSessionId: String): SessionHistoryResult {
        calls += "history:$runtimeSessionId"
        return onSessionHistory(runtimeSessionId)
    }

    override suspend fun createSession(title: String?): SessionCreateResult {
        calls += "create:${title.orEmpty()}"
        return onCreateSession(title).also { result ->
            liveByStored[result.storedSessionId] = result.sessionId
        }
    }

    override suspend fun deleteSession(storedSessionId: String): SessionDeleteResult {
        calls += "delete:$storedSessionId"
        return onDeleteSession(storedSessionId)
    }

    override suspend fun closeSession(runtimeSessionId: String): SessionCloseResult {
        calls += "close:$runtimeSessionId"
        return onCloseSession(runtimeSessionId)
    }

    override suspend fun renameSession(
        runtimeSessionId: String,
        title: String,
    ): SessionTitleResult {
        calls += "rename:$runtimeSessionId:$title"
        return onRenameSession(runtimeSessionId, title)
    }

    /** Inyecta un evento tal cual llegaría del canal (replay=0: hay que estar suscrito). */
    suspend fun emit(
        type: String,
        sessionId: String? = null,
        seq: Long? = null,
        payload: JsonElement = JsonNull,
    ) {
        _events.emit(GatewayEvent(type = type, sessionId = sessionId, seq = seq, payload = payload))
    }

    /** Deja todos los RPC fallando con [cause]: el mundo "sin red" de los tests de caché. */
    fun failAll(cause: Throwable) {
        val fail: suspend () -> Nothing = { throw cause }
        onListSessions = { fail() }
        onResumeSession = { fail() }
        onSessionHistory = { fail() }
        onCreateSession = { fail() }
        onDeleteSession = { fail() }
        onCloseSession = { fail() }
        onRenameSession = { _, _ -> fail() }
    }

    /** El stored canónico del result — la misma precedencia que aplica el repositorio. */
    private fun canonicalOf(
        requested: String,
        result: SessionResumeResult,
    ): String =
        result.storedSessionId?.takeIf { it.isNotBlank() }
            ?: result.sessionKey?.takeIf { it.isNotBlank() && it != result.sessionId }
            ?: requested

    private companion object {
        const val EVENT_BUFFER_CAPACITY = 64

        /** `cannot delete an active session` del backend (`session.delete` sobre una sesión viva). */
        const val CANNOT_DELETE_ACTIVE_CODE = 4023
    }
}
