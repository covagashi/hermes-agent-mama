package ai.hermes.mama.gateway

import ai.hermes.mama.contract.RpcMethods
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Canal JSON-RPC 2.0 sobre un [Transport] de texto — ROADMAP §2.2 al completo:
 *
 * - [call]: `{"jsonrpc":"2.0","id":N,"method":…,"params":…}` con ids numéricos
 *   incrementales y correlación estricta; timeout por defecto 120 s →
 *   [JsonRpcTimeoutException]; respuestas `error` → [JsonRpcException] con
 *   `code` y `data` preservados.
 * - [events]: notificaciones `{"method":"event","params":{type, session_id?, seq?, payload}}`
 *   decodificadas a [GatewayEvent]; tipos desconocidos pasan igual (§2.4: tolerante).
 *   `gateway.ready` es un evento normal más.
 * - [serverRequests]: peticiones servidor→cliente (`id` **string**, `method` ≠
 *   `"event"`) con [ServerRequest.respond]/[ServerRequest.fail] idempotentes.
 *   Sin handler que la reclame ni colector en el flujo → respuesta automática
 *   `-32601` para no bloquear al backend.
 * - Heartbeat: `gateway.ping {}` cada 15 s (configurable); si ninguna
 *   **respuesta** correlacionada llega en 45 s, el canal cierra el transport y
 *   llama a `onDead` (máx. 8 pings pendientes). OJO: `gateway.ping` por WS
 *   devuelve `{"ok": true}` (`OkResult`), no `PingResult` — el canal no
 *   decodifica el result, sólo la correlación.
 * - `open_requests` dentro de un result (reconexión) se re-entregan como
 *   [ServerRequest] con `replayed = true` antes de resolver la llamada.
 * - Frames malformados o desconocidos → `logger`, nunca una excepción que mate
 *   el canal. Por §8, los avisos llevan tipos/tamaños/ids numéricos, nunca
 *   contenido del frame ni mensajes de excepción (pueden incrustar el input).
 *
 * Un canal == una generación de conexión: tras `onDead` hay que crear otro (la
 * reconexión con backoff es de B2, `ConnectionManager`). El [scope] gobierna el
 * lector, el heartbeat y los envíos de respuesta; si el scope se cancela el canal
 * muere con él: pendientes fallan y el transport se cierra.
 */
class JsonRpcChannel(
    private val transport: Transport,
    private val scope: CoroutineScope,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val requestTimeout: Duration = DEFAULT_REQUEST_TIMEOUT,
    private val heartbeatInterval: Duration = DEFAULT_HEARTBEAT_INTERVAL,
    private val heartbeatDeadline: Duration = DEFAULT_HEARTBEAT_DEADLINE,
    private val logger: (message: String) -> Unit = {},
    private val onDead: (cause: Throwable) -> Unit = {},
) {
    private val closed = AtomicBoolean(false)
    private val nextId = AtomicLong(0)

    // Un solo envío en vuelo: los frames salen completos y en orden.
    private val sendMutex = Mutex()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()

    // Pings comparten la secuencia numérica de ids (§2.2: "id":N también para
    // gateway.ping). LinkedHashSet sincronizado: a lo sumo 8, se descarta el más viejo.
    private val outstandingPings = Collections.synchronizedSet(LinkedHashSet<Long>())

    private val requestHandlers = CopyOnWriteArrayList<ServerRequestHandler>()

    // Una señal por respuesta correlacionada (pong o result/error). El watchdog
    // espera cada ventana de `heartbeatDeadline`; sin señal → canal muerto.
    private val liveness = Channel<Unit>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val _events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)

    /**
     * Eventos servidor→cliente (`method == "event"`). `replay = 0`: los eventos
     * recibidos sin suscriptores se descartan — B2 debe suscribirse ANTES de que
     * llegue tráfico (p. ej. colectando con `CoroutineStart.UNDISPATCHED`).
     */
    val events: SharedFlow<GatewayEvent> = _events.asSharedFlow()

    private val _serverRequests =
        MutableSharedFlow<ServerRequest>(extraBufferCapacity = SERVER_REQUEST_BUFFER_CAPACITY)

    /**
     * Toda petición servidor→cliente (también las reclamadas por un handler, para
     * observación). Quien la procesa la responde con [ServerRequest.respond]/[ServerRequest.fail].
     * `replay = 0`: igual que [events], suscríbete antes de que llegue tráfico;
     * una petición que nadie recoge recibe `-32601` automático.
     */
    val serverRequests: SharedFlow<ServerRequest> = _serverRequests.asSharedFlow()

    /** `true` tras [close] o muerte por heartbeat/transporte/scope. */
    val isClosed: Boolean
        get() = closed.get()

    private val router = FrameRouter()

    private var readerJob: Job? = null
    private var heartbeatJob: Job? = null
    private var watchdogJob: Job? = null

    init {
        readerJob = startReader()
        heartbeatJob = startHeartbeat()
        watchdogJob = startWatchdog()
        // Si el scope muere por fuera (cancelación) el canal muere con él:
        // pendientes fallan y el transport se cierra, no queda un canal zombi.
        scope.coroutineContext[Job]?.invokeOnCompletion { cause ->
            dead(ChannelClosedException("channel scope ended", cause))
        }
    }

    /**
     * RPC cliente→servidor. Devuelve `result` ([JsonNull] si la respuesta no lo trae).
     *
     * @throws JsonRpcException si la respuesta trae `error` (`code`/`data` preservados)
     * @throws JsonRpcTimeoutException si no hay respuesta en `timeout`
     * @throws ChannelClosedException si el canal está muerto o el envío falla
     */
    suspend fun call(
        method: String,
        params: JsonElement = EMPTY_PARAMS,
        timeout: Duration = requestTimeout,
    ): JsonElement {
        requireOpen()
        val id = nextId.incrementAndGet()
        val deferred = CompletableDeferred<JsonElement>()
        pending[id] = deferred
        // Carrera con dead(): si el canal murió entre requireOpen()/send y el
        // registro, la llamada falla ya en vez de quedar huérfana hasta el timeout.
        abortIfClosed(id)
        sendCallFrame(
            id,
            method,
            requestFrame {
                put("id", id)
                put("method", method)
                put("params", params)
            },
        )
        abortIfClosed(id)
        try {
            return withTimeout(timeout) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            throw JsonRpcTimeoutException(method, timeout, e)
        } finally {
            // La respuesta ya quita la entrada al correlacionar; aquí cubrimos
            // timeout y cancelación externa. Quitar dos veces es un no-op.
            pending.remove(id)
        }
    }

    private fun requireOpen() {
        if (closed.get()) {
            throw ChannelClosedException()
        }
    }

    private fun abortIfClosed(id: Long) {
        if (closed.get()) {
            pending.remove(id)
            throw ChannelClosedException()
        }
    }

    // Los catch (Exception) son deliberados (§2.2): un transporte roto nunca
    // propaga una excepción cruda a la app; el canal muere de forma controlada.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun sendCallFrame(
        id: Long,
        method: String,
        frame: JsonObject,
    ) {
        try {
            sendFrame(frame)
        } catch (e: CancellationException) {
            pending.remove(id)
            throw e
        } catch (e: Exception) {
            pending.remove(id)
            dead(e)
            throw ChannelClosedException("send failed for $method", e)
        }
    }

    /**
     * Registra un handler con prioridad sobre el `-32601` automático; se prueban en
     * orden de registro y gana el primero que devuelva `true`. El [AutoCloseable]
     * devuelto lo quita.
     */
    fun addServerRequestHandler(handler: ServerRequestHandler): AutoCloseable {
        requestHandlers.addIfAbsent(handler)
        return AutoCloseable { requestHandlers.remove(handler) }
    }

    /** Cierra el canal (idempotente): para el heartbeat, falla pendientes y cierra el transport. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        failAllPending(ChannelClosedException())
        cancelJobs()
        // NonCancellable: el socket se cierra aunque el llamador esté cancelado.
        withContext(NonCancellable) {
            closeTransportQuietly()
        }
    }

    // --- recepción ---

    @Suppress("TooGenericExceptionCaught")
    private fun startReader(): Job =
        scope.launch {
            try {
                transport.incoming.collect { text -> router.handleFrame(text) }
                dead(ChannelClosedException("transport incoming flow finished"))
            } catch (e: CancellationException) {
                // El scope murió: el canal muere con él, marcado y cerrado.
                dead(ChannelClosedException("channel scope cancelled", e))
                throw e
            } catch (e: Throwable) {
                // También los Error (p. ej. StackOverflowError por JSON muy
                // anidado) matan el canal de forma controlada, nunca en silencio.
                // onDead recibe la causa real (IOException…); las llamadas
                // pendientes la reciben ya envuelta en ChannelClosedException.
                dead(e)
            }
        }

    /**
     * Semántica de los frames entrantes: respuestas correlacionadas, notificaciones
     * `event`, peticiones servidor→cliente y `open_requests` re-entregadas. Nada de
     * lo que llega por el cable puede tumbar el canal: lo malformado se loguea y
     * el lector sigue. Nada aquí suspende: las emisiones son `tryEmit` para que un
     * colector lento o ausente jamás aparque el lector.
     */
    private inner class FrameRouter {
        @Suppress("TooGenericExceptionCaught")
        fun handleFrame(text: String) {
            val frame = parseFrame(text) ?: return
            try {
                routeFrame(frame)
            } catch (e: Exception) {
                warn("frame ignorado por error interno (${e::class.simpleName})")
            }
        }

        fun parseFrame(text: String): JsonObject? {
            val element =
                try {
                    json.parseToJsonElement(text)
                } catch (e: SerializationException) {
                    // §8: el mensaje de JsonDecodingException incrusta el input;
                    // se loguea tipo + tamaño, nunca el contenido del frame.
                    warn("frame JSON inválido ignorado (${e::class.simpleName}, ${text.length} chars)")
                    null
                }
            if (element != null && element !is JsonObject) {
                warn("frame ignorado (no es objeto JSON)")
            }
            return element as? JsonObject
        }

        fun routeFrame(frame: JsonObject) {
            val method = frame[KEY_METHOD].stringOrNull()
            val id = frame[KEY_ID]
            when {
                // Notificación de evento (§2.4)
                method == EVENT_METHOD -> dispatchEvent(frame[KEY_PARAMS])

                // Petición servidor→cliente: id string + method ≠ "event" (§2.2)
                method != null && id is JsonPrimitive && id.isString ->
                    dispatchServerRequest(
                        id = id.content,
                        method = method,
                        params = frame[KEY_PARAMS] as? JsonObject ?: EMPTY_PARAMS,
                        replayed = false,
                    )

                // Respuesta a una llamada o ping nuestros: id presente, sin method
                method == null && id != null && id !is JsonNull -> dispatchResponse(id, frame)

                else -> warn("frame desconocido ignorado (${frame.keys.size} claves)")
            }
        }

        fun dispatchResponse(
            id: JsonElement,
            frame: JsonObject,
        ) {
            val numericId = (id as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
            if (numericId == null) {
                warn("respuesta con id no numérico ignorada")
            } else if (outstandingPings.remove(numericId)) {
                liveness.trySend(Unit)
            } else {
                completeCall(numericId, frame)
            }
        }

        fun completeCall(
            numericId: Long,
            frame: JsonObject,
        ) {
            val deferred = pending.remove(numericId)
            if (deferred == null) {
                warn("respuesta sin llamada pendiente (id=$numericId)")
                return
            }
            liveness.trySend(Unit)
            val error = frame[KEY_ERROR]
            val result = frame[KEY_RESULT]
            if (error != null && error !is JsonNull) {
                if (result != null && result !is JsonNull) {
                    warn("respuesta con result y error a la vez: gana el error")
                }
                deferred.completeExceptionally(error.toRpcException())
            } else {
                if (result == null) {
                    warn("respuesta sin result ni error (id=$numericId): resuelta a null")
                }
                // Re-entrega de peticiones abiertas (reconexión): viajan en el
                // result de session.resume / session.events.since, ANTES de
                // resolver la llamada.
                deliverOpenRequests(result)
                deferred.complete(result ?: JsonNull)
            }
        }

        fun deliverOpenRequests(result: JsonElement?) {
            val open = (result as? JsonObject)?.get("open_requests") as? JsonArray ?: return
            for (entry in open) {
                val obj = entry as? JsonObject
                val id = obj.stringOrNull("id")
                val method = obj.stringOrNull("method")
                if (obj == null || id == null || method == null) {
                    warn("entrada open_requests malformada ignorada")
                    continue
                }
                dispatchServerRequest(id, method, obj[KEY_PARAMS] as? JsonObject ?: EMPTY_PARAMS, replayed = true)
            }
        }

        fun dispatchEvent(paramsElement: JsonElement?) {
            val params = paramsElement as? JsonObject
            val type = params.stringOrNull("type")
            if (params == null || type == null) {
                warn("evento malformado ignorado")
                return
            }
            val emitted =
                _events.tryEmit(
                    GatewayEvent(
                        type = type,
                        // Los broadcasts del backend traen session_id "" (no lo omiten).
                        sessionId = params.stringOrNull("session_id")?.takeIf { it.isNotEmpty() },
                        seq = params.longOrNull("seq"),
                        payload = params["payload"] ?: JsonNull,
                    ),
                )
            if (!emitted) {
                warn("evento descartado: buffer de eventos lleno")
            }
        }

        fun dispatchServerRequest(
            id: String,
            method: String,
            params: JsonObject,
            replayed: Boolean,
        ) {
            val request =
                ServerRequest(
                    id = id,
                    method = method,
                    params = params,
                    replayed = replayed,
                    sendResponseFrame = ::sendResponseFrame,
                )
            val claimed = requestHandlers.any { handler -> acceptsSafely(handler, request) }
            // OJO: tryEmit devuelve true aunque NO haya suscriptores (replay=0: el
            // valor se descarta). La petición cuenta como entregada sólo si había
            // alguien suscrito al llegar el frame y el buffer la aceptó; si no, el
            // backend no puede quedarse esperando → -32601 automático.
            val hadSubscribers = _serverRequests.subscriptionCount.value > 0
            val delivered = hadSubscribers && _serverRequests.tryEmit(request)
            if (!claimed && !request.isAnswered && !delivered) {
                request.fail(JSON_RPC_METHOD_NOT_FOUND, "no handler for server request: $method")
            }
        }

        @Suppress("TooGenericExceptionCaught")
        fun acceptsSafely(
            handler: ServerRequestHandler,
            request: ServerRequest,
        ): Boolean =
            try {
                handler.accepts(request)
            } catch (e: Exception) {
                warn("handler de server request lanzó (${e::class.simpleName})")
                false
            }

        /**
         * Encola la respuesta de una [ServerRequest]. Devuelve `false` si el canal
         * está muerto: la petición queda respondida localmente pero nada sale por
         * el cable (el backend ya no está escuchando).
         */
        @Suppress("TooGenericExceptionCaught")
        fun sendResponseFrame(frame: JsonObject): Boolean {
            if (closed.get()) {
                return false
            }
            scope.launch {
                try {
                    sendFrame(frame)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    warn("respuesta a petición del servidor no enviada (${e::class.simpleName})")
                    dead(e)
                }
            }
            return true
        }
    }

    // --- heartbeat (§2.2): gateway.ping {} cada 15 s; sin respuesta en 45 s → muerto ---

    private fun startHeartbeat(): Job? =
        if (heartbeatInterval <= Duration.ZERO || heartbeatInterval == Duration.INFINITE) {
            null
        } else {
            scope.launch {
                // Sale solo cuando `closed` se activa (dead/close): así `dead` no
                // necesita cancelar el Job desde dentro (self-cancel).
                while (isActive && !closed.get()) {
                    delay(heartbeatInterval)
                    sendPing()
                }
            }
        }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun sendPing() {
        if (closed.get()) {
            return
        }
        val id = nextId.incrementAndGet()
        synchronized(outstandingPings) {
            outstandingPings.add(id)
            while (outstandingPings.size > MAX_OUTSTANDING_PINGS) {
                outstandingPings.remove(outstandingPings.first())
            }
        }
        try {
            sendFrame(
                requestFrame {
                    put("id", id)
                    put("method", RpcMethods.GATEWAY_PING)
                    put("params", EMPTY_PARAMS)
                },
            )
        } catch (e: CancellationException) {
            outstandingPings.remove(id)
            throw e
        } catch (e: Exception) {
            outstandingPings.remove(id)
            dead(e)
        }
    }

    private fun startWatchdog(): Job? =
        if (
            heartbeatDeadline <= Duration.ZERO ||
            heartbeatInterval <= Duration.ZERO ||
            heartbeatInterval == Duration.INFINITE
        ) {
            // Sin pings no hay watchdog: un canal ocioso no debe morir a los 45 s.
            null
        } else {
            scope.launch {
                try {
                    while (isActive && !closed.get()) {
                        withTimeout(heartbeatDeadline) { liveness.receive() }
                    }
                } catch (e: TimeoutCancellationException) {
                    dead(HeartbeatTimeoutException(heartbeatDeadline, e))
                }
            }
        }

    // --- infra ---

    private suspend fun sendFrame(frame: JsonObject) {
        sendMutex.withLock {
            transport.send(frame.toString())
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun dead(cause: Throwable) {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        warn("canal muerto (${cause::class.simpleName})")
        failAllPending(cause)
        // Los bucles de heartbeat y watchdog salen solos al ver `closed`; el reader
        // puede quedar aparcado en `collect` para siempre, así que sí se cancela.
        // (dead() puede correr dentro del propio reader: cancelar un Job que ya
        // está terminando es inofensivo.)
        readerJob?.cancel()
        // El scope puede estar ya cancelado: el cierre corre con un Job NUEVO
        // (NonCancellable) sobre el mismo dispatcher del canal, no como hijo del
        // scope muerto — así `transport.close()` siempre llega a ejecutarse.
        CoroutineScope(scope.coroutineContext + NonCancellable).launch {
            closeTransportQuietly()
        }
        try {
            onDead(cause)
        } catch (e: Exception) {
            warn("onDead lanzó (${e::class.simpleName})")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun closeTransportQuietly() {
        try {
            transport.close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warn("transport.close() falló (${e::class.simpleName})")
        }
    }

    private fun cancelJobs() {
        readerJob?.cancel()
        heartbeatJob?.cancel()
        watchdogJob?.cancel()
    }

    private fun failAllPending(cause: Throwable) {
        // El KDoc de call() promete excepciones del canal: una causa cruda del
        // transporte (IOException…) llega al await() como ChannelClosedException
        // con la causa real encadenada (la cruda también va a onDead).
        val failure = cause as? ChannelException ?: ChannelClosedException(cause = cause)
        pending.values.forEach { it.completeExceptionally(failure) }
        pending.clear()
        outstandingPings.clear()
    }

    private fun requestFrame(body: JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject {
            put("jsonrpc", JSON_RPC_VERSION)
            body()
        }

    /** §8: el logger viene de fuera — un logger que lanza no puede tumbar el canal. */
    private fun warn(message: String) {
        runCatching { logger(message) }
    }

    companion object {
        val DEFAULT_REQUEST_TIMEOUT: Duration = 120.seconds
        val DEFAULT_HEARTBEAT_INTERVAL: Duration = 15.seconds
        val DEFAULT_HEARTBEAT_DEADLINE: Duration = 45.seconds

        /** JSON-RPC "method not found" — la respuesta automática a peticiones sin dueño. */
        const val JSON_RPC_METHOD_NOT_FOUND = -32601

        private const val JSON_RPC_VERSION = "2.0"
        private const val EVENT_METHOD = "event"
        private const val KEY_ID = "id"
        private const val KEY_METHOD = "method"
        private const val KEY_PARAMS = "params"
        private const val KEY_RESULT = "result"
        private const val KEY_ERROR = "error"
        private const val MAX_OUTSTANDING_PINGS = 8
        private const val EVENT_BUFFER_CAPACITY = 256
        private const val SERVER_REQUEST_BUFFER_CAPACITY = 64
        private val EMPTY_PARAMS = JsonObject(emptyMap())
    }
}

// Helpers JSON puros: null-tolerantes para que el router no convierta un frame
// hostil en una excepción.

private fun JsonElement?.stringOrNull(): String? =
    (this as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content

private fun JsonObject?.stringOrNull(key: String): String? = this?.get(key).stringOrNull()

private fun JsonObject?.longOrNull(key: String): Long? = (this?.get(key) as? JsonPrimitive)?.longOrNull

private fun JsonElement.toRpcException(): JsonRpcException {
    val obj = this as? JsonObject
    return JsonRpcException(
        code = (obj?.get("code") as? JsonPrimitive)?.intOrNull,
        message = obj.stringOrNull("message") ?: "JSON-RPC error",
        data = obj?.get("data"),
    )
}
