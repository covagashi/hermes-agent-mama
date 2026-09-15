package ai.hermes.mama.feature.chat.clarify

import ai.hermes.mama.contract.EventTypes
import ai.hermes.mama.contract.RequestCancelPayload
import ai.hermes.mama.contract.ServerRequests
import ai.hermes.mama.gateway.ClarifyRequest
import ai.hermes.mama.gateway.GatewayClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * Coordinador de las tarjetas de pregunta del chat (ROADMAP C7, §2.5):
 *
 * - Colecta [requests] — la cola `clarifyQueue` que [ai.hermes.mama.feature.chat.approval.ApprovalController]
 *   reenvía desde `GatewayClient.serverRequests` (C6 es el dueño único del
 *   canal; esta clase es su único consumidor). Cada `clarify` se convierte en
 *   una entrada FIFO; [card] expone la primera como [ClarifyCardState].
 * - **Pregunta única** (`{question, choices?, multi_select?}`): [answer] envía
 *   `{"answer": "…"}` (`""` = skip). Con `multi_select`, [answerSelections]
 *   empaqueta las etiquetas como **string JSON de array** (`"[\"a\",\"b\"]"`)
 *   — es la forma que el backend decodifica (`_clean_answer`, igual que la
 *   respuesta por voz/teclado del gateway: `clarify_gateway._coerce_multi_select_text`).
 * - **Lote** (`{questions: [{qid, question, choices?…}]}`): se muestra **una a
 *   una** con progreso "N de M" ([ClarifyCardState.progress]); las respuestas
 *   se acumulan por `qid` y al terminar sale UN frame `{"answers": {qid: "…"}}`
 *   — el mapeo exacto de §2.5. Las `answers` ya bloqueadas que trae una
 *   re-entrega (`open_requests`) se respetan: el lote retoma la primera
 *   pregunta sin responder.
 * - `request.cancel {id, method:"clarify"}` cierra la tarjeta con ese id de
 *   frame. [dismiss] responde `result {}` (cancel-all) — sin UI por ahora.
 * - Replay/re-entrega: un `clarify` re-entregado con el mismo `id` no duplica
 *   la tarjeta (se re-liga al objeto nuevo; si ya la habíamos respondido, el
 *   mismo result final sale otra vez sin molestar a la usuaria).
 *
 * El ciclo de vida es del llamador (C8 lo atará a la pantalla de chat):
 * [start] una vez por generación de client, [close] al soltarla.
 */
class ClarifyController(
    private val client: GatewayClient,
    private val requests: Flow<ClarifyRequest>,
    private val scope: CoroutineScope,
    private val answeredVisibleMs: Long = DEFAULT_ANSWERED_VISIBLE_MS,
    private val logger: (String) -> Unit = {},
) {
    private val mutex = Mutex()
    private val entries = ArrayDeque<Entry>()
    private var collectors: List<Job> = emptyList()
    private var hideJob: Job? = null

    /** `true` tras [close]: corta [publish] tardíos; [start] lo resetea. */
    @Volatile
    private var closed = false

    private val _card = MutableStateFlow<ClarifyCardState?>(null)

    /** La tarjeta visible, o `null` si no hay pregunta pendiente. */
    val card: StateFlow<ClarifyCardState?> = _card.asStateFlow()

    init {
        require(answeredVisibleMs > 0) { "answeredVisibleMs debe ser > 0" }
    }

    /**
     * Empieza a colectar peticiones y `request.cancel`. Idempotente y
     * reiniciable: `close()` para el ciclo, `start()` abre otro — pensado para
     * el lifecycle de la pantalla (C8). La cola sobrevive el stop: el servidor
     * sigue esperando sus respuestas.
     */
    fun start() {
        if (collectors.isNotEmpty()) {
            return
        }
        closed = false
        collectors =
            listOf(
                scope.launch { collectRequests() },
                scope.launch { collectCancels() },
            )
        // Re-publica la cabeza superviviente del ciclo anterior (si la hubo).
        scope.launch { mutex.withLock { publish() } }
    }

    /** Detiene los colectores y oculta la tarjeta (el client vive o muere fuera; [start] reabre). */
    fun close() {
        closed = true
        collectors.forEach { it.cancel() }
        collectors = emptyList()
        hideJob?.cancel()
        hideJob = null
        _card.value = null
    }

    /**
     * La usuaria respondió la pregunta [questionNumber] de la tarjeta [key]
     * con [text] — etiqueta de una `choice`, texto libre, o el string JSON de
     * un multi-select (ver [answerSelections]). En un lote avanza a la
     * siguiente pregunta; en la última (o en pregunta única) envía el result
     * final (`{"answers":…}` / `{"answer":…}`).
     *
     * [questionNumber] es [ClarifyCardState.questionNumber]: un tap tardío
     * sobre la pregunta anterior (doble tap, re-render entre tap y cola) cae
     * en número distinto del vigente y se ignora — nunca responde una pregunta
     * que la usuaria no llegó a ver.
     */
    fun answer(
        key: String,
        questionNumber: Int,
        text: String,
    ) {
        scope.launch {
            val entry =
                mutex.withLock {
                    val shown = entries.firstOrNull { it.key == key }
                    if (shown == null || !shown.isAnswerable() || shown.index + 1 != questionNumber) {
                        return@withLock null
                    }
                    shown.answers[shown.questions[shown.index].qid] = text
                    if (shown.index + 1 < shown.questions.size) {
                        // Pregunta intermedia del lote: nada sale al wire aún.
                        shown.index++
                        publish()
                        return@withLock null
                    }
                    shown.responding = true
                    shown.status = ClarifyStatus.Pending // limpia un SendFailed previo
                    publish()
                    shown
                } ?: return@launch
            sendFinal(entry)
        }
    }

    /**
     * Variante `multi_select` de [answer]: empaqueta [wireTexts] (las etiquetas
     * elegidas, en el orden en que el servidor las ofreció) como string JSON de
     * array — `["a","b"]` — que es lo que el backend decodifica como lista.
     */
    fun answerSelections(
        key: String,
        questionNumber: Int,
        wireTexts: List<String>,
    ) = answer(key, questionNumber, encodeSelections(wireTexts))

    /**
     * Cierra la petición sin responder — `result {}` = cancel-all (doc del
     * schema). No hay botón en la UI (mockup); lo usa el ciclo de vida si la
     * pantalla muere con una pregunta abierta.
     */
    fun dismiss(key: String) {
        scope.launch {
            val entry =
                mutex.withLock {
                    entries
                        .firstOrNull { it.key == key }
                        ?.also { removed ->
                            entries.remove(removed)
                            publish()
                        }
                } ?: return@launch
            val sent = entry.live?.dismiss() ?: false
            if (!sent && !client.isClosed) {
                // Ya respondida en otro sitio: la tarjeta igualmente se fue.
                warn("dismiss de clarify ya respondida")
            }
        }
    }

    // --- colectores ---

    private suspend fun collectRequests() {
        requests.collect { request -> onClarify(request) }
    }

    private suspend fun collectCancels() {
        client.events.collect { event ->
            if (event.type != EventTypes.REQUEST_CANCEL) {
                return@collect
            }
            val payload = client.decodePayload(event, RequestCancelPayload.serializer()) ?: return@collect
            if (payload.method != ServerRequests.CLARIFY) {
                // Los cancel de approval los atiende ApprovalController.
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
     * Una `clarify` viva → entrada de cola. Dedup por `id` de frame (estable en
     * re-entregas de `open_requests`): la re-entrega no duplica tarjeta —
     * re-liga la entrada al objeto nuevo (el viejo respondía por un socket
     * muerto), funde las `answers` que el servidor lleve bloqueadas y, si ya
     * estaba respondida, reenvía el mismo result porque el servidor nunca lo
     * recibió.
     */
    private suspend fun onClarify(request: ClarifyRequest) {
        mutex.withLock {
            val existing = entries.firstOrNull { it.key == request.id }
            if (existing != null) {
                existing.live = request
                existing.mergeLocked(request)
                when (existing.status) {
                    ClarifyStatus.Answered -> resendFinal(existing)
                    else -> Unit // Pendiente o SendFailed: la usuaria sigue decidiendo.
                }
                publish()
                return@withLock
            }
            val entry = entryFor(request)
            if (entry == null) {
                warn("clarify sin preguntas válidas: respondida cancel-all")
                scope.launch { request.dismiss() }
            } else {
                entries.addLast(entry)
                if (entry.isComplete()) {
                    // Re-entrega con TODO bloqueado ya (raro): falta sólo el frame final.
                    scope.launch { sendFinal(entry) }
                }
            }
            publish()
        }
    }

    // --- respuestas ---

    /**
     * El result final por el wire vivo: `{"answers": {qid: …}}` en lote o
     * `{"answer": …}` en pregunta única. `respond()` devuelve `false` si ya
     * estaba respondida (otro consumidor → cerrar) o si el canal está muerto
     * (no salió → error visual; OJO: `isAnswered` queda `true` en ambos casos,
     * así que el discriminante es el canal — igual que en ApprovalController).
     */
    private suspend fun sendFinal(entry: Entry) {
        val sent =
            entry.live?.let { live ->
                if (entry.batch) {
                    live.answerAll(entry.answers)
                } else {
                    live.answer(entry.answers.values.last())
                }
            } ?: false
        mutex.withLock {
            when {
                sent -> {
                    entry.status = ClarifyStatus.Answered
                    publish()
                    scheduleAutoHideLocked(entry)
                }
                client.isClosed -> {
                    // El socket murió: reintento posible sólo tras re-ligar una
                    // re-entrega — el aviso queda visible mientras tanto.
                    entry.responding = false
                    entry.status = ClarifyStatus.SendFailed
                    publish()
                }
                else -> {
                    // Ya estaba resuelta en otra superficie: cerrar sin ruido.
                    entries.remove(entry)
                    publish()
                }
            }
        }
    }

    /** Reenvía el result ya registrado a una re-entrega (sin tocar la UI). */
    private fun resendFinal(entry: Entry) {
        scope.launch {
            val sent =
                entry.live?.let { live ->
                    if (entry.batch) live.answerAll(entry.answers) else live.answer(entry.answers.values.last())
                } ?: false
            if (!sent) {
                warn("re-respuesta de clarify re-entregada no salió")
            }
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

    /**
     * `ClarifyRequestParams` → [Entry]: lote si trae `questions` con alguna
     * pregunta válida (las vacías se descartan — el `qid` quedará sin
     * respuesta = skip), si no pregunta única; `null` = malformada (se
     * responde cancel-all para no colgar el backend).
     */
    private fun entryFor(request: ClarifyRequest): Entry? {
        val params = request.params
        val singleQuestion = params.question?.takeIf { it.isNotBlank() }
        val batch =
            params.questions.orEmpty().mapIndexedNotNull { index, q ->
                if (q.question.isBlank()) {
                    null
                } else {
                    PendingQuestion(
                        qid = q.qid.ifBlank { "q$index" },
                        text = q.question,
                        options = q.choices.orEmpty().map(::clarifyOptionFor),
                        multiSelect = q.multiSelect && !q.choices.isNullOrEmpty(),
                    )
                }
            }
        return when {
            batch.isNotEmpty() ->
                Entry(
                    key = request.id,
                    live = request,
                    batch = true,
                    questions = batch,
                    answers = LinkedHashMap(params.answers.orEmpty()),
                )
            singleQuestion != null ->
                Entry(
                    key = request.id,
                    live = request,
                    batch = false,
                    questions =
                        listOf(
                            PendingQuestion(
                                qid = SINGLE_QID,
                                text = singleQuestion,
                                options = params.choices.orEmpty().map(::clarifyOptionFor),
                                multiSelect = params.multiSelect == true && !params.choices.isNullOrEmpty(),
                            ),
                        ),
                    answers = LinkedHashMap(),
                )
            else -> null
        }
    }

    private fun publish() {
        if (closed) {
            return
        }
        _card.value = entries.firstOrNull()?.let { head -> cardFor(head) }
    }

    private fun cardFor(entry: Entry): ClarifyCardState {
        val current = entry.questions[entry.index.coerceAtMost(entry.questions.lastIndex)]
        val input =
            when {
                current.options.isEmpty() -> ClarifyInput.FreeText
                current.multiSelect -> ClarifyInput.MultiSelect(current.options)
                else -> ClarifyInput.Choices(current.options)
            }
        return ClarifyCardState(
            key = entry.key,
            question = current.text,
            input = input,
            progress = if (entry.batch) ClarifyProgress(entry.index + 1, entry.questions.size) else null,
            status = entry.status,
        )
    }

    /** §8: el logger viene de fuera — un logger que lanza no puede tumbar el controller. */
    private fun warn(message: String) {
        runCatching { logger(message) }
    }

    /**
     * Una pregunta ya aplanada (única o del lote). [qid] es la clave del mapa
     * `answers` — en pregunta única no viaja al wire, sólo ordena el acceso.
     */
    private data class PendingQuestion(
        val qid: String,
        val text: String,
        val options: List<ClarifyOption>,
        val multiSelect: Boolean,
    )

    /**
     * Una clarify en cola. [key] = `id` de frame `srq-…`: la misma petición
     * re-entregada por `open_requests` casa.
     */
    private class Entry(
        val key: String,
        /** `true` si vino como `questions` — el result final es `{"answers":…}` aunque haya 1 sola. */
        val batch: Boolean,
        val questions: List<PendingQuestion>,
        /** Respuestas por `qid`; las `answers` de una re-entrega (locks del servidor) llegan precargadas. */
        val answers: LinkedHashMap<String, String>,
        /** Petición viva ligada (se re-liga en re-entregas); su `id` es el que referencia `request.cancel`. */
        var live: ClarifyRequest?,
    ) {
        /** Índice de la pregunta visible (primera sin respuesta registrada). */
        var index: Int = questions.indexOfFirst { it.qid !in answers }.let { if (it < 0) questions.size else it }

        /** Tocado sólo bajo el mutex del controller. */
        var status: ClarifyStatus = ClarifyStatus.Pending

        /** Envío del result final en vuelo: doble tap no emite dos respuestas. */
        var responding: Boolean = false

        /** Todas las preguntas tienen respuesta (falta el frame final o ya salió). */
        fun isComplete(): Boolean = index >= questions.size

        /** `true` si la usuaria puede responderla ahora (pendiente o reintento tras fallo). */
        fun isAnswerable(): Boolean =
            !responding && !isComplete() &&
                (status == ClarifyStatus.Pending || status == ClarifyStatus.SendFailed)

        /**
         * Funde las `answers` que una re-entrega trae bloqueadas (otra
         * superficie respondió por `clarify.lock` mientras estábamos caídos):
         * el lote retoma la primera pregunta sin respuesta.
         */
        fun mergeLocked(request: ClarifyRequest) {
            val locked = request.params.answers.orEmpty()
            if (locked.isEmpty()) {
                return
            }
            answers.putAll(locked)
            val firstOpen = questions.indexOfFirst { it.qid !in answers }
            index = if (firstOpen < 0) questions.size else firstOpen
        }

        /** `request.cancel {id}`: el `id` es el de frame (`srq-…`), que es [key]. */
        fun matchesCancel(id: String): Boolean = key == id || live?.id == id
    }

    companion object {
        /**
         * Tiempo que la confirmación ("Respuesta enviada.") queda visible antes
         * de cerrar la tarjeta y pasar a la siguiente clarify encolada.
         */
        const val DEFAULT_ANSWERED_VISIBLE_MS = 1_600L

        /** Clave de `answers` en pregunta única (no viaja al wire). */
        private const val SINGLE_QID = "answer"

        /**
         * Empaqueta una selección múltiple como string JSON de array —
         * `["a","b"]` — la forma que el backend decodifica como lista
         * (`_clean_answer`/`_parse_multi_select_response`).
         */
        internal fun encodeSelections(wireTexts: List<String>): String =
            JsonArray(wireTexts.map(::JsonPrimitive)).toString()
    }
}
