package ai.hermes.mama.testing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Estado en memoria del [FakeGateway]: sesiones (transcript + log de eventos),
 * peticiones servidor→cliente abiertas, aprobaciones pendientes, controladores
 * de navegador y tickets/cookies de auth. Nada sobrevive al proceso.
 *
 * Una sesión de chat del fake. Los ids siguen el contrato: `storedId` para
 * `session.list`/`resume`/`delete`, `runtimeId` para `prompt.submit` y eventos.
 */
class FakeSession(
    val storedId: String,
    val runtimeId: String,
    var title: String,
    var preview: String,
    val startedAt: Double,
    val source: String,
    val hidden: Boolean,
    initialMessages: List<JsonObject>,
) {
    /** Transcript proyectado (filas `TranscriptMessage`). */
    val messages: MutableList<JsonObject> = initialMessages.toMutableList()

    /** Log de eventos de ESTA sesión (`{type, session_id, seq, payload}`) para `session.events.since`. */
    val eventLog: MutableList<JsonObject> = mutableListOf()

    /** Contador `seq` por sesión (§2.2: monótono; el cliente rellena huecos con `events.since`). */
    val seq = AtomicLong(0)

    /** Turno en ejecución (la corrutina que corre los pasos del guion). */
    @Volatile
    var turnJob: Job? = null

    /** Serializa turnos del mismo chat: un prompt a la vez, como el backend real. */
    val turnMutex = Mutex()

    /** `session.info.running` mientras corre un turno. */
    @Volatile
    var running: Boolean = false

    /** Peticiones `srq-*` abiertas dirigidas a esta sesión (re-entregables por `open_requests`). */
    val openRequests = ConcurrentHashMap<String, OpenRequest>()

    /** Aprobaciones pendientes por `request_id` (para `approval.pending`/`approval.respond`). */
    val pendingApprovals = ConcurrentHashMap<String, PendingApproval>()

    /** Controladores de navegador registrados (`controller_id` → registro). */
    val controllers = ConcurrentHashMap<String, ControllerRegistration>()

    /** Último `command_id` de navegador emitido (para `browser_cancel` sin id). */
    @Volatile
    var lastCommandId: String? = null
}

/** Petición servidor→cliente `srq-*` aún sin respuesta. */
class OpenRequest(
    val id: String,
    val method: String,
    val params: JsonObject,
    val session: FakeSession,
    val conn: WsConnection,
) {
    /** Se completa cuando llega `{"id": "<srq>", "result"|"error"}` o `request.answer`. */
    val response = CompletableDeferred<JsonObject>()
}

/** Una aprobación emitida como `approval` y aún sin resolver (vista de `approval.pending`). */
class PendingApproval(
    val requestId: String,
    val srqId: String,
    val session: FakeSession,
    val params: JsonObject,
)

/** Registro devuelto por `browser.controller.register`. */
data class ControllerRegistration(
    val controllerId: String,
    val browserProfileId: String,
    val capabilities: List<String>,
    val session: FakeSession,
)

/** Comando de navegador emitido y aún sin `browser.controller.result`. */
class PendingBrowserCommand(
    val commandId: String,
    val action: String,
    val session: FakeSession,
    val conn: WsConnection,
) {
    val result = CompletableDeferred<JsonObject>()
}

/** Registro para tests: respuesta que el cliente dio a una petición `srq-*` o comando. */
data class AnsweredRequest(
    val id: String,
    val method: String,
    val result: JsonElement?,
    val error: JsonElement?,
)

/** Identidad sellada en el socket por ticket válido (§2.1/§2.6: la sella el servidor). */
data class FakeIdentity(
    val userId: String,
    val provider: String,
)

/** Estado compartido del gateway (una instancia por [FakeGateway]). */
class FakeSessionStore {
    /** Sesiones por `storedId`. */
    val sessions = ConcurrentHashMap<String, FakeSession>()

    private val runtimeIndex = ConcurrentHashMap<String, FakeSession>()
    private val idCounter = AtomicLong(0)

    /** Todas las peticiones `srq-*` abiertas (cualquier sesión), para correlacionar respuestas. */
    val openRequestsById = ConcurrentHashMap<String, OpenRequest>()

    /** Comandos de navegador en vuelo por `command_id`. */
    val pendingCommands = ConcurrentHashMap<String, PendingBrowserCommand>()

    fun nextRuntimeId(): String = "sess_%06x".format(idCounter.incrementAndGet())

    fun nextStoredId(): String = "stored_%06x".format(idCounter.incrementAndGet())

    fun add(session: FakeSession) {
        sessions[session.storedId] = session
        runtimeIndex[session.runtimeId] = session
    }

    fun remove(storedId: String): FakeSession? {
        val session = sessions.remove(storedId) ?: return null
        runtimeIndex.remove(session.runtimeId)
        return session
    }

    /** Resuelve un id en cualquiera de sus formas: stored, runtime o título exacto. */
    fun resolve(sessionId: String?): FakeSession? {
        if (sessionId.isNullOrEmpty()) {
            return null
        }
        return sessions[sessionId]
            ?: runtimeIndex[sessionId]
            ?: sessions.values.firstOrNull { it.title == sessionId }
    }

    fun nextSrqId(): String = "srq-%012x".format(SRQ_COUNTER.incrementAndGet() and SRQ_MASK)

    fun nextRequestId(): String = "req-%06x".format(REQ_COUNTER.incrementAndGet())

    fun nextCommandId(): String = "cmd-%06x".format(CMD_COUNTER.incrementAndGet())

    fun nextToolId(): String = "tool-%06x".format(TOOL_COUNTER.incrementAndGet())

    private companion object {
        val SRQ_COUNTER = AtomicLong()
        val REQ_COUNTER = AtomicLong()
        val CMD_COUNTER = AtomicLong()
        val TOOL_COUNTER = AtomicLong()
        const val SRQ_MASK = 0xFFFFFFFFFFFFL
    }
}
