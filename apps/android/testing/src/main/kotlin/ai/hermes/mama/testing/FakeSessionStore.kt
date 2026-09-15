package ai.hermes.mama.testing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.Collections
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
    /** Transcript proyectado (filas `TranscriptMessage`). Sincronizada: la leen/escriben varias corrutinas. */
    private val messages: MutableList<JsonObject> =
        Collections.synchronizedList(initialMessages.toMutableList())

    /** Log de eventos de ESTA sesión (`{type, session_id, seq, payload}`) para `session.events.since`. */
    private val eventLog: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())

    /** Contador `seq` por sesión (§2.2: monótono; el cliente rellena huecos con `events.since`). */
    val seq = AtomicLong(0)

    /**
     * Perfil del chat (el backend exige sesión CON perfil en `browser.controller.register`;
     * el fake fija "default", como el perfil estándar del servidor).
     */
    val profile: String = "default"

    /**
     * Job del turno que corre AHORA bajo [turnMutex] — lo asigna [TurnRunner] al
     * entrar (nunca un job encolado: interrupt/delete deben cortar el VIVO).
     */
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

    /** Copia inmutable del transcript (los readers nunca iteran la lista viva). */
    fun messagesSnapshot(): List<JsonObject> = synchronized(messages) { messages.toList() }

    /** Copia inmutable del log de eventos para `session.events.since`. */
    fun eventLogSnapshot(): List<JsonObject> = synchronized(eventLog) { eventLog.toList() }

    /** Añade una fila al transcript; devuelve el `row_id` (1-based) asignado. */
    fun addMessage(row: JsonObject): Int =
        synchronized(messages) {
            messages.add(row)
            messages.size
        }

    fun messageCount(): Int = synchronized(messages) { messages.size }

    /** Registra un evento con `seq` aplicando el tope del log (FIFO). */
    fun appendEvent(params: JsonObject) {
        synchronized(eventLog) {
            eventLog.add(params)
            if (eventLog.size > EVENT_LOG_CAP) {
                eventLog.removeAt(0)
            }
        }
    }

    private companion object {
        const val EVENT_LOG_CAP = 500
    }
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

/**
 * Registro devuelto por `browser.controller.register` (§2.6). [conn] es la
 * conexión propietaria: `result`/`heartbeat`/`detach` de OTRA conexión fallan
 * (4403), como el `is_owner` del broker real.
 */
data class ControllerRegistration(
    val controllerId: String,
    val browserProfileId: String,
    val capabilities: Set<String>,
    val session: FakeSession,
    val conn: WsConnection,
)

/** Comando de navegador emitido y aún sin `browser.controller.result`. */
class PendingBrowserCommand(
    val commandId: String,
    val action: String,
    val toolCallId: String?,
    val controllerId: String,
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

/**
 * Identidad sellada en el socket (§2.1/§2.6: la sella el servidor, nunca el
 * cliente). `authenticated=false` en conexiones dev sin ticket: el backend real
 * les deja entrar pero `browser.controller.*` las rechaza con 4403.
 */
data class FakeIdentity(
    val userId: String,
    val provider: String,
    val authenticated: Boolean = true,
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

    /**
     * Registra la sesión stored; con [live]=true entra también al índice runtime
     * (sesión VIVA del real). Una seed SIN `runtime_id` queda stored-only
     * (borrable) hasta que `session.resume` la sube con [markLive].
     */
    fun add(
        session: FakeSession,
        live: Boolean = true,
    ) {
        sessions[session.storedId] = session
        if (live) {
            runtimeIndex[session.runtimeId] = session
        }
    }

    /** `session.resume` del real sube la sesión al registro vivo (luego delete → 4023). */
    fun markLive(session: FakeSession) {
        runtimeIndex[session.runtimeId] = session
    }

    /** ¿El storedId pertenece a una sesión VIVA? (el 4023 de `session.delete` del real). */
    fun isLive(storedId: String): Boolean = runtimeIndex.values.any { it.storedId == storedId }

    fun remove(storedId: String): FakeSession? {
        val session = sessions.remove(storedId) ?: return null
        runtimeIndex.remove(session.runtimeId)
        return session
    }

    /**
     * Resuelve `session_id` por su id RUNTIME (sess_*), única forma que acepta el
     * backend para los RPC con sesión viva (`_sess_nowait` → 4001). Sin
     * fallback por título ni stored: un id erróneo debe fallar, no casar otra cosa.
     */
    fun resolveRuntime(sessionId: String?): FakeSession? =
        sessionId?.takeUnless { it.isEmpty() }?.let(runtimeIndex::get)

    /**
     * Resuelve `session_id` por su id STORED (stored_* / session_key): la forma
     * que exigen `session.resume` y `session.delete` (el real da 4007 si no está).
     */
    fun resolveStored(sessionId: String?): FakeSession? = sessionId?.takeUnless { it.isEmpty() }?.let(sessions::get)

    fun nextSrqId(): String = "srq-%012x".format(SRQ_COUNTER.incrementAndGet() and SRQ_MASK)

    fun nextRequestId(): String = "req-%06x".format(REQ_COUNTER.incrementAndGet())

    fun nextCommandId(): String = "cmd-%06x".format(CMD_COUNTER.incrementAndGet())

    /** `tool_call_id` del broker real cuando el guion no fija uno (va en el frame `browser.controller.command`). */
    fun nextToolCallId(): String = "tc-%06x".format(TC_COUNTER.incrementAndGet())

    fun nextToolId(): String = "tool-%06x".format(TOOL_COUNTER.incrementAndGet())

    private companion object {
        val SRQ_COUNTER = AtomicLong()
        val REQ_COUNTER = AtomicLong()
        val CMD_COUNTER = AtomicLong()
        val TC_COUNTER = AtomicLong()
        val TOOL_COUNTER = AtomicLong()
        const val SRQ_MASK = 0xFFFFFFFFFFFFL
    }
}
