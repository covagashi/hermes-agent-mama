package ai.hermes.mama.feature.chat.conversation

import ai.hermes.mama.contract.PromptSubmitResult
import ai.hermes.mama.contract.PromptSubmitStatus
import ai.hermes.mama.core.storage.ChatEntity
import ai.hermes.mama.core.storage.LiveTurn
import ai.hermes.mama.core.storage.SessionRepository
import ai.hermes.mama.core.ui.components.ChatBubbleAuthor
import ai.hermes.mama.gateway.ConnectionState
import ai.hermes.mama.gateway.GatewayClient
import ai.hermes.mama.gateway.GatewayEvent
import ai.hermes.mama.gateway.JsonRpcException
import ai.hermes.mama.gateway.interruptSession
import ai.hermes.mama.gateway.sessionEventsSince
import ai.hermes.mama.gateway.submitPrompt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Una generación de conexión viva con el backend (un socket == una generación,
 * B1/B2): el [SessionRepository] de B6 (caché Room + reducer de eventos) y el
 * [GatewayClient] de B4 de esa generación.
 *
 * Quien compone la app (C8) emite una por cada `ConnectionState.Connected` del
 * `ConnectionManager` — el flujo debe REENTREGAR la generación vigente a un
 * suscriptor nuevo (p. ej. `state.filterIsInstance<Connected>().map {…}`), de
 * modo que abrir el chat en medio de una conexión sana no espere a otra.
 */
data class ChatGeneration(
    val repository: SessionRepository,
    val client: GatewayClient,
)

/**
 * ViewModel de la pantalla Chat (ROADMAP C4): transcript + streaming +
 * actividad de Hermes sobre UN chat.
 *
 * - **Transcript**: `SessionRepository.messages(storedId)` (Room, offline
 *   primero) fundido con los envíos optimistas en [send]; la fila `user` del
 *   servidor llega vía `history` y consume al pendiente sin duplicar.
 * - **Streaming**: los `message.delta` viven en `liveTurn` (memoria del repo);
 *   [liveText] es `State` aparte para que el transcript no recomponga por delta.
 * - **Actividad/avisos**: `tool.start`/`tool.complete`, `status.update`,
 *   `error` y `notice` NO pasan por el repositorio (B6 los ignora a propósito):
 *   se colectan aquí directamente de `client.events` para el chip, el
 *   subtítulo y la franja de avisos.
 * - **Reconexión**: cada generación nueva re-abre el chat (`session.resume`); si
 *   ya había `seq` vistos, `session.events.since(last_seen)` rellena el hueco —
 *   los eventos pasan por `replayEvents` al reducer (deltas/completes) y por el
 *   propio VM (chip/avisos) — y `session.history` refresca el transcript.
 * - **Suscripción temprana**: el colector de eventos arranca ANTES de
 *   `session.resume` en cada generación (`replay = 0` en el canal); los que
 *   lleguen antes de conocer el runtime id se guardan un instante y se
 *   reevalúan tras abrir.
 *
 * Ciclo de vida: [close] al salir de la pantalla (la generación la gobierna el
 * dueño del socket, no este VM).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val storedId: String,
    private val generations: Flow<ChatGeneration>,
    private val scope: CoroutineScope,
    connectionState: Flow<ConnectionState> = emptyFlow(),
    private val nowEpochSeconds: () -> Double = { System.currentTimeMillis() / MILLIS_PER_SECOND },
    private val logger: (String) -> Unit = {},
) {
    private val repoFlow = MutableStateFlow<SessionRepository?>(null)

    /** storedId canónico (un `session.resume` puede migrar la fila a la punta de linaje). */
    private val storedFlow = MutableStateFlow(storedId)

    private var generation: ChatGeneration? = null
    private var generationJob: Job? = null
    private var mainJob: Job? = null
    private var runtimeTrackerJob: Job? = null

    /** Último `seq` visto en vivo o por replay — `last_seen` de `session.events.since`. */
    private var lastSeenSeq = 0L

    /** Runtime id de la generación vigente (`null` hasta el primer `open`). */
    @Volatile
    private var runtimeId: String? = null

    /** Eventos de sesión llegados antes de saber si son de ESTE chat (ventana open). */
    private val preOpenEvents = ArrayDeque<GatewayEvent>()

    private var pendingCounter = 0

    private val pendingMessages = MutableStateFlow<List<PendingMessage>>(emptyList())

    private val _notices = MutableSharedFlow<ChatNotice>(extraBufferCapacity = NOTICE_BUFFER)

    /**
     * Chip/subtítulo/encolado: el [ChatEventSink] traduce `tool.*`,
     * `status.update`, `error`/`notice` y `message.*` a estado visible;
     * `onAssistantStart` refresca el transcript cuando arranca el turno.
     */
    private val sink =
        ChatEventSink(
            emitNotice = { notice -> _notices.tryEmit(notice) },
            onAssistantStart = { refreshHistory() },
        )

    /** Chip de actividad visible (mapa `tool.start` → [ActivityKind]); `null` = oculto. */
    val activity: StateFlow<ActivityKind?> = sink.activity

    /** Avisos puntuales para la franja de la pantalla (envío fallido, `error`/`notice` del wire). */
    val notices: SharedFlow<ChatNotice> = _notices.asSharedFlow()

    private val chatFlow: Flow<ChatEntity?> =
        repoFlow
            .filterNotNull()
            .flatMapLatest { repo ->
                combine(repo.chats, storedFlow) { chats, sid -> chats.firstOrNull { it.storedId == sid } }
            }

    /** `true` mientras no hay `Connected` en la generación actual (franja "Sin conexión"). */
    val offline: StateFlow<Boolean> =
        connectionState
            .map { state -> state !is ConnectionState.Connected }
            .stateIn(scope, SharingStarted.Eagerly, false)

    /** Turno del asistente en vuelo (texto acumulado de deltas; `null` = sin streaming). */
    val liveTurn: StateFlow<LiveTurn?> =
        combine(repoFlow.filterNotNull(), chatFlow) { repo, chat -> repo to chat?.runtimeId }
            .flatMapLatest { (repo, rid) -> if (rid == null) flowOf(null) else repo.liveTurn(rid) }
            .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /**
     * Texto del streaming, como `State` separado del item del transcript (C4):
     * la burbuja viva lo colecta ella sola y un delta no recompone la lista.
     */
    val liveText: StateFlow<String> =
        liveTurn
            .map { it?.text.orEmpty() }
            .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), "")

    /** `true` entre `message.start`/primer delta y `message.complete`. */
    val liveStreaming: StateFlow<Boolean> =
        liveTurn
            .map { it != null }
            .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    /** Cabecera (título + línea de estado), lista para la barra superior. */
    val header: StateFlow<ChatHeader> =
        combine(chatFlow, sink.statusLine, liveStreaming, sink.queuedSubmit) { chat, status, streaming, queued ->
            ChatHeader(
                title = chat?.title.orEmpty(),
                statusText = status,
                streaming = streaming,
                running = chat?.running == true,
                queued = queued,
            )
        }.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ChatHeader())

    /** Renglones de la `LazyColumn` en orden cronológico (mensajes + pendientes + separadores de día). */
    val items: StateFlow<List<ChatListItem>> =
        combine(
            repoFlow
                .filterNotNull()
                .flatMapLatest { repo -> storedFlow.flatMapLatest { sid -> repo.messages(sid) } },
            pendingMessages,
        ) { entities, pending ->
            buildChatItems(
                mergePending(entities.mapNotNull { it.toChatMessage() }, pending),
                nowEpochSeconds,
            )
        }.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    init {
        mainJob =
            scope.launch {
                generations.collect { generation -> bindGeneration(generation) }
            }
        // `session.info` puede cambiar el runtime id en caliente (reducer → Room):
        // el filtro de eventos sigue el id que Room dice vigente.
        runtimeTrackerJob =
            scope.launch {
                chatFlow.collect { chat -> chat?.runtimeId?.let { runtimeId = it } }
            }
    }

    /** Envía el texto del composer (burbuja optimista + `prompt.submit`). */
    fun send(text: String) {
        if (text.isBlank()) {
            return
        }
        val entry = PendingMessage(key = "pend-${pendingCounter++}", text = text)
        pendingMessages.update { it + entry }
        scope.launch {
            val generation = generation
            if (generation == null) {
                markFailed(entry.key)
                _notices.tryEmit(ChatNotice.SendFailed)
                return@launch
            }
            val result =
                runCatching { submitWithReopen(generation, text) }.getOrElse {
                    markFailed(entry.key)
                    _notices.tryEmit(ChatNotice.SendFailed)
                    return@launch
                }
            sink.queuedSubmit.value = result.status == PromptSubmitStatus.QUEUED
            // El servidor ya persistió la fila `user` al aceptar el submit:
            // refrescar la caché funde la burbuja optimista sin duplicarla.
            refreshHistory(generation)
        }
    }

    /** «Parar» → `session.interrupt` sobre el runtime vivo. */
    fun stop() {
        val generation = generation ?: return
        scope.launch {
            runCatching {
                generation.client.interruptSession(generation.repository.runtimeIdFor(storedFlow.value))
            }.onFailure {
                warn("session.interrupt falló (${it::class.simpleName})")
                _notices.tryEmit(ChatNotice.InterruptFailed)
            }
        }
    }

    /** Reintenta un envío marcado como fallido (misma burbuja optimista, otra vez). */
    fun retry(messageKey: String) {
        val entry = pendingMessages.value.firstOrNull { it.key == messageKey && it.failed } ?: return
        pendingMessages.update { list -> list.filterNot { it.key == messageKey } }
        send(entry.text)
    }

    /** Detiene los colectores (la pantalla se va; la generación sigue siendo del dueño). */
    fun close() {
        mainJob?.cancel()
        runtimeTrackerJob?.cancel()
        generationJob?.cancel()
        generation = null
        repoFlow.value = null
    }

    // --- ciclo de generación (un bind por socket Connected) ---

    private fun bindGeneration(generation: ChatGeneration) {
        generationJob?.cancel()
        this.generation = generation
        runtimeId = null
        preOpenEvents.clear()
        repoFlow.value = generation.repository
        sink.reset()
        generationJob = scope.launch { runGeneration(generation) }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun runGeneration(generation: ChatGeneration) {
        coroutineScope {
            // ANTES de cualquier tráfico (events es replay=0): lo que llegue
            // sin runtime id conocido se guarda un instante en preOpenEvents.
            launch { collectEvents(generation) }
            try {
                val opened = generation.repository.open(storedFlow.value)
                storedFlow.value = opened.storedId
                runtimeId = opened.runtimeId
                drainPreOpen(opened.runtimeId)
                // Reconexión (ya habíamos visto eventos): rellena el hueco por
                // replay y refresca el transcript — §C4 "Reconnected → history()
                // + session.events.since(último seq)".
                if (lastSeenSeq > 0) {
                    resync(generation, opened.runtimeId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warn("apertura del chat falló (${e::class.simpleName})")
                _notices.tryEmit(ChatNotice.GatewayError)
            }
        }
    }

    /** Re-evalúa los eventos retenidos: los de este runtime ya se pueden aplicar. */
    private fun drainPreOpen(runtimeId: String) {
        val ours = preOpenEvents.filter { it.sessionId == runtimeId }
        preOpenEvents.clear()
        ours.forEach { onSessionEvent(it) }
    }

    private suspend fun collectEvents(generation: ChatGeneration) {
        generation.client.events.collect { event ->
            val rid = runtimeId
            when {
                // Broadcast (session_id ""): no es de este chat — el reducer ya lo atiende.
                event.sessionId == null -> trackSeq(event)
                rid == null -> preOpenEvents.addLast(event)
                event.sessionId == rid -> onSessionEvent(event)
                else -> Unit // evento de OTRA sesión del mismo socket
            }
        }
    }

    private fun onSessionEvent(event: GatewayEvent) {
        trackSeq(event)
        val client = generation?.client ?: return
        sink.onEvent(client, event)
    }

    private fun trackSeq(event: GatewayEvent) {
        val seq = event.seq ?: return
        if (seq > lastSeenSeq) {
            lastSeenSeq = seq
        }
    }

    // --- resync tras reconexión ---

    private suspend fun resync(
        generation: ChatGeneration,
        runtimeId: String,
    ) {
        val since =
            runCatching { generation.client.sessionEventsSince(runtimeId, lastSeenSeq) }
                .getOrElse {
                    warn("session.events.since falló (${it::class.simpleName})")
                    null
                }
        if (since != null) {
            val fresh =
                since.events
                    .mapNotNull { it.toGatewayEvent() }
                    .sortedBy { it.seq ?: Long.MAX_VALUE }
                    .filter { event -> event.seq.let { seq -> seq == null || seq > lastSeenSeq } }
            for (event in fresh) {
                trackSeq(event)
                onSessionEvent(event)
            }
            generation.repository.replayEvents(fresh)
        }
        refreshHistory(generation)
    }

    // --- helpers ---

    private suspend fun submitWithReopen(
        generation: ChatGeneration,
        text: String,
    ): PromptSubmitResult =
        try {
            generation.client.submitPrompt(generation.repository.runtimeIdFor(storedFlow.value), text)
        } catch (e: JsonRpcException) {
            if (e.code != SESSION_STALE_CODE) {
                throw e
            }
            // Runtime id obsoleto (el gateway se reinició): reabrir reaprende y reintenta una vez.
            generation.repository.open(storedFlow.value)
            generation.client.submitPrompt(generation.repository.runtimeIdFor(storedFlow.value), text)
        }

    private fun refreshHistory() {
        if (pendingMessages.value.none { !it.failed }) {
            return
        }
        val generation = generation ?: return
        refreshHistory(generation)
    }

    private fun refreshHistory(generation: ChatGeneration) {
        scope.launch {
            runCatching { generation.repository.history(storedFlow.value) }
                .onFailure { warn("session.history falló (${it::class.simpleName})") }
        }
    }

    private fun markFailed(key: String) {
        pendingMessages.update { list -> list.map { p -> if (p.key == key) p.copy(failed = true) else p } }
    }

    private fun warn(message: String) {
        runCatching { logger(message) }
    }

    private companion object {
        const val SESSION_STALE_CODE = 4001
        const val STOP_TIMEOUT_MS = 5_000L
        const val NOTICE_BUFFER = 8
        const val MILLIS_PER_SECOND = 1_000.0
    }
}
