package ai.hermes.mama.feature.chat.approval

import ai.hermes.mama.contract.ApprovalChoice
import ai.hermes.mama.contract.EventTypes
import ai.hermes.mama.contract.PendingApproval
import ai.hermes.mama.contract.RequestCancelPayload
import ai.hermes.mama.contract.ServerRequests
import ai.hermes.mama.gateway.ApprovalRequest
import ai.hermes.mama.gateway.ClarifyRequest
import ai.hermes.mama.gateway.GatewayClient
import ai.hermes.mama.gateway.UnsupportedRequest
import ai.hermes.mama.gateway.pendingApprovals
import ai.hermes.mama.gateway.respondApproval
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Aviso para la UI fuera de la tarjeta (C6; el texto humano lo resuelve la
 * pantalla desde `strings_aprobacion.xml`).
 */
sealed interface ApprovalNotice {
    /**
     * §2.5: llegó una petición que la app no soporta (`secret`, `sudo`,
     * `vault.*`…) — su `-32601` ya salió automáticamente (B4); sólo queda
     * mostrar el aviso humano ("Hermes necesita algo que esta app no puede
     * dar…").
     */
    data object UnsupportedRequest : ApprovalNotice
}

/**
 * Coordinador de las tarjetas Sí/No del chat (ROADMAP C6, §2.5):
 *
 * - Colecta [GatewayClient.serverRequests]: cada `approval` se convierte en
 *   una entrada de cola FIFO; [card] expone la primera como
 *   [ApprovalCardState] (o `null` = oculta). Las `clarify` se reenvían a
 *   [clarifyRequests] para C7 (este colector es dueño único de la cola del
 *   canal) y las no soportadas llegan como [notices].
 * - [approve]/[deny] responden `once`/`deny` — nunca `always`/`session` —
 *   por el camino que toca: la petición viva ([ApprovalRequest.approve] /
 *   [ApprovalRequest.deny], `result` directo al frame) o `approval.respond`
 *   para las re-sincronizadas con [resync].
 * - `request.cancel {id, method:"approval"}` cierra la tarjeta con ese id —
 *   el `id` del payload es el de frame (`srq-…`), que es [ApprovalRequest.id].
 * - Replay/re-entrega: una `approval` re-entregada con el mismo `request_id`
 *   no duplica la tarjeta (se re-liga al objeto nuevo; si ya la habíamos
 *   respondido, la misma elección sale otra vez sin molestar a la usuaria).
 * - Tras responder, la elección queda visible [answeredVisibleMs] antes de
 *   pasar a la siguiente aprobación encolada.
 *
 * El ciclo de vida es del llamador (C8 lo atará a la pantalla de chat):
 * [start] una vez por generación de client, [close] al soltarla.
 */
class ApprovalController(
    private val client: GatewayClient,
    private val scope: CoroutineScope,
    private val answeredVisibleMs: Long = DEFAULT_ANSWERED_VISIBLE_MS,
    private val logger: (String) -> Unit = {},
) {
    private val mutex = Mutex()
    private val entries = ArrayDeque<Entry>()
    private var collectors: List<Job> = emptyList()
    private var hideJob: Job? = null

    private val _card = MutableStateFlow<ApprovalCardState?>(null)

    /** La tarjeta visible, o `null` si no hay aprobación pendiente. */
    val card: StateFlow<ApprovalCardState?> = _card.asStateFlow()

    // Cola acotada como la del canal: una clarify no entregada no debe
    // desaparecer en silencio (el backend se queda esperando). Si nadie la
    // recoge (C7 aún no colecta), se responde cancel-all para no colgarla.
    private val clarifyQueue = Channel<ClarifyRequest>(capacity = CLARIFY_QUEUE_CAPACITY)

    /** Peticiones `clarify` reenviadas tal cual llegan — el punto de enganche de C7. */
    val clarifyRequests: Flow<ClarifyRequest> = clarifyQueue.receiveAsFlow()

    private val _notices = MutableSharedFlow<ApprovalNotice>(extraBufferCapacity = NOTICE_BUFFER)

    /** Avisos humanos fuera de la tarjeta (hoy: petición no soportada). */
    val notices: SharedFlow<ApprovalNotice> = _notices.asSharedFlow()

    init {
        require(answeredVisibleMs > 0) { "answeredVisibleMs debe ser > 0" }
    }

    /** Empieza a colectar peticiones y `request.cancel`. Idempotente. */
    fun start() {
        if (collectors.isNotEmpty()) {
            return
        }
        collectors =
            listOf(
                scope.launch { collectServerRequests() },
                scope.launch { collectCancels() },
            )
    }

    /** Detiene los colectores, cierra la cola de clarify y oculta la tarjeta (el client vive o muere fuera). */
    fun close() {
        collectors.forEach { it.cancel() }
        collectors = emptyList()
        hideJob?.cancel()
        hideJob = null
        clarifyQueue.close()
        _card.value = null
    }

    /** La usuaria pulsó "Sí, adelante" → `{"choice":"once"}` (§2.5). */
    fun approve() = choose(approved = true)

    /** La usuaria pulsó "No" → `{"choice":"deny"}` (§2.5). */
    fun deny() = choose(approved = false)

    /**
     * Re-sincroniza tras una reconexión (C6): `approval.pending` devuelve las
     * aprobaciones de la sesión que siguen abiertas en el servidor; se funden
     * con la cola local (sin duplicar por `request_id`) y las re-sincronizadas
     * que ya no existen en el servidor desaparecen (las resolvió otra
     * superficie o expiraron). Las vivas de esta generación de socket no se
     * tocan — su ciclo de vida es el wire.
     */
    suspend fun resync(sessionId: String) {
        val pending =
            runCatching { client.pendingApprovals(sessionId).approvals }
                .onFailure { warn("approval.pending falló al re-sincronizar") }
                .getOrNull() ?: return
        mutex.withLock {
            val freshIds = pending.mapNotNullTo(mutableSetOf()) { it.requestId }
            // Ojo: sólo las re-sincronizadas SIN petición viva — una entrada
            // re-ligada a un frame del wire la gobierna el wire, no la lista.
            entries.removeAll { entry -> entry.synced && entry.live == null && entry.requestId !in freshIds }
            pending.forEach { item ->
                val requestId = item.requestId
                if (requestId.isNullOrBlank()) {
                    warn("approval.pending trajo una entrada sin request_id")
                } else if (entries.none { it.key == requestId }) {
                    entries.addLast(syncedEntry(sessionId, requestId, item))
                }
            }
            publish()
        }
    }

    // --- colectores ---

    private suspend fun collectServerRequests() {
        client.serverRequests.collect { request ->
            when (request) {
                is ApprovalRequest -> onApproval(request)
                is ClarifyRequest -> forwardClarify(request)
                is UnsupportedRequest -> _notices.tryEmit(ApprovalNotice.UnsupportedRequest)
            }
        }
    }

    private suspend fun collectCancels() {
        client.events.collect { event ->
            if (event.type != EventTypes.REQUEST_CANCEL) {
                return@collect
            }
            val payload = client.decodePayload(event, RequestCancelPayload.serializer()) ?: return@collect
            if (payload.method != ServerRequests.APPROVAL) {
                // Los cancel de clarify los atiende C7.
                return@collect
            }
            mutex.withLock {
                if (entries.removeAll { entry -> entry.matchesCancel(payload.id) }) {
                    publish()
                }
            }
        }
    }

    /**
     * Una `approval` viva → entrada de cola. Dedup por `request_id` (o id de
     * frame si aquel falta): la re-entrega tras reconexión no duplica tarjeta
     * — re-liga la entrada al objeto nuevo (el viejo respondía por un socket
     * muerto) y, si ya estaba respondida, reenvía la misma elección porque el
     * servidor nunca la recibió (§2.2: responder dos veces es no-op para él).
     */
    private suspend fun onApproval(request: ApprovalRequest) {
        mutex.withLock {
            val key = request.requestId.ifBlank { request.id }
            val existing = entries.firstOrNull { it.key == key }
            if (existing != null) {
                existing.live = request
                // El wire toma posesión: aunque llegó por approval.pending, ya
                // no la poda una re-sync — su ciclo de vida es el wire.
                existing.synced = false
                when (existing.status) {
                    ApprovalStatus.Approved -> reAnswerLocked(request, approved = true)
                    ApprovalStatus.Denied -> reAnswerLocked(request, approved = false)
                    else -> Unit // Pendiente o SendFailed: la usuaria sigue decidiendo.
                }
                return@withLock
            }
            entries.addLast(liveEntry(key, request))
            publish()
        }
    }

    /** Reenvía la elección ya registrada a una re-entrega (sin tocar la UI). */
    private fun reAnswerLocked(
        request: ApprovalRequest,
        approved: Boolean,
    ) {
        scope.launch {
            val sent = if (approved) request.approve() else request.deny()
            if (!sent) {
                warn("re-respuesta de approval re-entregada no salió")
            }
        }
    }

    private fun forwardClarify(request: ClarifyRequest) {
        if (clarifyQueue.trySend(request).isFailure) {
            // Cola llena/cerrada: mejor un cancel-all que dejarla colgando.
            warn("clarify sin consumidor: respondida cancel-all")
            request.dismiss()
        }
    }

    // --- respuestas ---

    private fun choose(approved: Boolean) {
        scope.launch {
            val entry =
                mutex.withLock {
                    val head = entries.firstOrNull()
                    if (head == null || !head.isAnswerable()) {
                        return@withLock null
                    }
                    head.responding = true
                    // Reintento tras SendFailed: quita el aviso mientras se envía.
                    head.status = ApprovalStatus.Pending
                    publish()
                    head
                } ?: return@launch
            val outcome =
                runCatching { answerEntry(entry, approved) }
                    .getOrDefault(AnswerOutcome.Failed)
            mutex.withLock {
                when (outcome) {
                    AnswerOutcome.Sent -> {
                        entry.status = if (approved) ApprovalStatus.Approved else ApprovalStatus.Denied
                        publish()
                        scheduleAutoHideLocked(entry)
                    }
                    AnswerOutcome.AlreadyResolved -> {
                        // La resolvió otra superficie: cerrar sin ruido.
                        entries.remove(entry)
                        publish()
                    }
                    AnswerOutcome.Failed -> {
                        entry.responding = false // reintentable
                        entry.status = ApprovalStatus.SendFailed
                        publish()
                    }
                }
            }
        }
    }

    /** Responde por el camino que toca: wire vivo vs `approval.respond` (ver helpers). */
    private suspend fun answerEntry(
        entry: Entry,
        approved: Boolean,
    ): AnswerOutcome {
        val live = entry.live
        return if (live != null) answerLive(live, approved) else answerSynced(entry, approved)
    }

    /**
     * La petición viva contesta el frame en el socket ([ApprovalRequest.approve] /
     * [ApprovalRequest.deny] → `{"choice":"once"|"deny"}`). `respond()` devuelve
     * `false` si ya estaba respondida (otro consumidor del canal → cerrar) o si
     * el canal está muerto (la respuesta no salió → error visual). OJO:
     * `isAnswered` queda `true` en ambos casos — el discriminante es el canal.
     */
    private fun answerLive(
        live: ApprovalRequest,
        approved: Boolean,
    ): AnswerOutcome {
        val sent = if (approved) live.approve() else live.deny()
        return when {
            sent -> AnswerOutcome.Sent
            client.isClosed -> AnswerOutcome.Failed
            else -> AnswerOutcome.AlreadyResolved
        }
    }

    /** La re-sincronizada va por `approval.respond` con el `request_id` de la cola del servidor. */
    private suspend fun answerSynced(
        entry: Entry,
        approved: Boolean,
    ): AnswerOutcome {
        val requestId = entry.requestId ?: return AnswerOutcome.Failed
        val result =
            runCatching {
                client.respondApproval(
                    entry.sessionId,
                    requestId,
                    if (approved) ApprovalChoice.ONCE else ApprovalChoice.DENY,
                )
            }.getOrNull()
        return when {
            result == null -> AnswerOutcome.Failed
            result.resolved > 0 -> AnswerOutcome.Sent
            else -> AnswerOutcome.AlreadyResolved
        }
    }

    private fun scheduleAutoHideLocked(entry: Entry) {
        hideJob?.cancel()
        hideJob =
            scope.launch {
                delay(answeredVisibleMs)
                mutex.withLock {
                    entries.remove(entry)
                    publish()
                }
            }
    }

    // --- entradas de cola ---

    private fun liveEntry(
        key: String,
        request: ApprovalRequest,
    ): Entry =
        Entry(
            key = key,
            frameId = request.id,
            requestId = request.requestId,
            sessionId = request.sessionId,
            kind = approvalKindFor(request.params.toolName),
            detail = plainDetail(request.params.description, request.params.command),
            synced = false,
            live = request,
        )

    private fun syncedEntry(
        sessionId: String,
        requestId: String,
        pending: PendingApproval,
    ): Entry =
        Entry(
            key = requestId,
            frameId = null,
            requestId = requestId,
            sessionId = sessionId,
            kind = approvalKindFor(pending.toolName),
            detail = plainDetail(pending.description.orEmpty(), pending.command.orEmpty()),
            synced = true,
            live = null,
        )

    private fun publish() {
        _card.value =
            entries.firstOrNull()?.let { head ->
                ApprovalCardState(kind = head.kind, detail = head.detail, status = head.status)
            }
    }

    /** §8: el logger viene de fuera — un logger que lanza no puede tumbar el controller. */
    private fun warn(message: String) {
        runCatching { logger(message) }
    }

    /**
     * Una aprobación en cola. [key] = `request_id` (o id de frame si falta) —
     * la misma aprobación llegada por `approval.pending` y por el wire casa.
     */
    private class Entry(
        val key: String,
        /** Id de frame (`srq-…`) — el que `request.cancel` referencia (sólo vivas). */
        val frameId: String?,
        /** `request_id` de la cola del servidor — el que `approval.respond` usa. */
        val requestId: String?,
        /** Runtime `session_id` — lo piden `approval.pending`/`approval.respond`. */
        val sessionId: String,
        val kind: ApprovalKind,
        val detail: String?,
        /** `true` si la gobierna `approval.pending` (re-sync); pasa a `false` al re-ligar un frame vivo del wire. */
        var synced: Boolean,
        /** Petición viva ligada a la entrada (se re-liga en re-entregas). */
        var live: ApprovalRequest?,
    ) {
        /** Tocado sólo bajo el mutex del controller. */
        var status: ApprovalStatus = ApprovalStatus.Pending

        /** Envío en vuelo: doble tap o Sí+No simultáneos no emiten dos respuestas. */
        var responding: Boolean = false

        /** `true` si la usuaria puede responderla ahora (pendiente o reintento tras fallo). */
        fun isAnswerable(): Boolean =
            !responding && (status == ApprovalStatus.Pending || status == ApprovalStatus.SendFailed)

        fun matchesCancel(id: String): Boolean = frameId == id || requestId == id
    }

    /** Resultado de intentar enviar la respuesta. */
    private enum class AnswerOutcome {
        /** La respuesta salió al cable. */
        Sent,

        /** Ya estaba resuelta en otro sitio → cerrar sin ruido. */
        AlreadyResolved,

        /** No salió (socket muerto / timeout) → aviso visual + reintento. */
        Failed,
    }

    companion object {
        /**
         * Tiempo que la elección ("Has dicho que sí/no") queda visible antes de
         * cerrar la tarjeta y pasar a la siguiente encolada.
         */
        const val DEFAULT_ANSWERED_VISIBLE_MS = 1_600L

        /** Capacidad de la cola de clarify reenviadas a C7. */
        private const val CLARIFY_QUEUE_CAPACITY = 64

        /** Buffer de avisos [notices] sin suscriptor. */
        private const val NOTICE_BUFFER = 16

        /** `description` ya redactada; si falta, `command` (también redactado). */
        private fun plainDetail(
            description: String,
            command: String,
        ): String? = description.ifBlank { command }.ifBlank { null }
    }
}
