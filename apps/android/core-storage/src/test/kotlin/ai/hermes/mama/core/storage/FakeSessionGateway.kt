package ai.hermes.mama.core.storage

import ai.hermes.mama.contract.SessionCreateResult
import ai.hermes.mama.contract.SessionDeleteResult
import ai.hermes.mama.contract.SessionHistoryResult
import ai.hermes.mama.contract.SessionListResult
import ai.hermes.mama.contract.SessionResumeResult
import ai.hermes.mama.contract.SessionTitleResult
import ai.hermes.mama.gateway.GatewayEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [SessionGateway] en memoria (B6): cada RPC se configura con una lambda
 * `on*` y queda grabada en [calls]; los eventos se inyectan con [emit].
 * Sin configurar, los RPCs distintos de list/delete/rename lanzan — un test
 * que toque red sin declararlo falla en vez de pasar de puntillas.
 */
internal class FakeSessionGateway : SessionGateway {
    private val _events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
    override val events: Flow<GatewayEvent> = _events

    /** Llamadas recibidas, `"<metodo>:<sessionId>"` (p. ej. `"resume:s-1"`, `"list"`). */
    val calls = CopyOnWriteArrayList<String>()

    var onListSessions: suspend () -> SessionListResult = { SessionListResult(sessions = emptyList()) }
    var onResumeSession: suspend (storedId: String) -> SessionResumeResult = { storedId ->
        throw UnsupportedOperationException("resumeSession no configurado ($storedId)")
    }
    var onSessionHistory: suspend (runtimeId: String) -> SessionHistoryResult = { runtimeId ->
        throw UnsupportedOperationException("sessionHistory no configurado ($runtimeId)")
    }
    var onCreateSession: suspend (title: String?) -> SessionCreateResult = {
        throw UnsupportedOperationException("createSession no configurado")
    }
    var onDeleteSession: suspend (storedId: String) -> SessionDeleteResult = { storedId ->
        SessionDeleteResult(deleted = storedId)
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
        return onResumeSession(storedSessionId)
    }

    override suspend fun sessionHistory(runtimeSessionId: String): SessionHistoryResult {
        calls += "history:$runtimeSessionId"
        return onSessionHistory(runtimeSessionId)
    }

    override suspend fun createSession(title: String?): SessionCreateResult {
        calls += "create:${title.orEmpty()}"
        return onCreateSession(title)
    }

    override suspend fun deleteSession(storedSessionId: String): SessionDeleteResult {
        calls += "delete:$storedSessionId"
        return onDeleteSession(storedSessionId)
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
        onRenameSession = { _, _ -> fail() }
    }

    private companion object {
        const val EVENT_BUFFER_CAPACITY = 64
    }
}
