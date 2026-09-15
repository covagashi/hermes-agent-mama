package ai.hermes.mama.feature.chat.chats

import ai.hermes.mama.contract.SessionCloseResult
import ai.hermes.mama.contract.SessionCreateResult
import ai.hermes.mama.contract.SessionDeleteResult
import ai.hermes.mama.contract.SessionHistoryResult
import ai.hermes.mama.contract.SessionListResult
import ai.hermes.mama.contract.SessionLiveInfo
import ai.hermes.mama.contract.SessionResumeResult
import ai.hermes.mama.contract.SessionTitleResult
import ai.hermes.mama.contract.TranscriptMessage
import ai.hermes.mama.core.storage.MamaDatabase
import ai.hermes.mama.core.storage.SessionGateway
import ai.hermes.mama.core.storage.SessionRepository
import ai.hermes.mama.gateway.GatewayEvent
import ai.hermes.mama.gateway.JsonRpcException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Soporte compartido de los tests de Chats (C3): [SessionGateway] en memoria
 * con el mismo contrato que el fake de B6 (`core-storage` lo tiene `internal`
 * en su propio source-set de test — no es visible desde aquí), fábrica de
 * [SessionRepository] sobre Room en memoria y la regla de Main dispatcher que
 * `viewModelScope` necesita bajo Robolectric.
 */

internal const val TEST_NOW_MILLIS = 1_700_000_000_000L

/**
 * [SessionGateway] en memoria (mismo contrato que el fake de B6): cada RPC se
 * configura con una lambda `on*` y queda grabado en [calls]; los eventos se
 * inyectan con [emit]. `resume`/`create` dejan el chat **vivo** y `delete`
 * sobre un vivo responde `4023 cannot delete an active session` — igual que
 * `methods_session.py` — para ejercitar el close→delete real.
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
        resumeResult(runtimeId = "sess_$storedId", storedId = storedId)
    }
    var onSessionHistory: suspend (runtimeId: String) -> SessionHistoryResult = { runtimeId ->
        throw UnsupportedOperationException("sessionHistory no configurado ($runtimeId)")
    }
    var onCreateSession: suspend (title: String?) -> SessionCreateResult = { title ->
        val storedId = "stored_new"
        SessionCreateResult(
            sessionId = "sess_new",
            storedSessionId = storedId,
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

/** Repositorio real sobre Room en memoria y [backgroundScope] del test (patrón B6). */
internal fun TestScope.newRepository(
    gateway: SessionGateway,
    db: MamaDatabase,
): SessionRepository =
    SessionRepository(
        gateway = gateway,
        db = db,
        scope = backgroundScope,
        nowEpochSeconds = { TEST_NOW_MILLIS / MILLIS_PER_SECOND },
    )

/**
 * Sondea [probe] hasta que dé no-null o se agote el tiempo.
 *
 * Room escribe en un executor REAL, no en el scheduler virtual de `runTest`:
 * lo que llega vía Room (refresh, create, delete) hay que esperarlo sondeando.
 * Tras cada espera real se bombea el scheduler virtual (`runCurrent`) para que
 * las emisiones encoladas en `Dispatchers.Main` de prueba se procesen aunque el
 * hilo del test no ceda — si no, bajo carga de CI la cola puede quedarse atrás
 * y el sondeo expira aunque Room ya haya escrito. Igual que el `eventually` de
 * B6, pero con el bombeo explícito.
 */
internal suspend fun <T> eventually(
    timeoutMs: Long = 30_000,
    probe: suspend () -> T?,
): T {
    val scheduler = currentCoroutineContext()[TestCoroutineScheduler]
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        probe()?.let { return it }
        check(System.currentTimeMillis() < deadline) { "eventually() agotó ${timeoutMs}ms" }
        withContext(Dispatchers.Default) { delay(10) }
        scheduler?.runCurrent()
    }
}

/**
 * `Dispatchers.Main` → [TestDispatcher] del test: `viewModelScope` del
 * ViewModel corre así en el scheduler virtual de `runTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

/** `SessionResumeResult` de mentira (misma forma que el helper de B6). */
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

private const val MILLIS_PER_SECOND = 1000.0
