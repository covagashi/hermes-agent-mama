package ai.hermes.mama.gateway

import ai.hermes.mama.contract.EventTypes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Crea el [JsonRpcChannel] de una generación (punto de inyección para tests:
 * heartbeat desactivado o acortado para que el tiempo virtual gobierne solo el
 * backoff). En producción se usa el canal estándar de B1.
 */
fun interface ChannelFactory {
    fun create(
        transport: Transport,
        scope: CoroutineScope,
        onDead: (Throwable) -> Unit,
    ): JsonRpcChannel
}

/**
 * Mantiene viva la conexión WS con el gateway (ROADMAP §2.1/§2.2, tarea B2).
 *
 * - [connect] arranca el bucle: `onBeforeConnect` (B3 minteará aquí el ticket
 *   de un solo uso, llamado **en cada intento**) → [TransportFactory] →
 *   [JsonRpcChannel] → espera `gateway.ready`.
 * - [state]: `Disconnected → Connecting → Connected`; tras una caída,
 *   `Reconnecting(attempt, retryIn)` con backoff exponencial + jitter
 *   (§2.2: 1 s, 2 s, 4 s… máx 30 s, ±20 %). Sin límite de reintentos: la app
 *   "vuelve sola" de un corte largo.
 * - Al reconectar (2ª generación `Connected` en adelante) emite
 *   [ConnectionEvent.Reconnected] con el `replay_epoch` de su `gateway.ready`.
 * - [disconnect] es el cierre explícito: para el bucle y cierra el canal —
 *   nunca provoca reconexión.
 * - Invariante: **como mucho un socket abierto**. Cada generación cierra su
 *   transport antes de que la siguiente iteración abra el suyo (misma
 *   corrutina, orden estricto).
 *
 * §8: los logs llevan números de intento y tipos de excepción — nunca URLs
 * (ticket) ni contenido de frames.
 */
class ConnectionManager(
    private val scope: CoroutineScope,
    private val transportFactory: TransportFactory,
    private val onBeforeConnect: suspend () -> ConnectParams,
    private val config: ReconnectConfig = ReconnectConfig(),
    private val logger: (String) -> Unit = {},
    private val channelFactory: ChannelFactory =
        ChannelFactory { transport, channelScope, onDead ->
            JsonRpcChannel(transport = transport, scope = channelScope, logger = logger, onDead = onDead)
        },
) {
    /**
     * Atajo de producción: [WebSocketTransport] sobre un [OkHttpClient] ya
     * configurado (en B3, el cliente que lleva el `CookieJar` de sesión).
     */
    constructor(
        scope: CoroutineScope,
        client: OkHttpClient,
        onBeforeConnect: suspend () -> ConnectParams,
        config: ReconnectConfig = ReconnectConfig(),
        logger: (String) -> Unit = {},
    ) : this(
        scope = scope,
        transportFactory = WebSocketTransport.factory(client, logger),
        onBeforeConnect = onBeforeConnect,
        config = config,
        logger = logger,
    )

    private val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)

    /** Estado de la conexión; `Connected` se marca al recibir `gateway.ready` (§2.4). */
    val state: StateFlow<ConnectionState> = mutableState.asStateFlow()

    private val mutableEvents = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)

    /** Señales de una sola vez (F3 re-registra el controlador con [ConnectionEvent.Reconnected]). */
    val events: SharedFlow<ConnectionEvent> = mutableEvents.asSharedFlow()

    private val started = AtomicBoolean(false)
    private val loopJob = AtomicReference<Job?>(null)

    @Volatile
    private var currentChannel: JsonRpcChannel? = null

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Arranca el bucle de conexión (idempotente). El re-check de [started] tras
     * publicar el job cubre la carrera `connect()`/`disconnect()`: si disconnect
     * ganó entre el CAS y el [AtomicReference.set], el job se cancela aquí y no
     * queda un bucle zombi.
     */
    fun connect() {
        if (!started.compareAndSet(false, true)) {
            return
        }
        val job = scope.launch { runLoop() }
        loopJob.set(job)
        if (!started.get()) {
            job.cancel()
        }
    }

    /**
     * Cierre explícito e idempotente: para el bucle y cierra el canal actual.
     * Tras [disconnect] no hay reconexión; [connect] puede arrancar de nuevo.
     *
     * La instantánea de [loopJob] se toma ANTES del CAS de [started]: un
     * [connect] posterior sólo puede ganar el CAS una vez `started` vuelve a
     * `false`, así que cualquier job instalado tras la instantánea pertenece a
     * una generación nueva y no se toca (se compara con `compareAndSet`). Si el
     * job aún no está instalado (hueco CAS→set de [connect]), el re-check de
     * `started` en [connect] lo cancela él mismo.
     */
    suspend fun disconnect() {
        // Instantáneas ANTES del CAS: cualquier job/canal instalado después
        // pertenece a una generación nueva de connect() y no se toca.
        val job = loopJob.get()
        val channel = currentChannel
        if (!started.compareAndSet(true, false)) {
            mutableState.value = ConnectionState.Disconnected
            return
        }
        // El finally del bucle cierra el canal de la generación en curso.
        if (job != null && loopJob.compareAndSet(job, null)) {
            job.cancelAndJoin()
        }
        if (currentChannel === channel) {
            currentChannel = null
        }
        closeQuietly { channel?.close() }
        mutableState.value = ConnectionState.Disconnected
    }

    /**
     * [retryStreak] cuenta los intentos de la racha actual que terminaron sin
     * `Connected`: cada fallo de conexión suma 1 y la muerte de una generación
     * abre racha nueva en 1 (así la secuencia tras caída es `Reconnecting(1, 1 s),
     * Reconnecting(2, 2 s)…`, igual que tras fallos de conexión).
     */
    private suspend fun runLoop() {
        var retryStreak = 0
        var connections = 0
        var lastError: Throwable? = null
        while (currentCoroutineContext().isActive) {
            if (retryStreak == 0 && connections == 0) {
                mutableState.value = ConnectionState.Connecting
            } else {
                val attempt = retryStreak.coerceAtLeast(1)
                val wait = config.backoff(attempt)
                mutableState.value = ConnectionState.Reconnecting(attempt, wait, lastError)
                delay(wait)
            }
            when (val outcome = attemptConnect()) {
                is AttemptOutcome.Fatal -> {
                    // Error terminal (p. ej. credenciales rechazadas): sin reintentos —
                    // la app va a la pantalla de conexión y connect() puede reintentar.
                    mutableState.value = ConnectionState.Failed(outcome.cause)
                    started.set(false)
                    warn("connection attempt is fatal (${outcome.cause::class.simpleName}); stopping")
                    return
                }

                is AttemptOutcome.Failure -> {
                    retryStreak++
                    lastError = outcome.cause
                }

                is AttemptOutcome.Success -> {
                    retryStreak = 0
                    connections++
                    lastError = runGeneration(outcome.generation, connections)
                    retryStreak = 1 // la generación muerta es el fallo nº 1 de la racha
                }
            }
        }
    }

    /** Un intento acotado por [ReconnectConfig.attemptTimeout]; la cancelación externa se propaga. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun attemptConnect(): AttemptOutcome =
        try {
            AttemptOutcome.Success(withTimeout(config.attemptTimeout) { openGeneration() })
        } catch (e: ConnectionFatalException) {
            // Terminal (p. ej. ticket rechazado por credenciales): no reintentar.
            warn("connection attempt is fatal (${e::class.simpleName})")
            AttemptOutcome.Fatal(e)
        } catch (e: TimeoutCancellationException) {
            // Timeout del intento (propio o el readyTimeout interior): fallo reintentable.
            warn("connection attempt timed out")
            AttemptOutcome.Failure(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Incluye Error: el bucle de reconexión no muere en silencio (§2.2).
            warn("connection attempt failed (${e::class.simpleName})")
            AttemptOutcome.Failure(e)
        }

    /**
     * Un intento completo: ticket nuevo (`onBeforeConnect`), socket, canal y
     * espera de `gateway.ready` (máx. [ReconnectConfig.readyTimeout]). El
     * `replay_epoch` se lee del frame crudo con [TapTransport]: no depende de
     * suscriptores en `channel.events` (replay=0), así no puede perderse.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun openGeneration(): Generation {
        val params = onBeforeConnect()
        val transport = transportFactory.connect(params)
        val deadSignal = CompletableDeferred<Throwable>()
        val ready = CompletableDeferred<String?>()
        val tapped =
            TapTransport(
                delegate = transport,
                onFrame = { text -> onFrame(text, ready) },
                onEnd = { cause -> deadSignal.complete(cause) },
            )
        // Scope HIJO por generación (revisión #10 vuelta 2): JsonRpcChannel
        // registra un invokeOnCompletion sobre el Job de su scope y descarta el
        // DisposableHandle — sobre el scope del manager cada reconexión
        // retendría un canal entero hasta que la app muera. Aquí el handler cae
        // en el Job hijo: al cancelarlo en el finally completa, suelta el
        // handler y el canal es GC-able (además actúa de kill-switch para los
        // jobs internos del canal).
        val generationScope =
            CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext[Job]))
        val channel =
            try {
                channelFactory.create(tapped, generationScope) { cause ->
                    deadSignal.complete(cause)
                    ready.completeExceptionally(
                        ChannelClosedException("channel died before gateway.ready", cause),
                    )
                }
            } catch (e: Throwable) {
                generationScope.cancel()
                closeQuietly { transport.close() }
                throw e
            }
        return try {
            Generation(
                channel = channel,
                transport = transport,
                scope = generationScope,
                replayEpoch = withTimeout(config.readyTimeout) { ready.await() },
                deadSignal = deadSignal,
            )
        } catch (e: Throwable) {
            generationScope.cancel()
            closeQuietly { channel.close() }
            closeQuietly { transport.close() }
            throw e
        }
    }

    /**
     * Generación viva: espera a su muerte y la cierra al terminar (invariante
     * "un solo socket"). Devuelve la causa de la muerte para `lastError`.
     */
    private suspend fun runGeneration(
        generation: Generation,
        connections: Int,
    ): Throwable? {
        // El canal se publica ANTES de comprobar cancelación: si disconnect()
        // ganó la carrera tras attemptConnect, el finally cierra la generación —
        // nunca un socket huérfano sin dueño (invariante "un solo socket").
        currentChannel = generation.channel
        var death: Throwable? = null
        try {
            currentCoroutineContext().ensureActive()
            mutableState.value = ConnectionState.Connected(generation.channel, generation.replayEpoch)
            if (connections > 1) {
                // Reconnected(replayEpoch) — §2.4: la app re-sincroniza sesiones/controlador.
                if (!mutableEvents.tryEmit(ConnectionEvent.Reconnected(generation.replayEpoch))) {
                    warn("events buffer full: Reconnected dropped")
                }
            }
            val cause = generation.awaitDead()
            death = cause
            warn("channel died (${cause::class.simpleName}); reconnecting")
        } finally {
            if (currentChannel === generation.channel) {
                currentChannel = null
            }
            // Kill-switch del scope hijo: mata reader/heartbeat/watchdog y
            // completa el Job — el invokeOnCompletion del canal se suelta y la
            // generación deja de ser hija del scope del manager.
            generation.scope.cancel()
            closeQuietly { generation.channel.close() }
            // Y se ESPERA el drenaje real del socket viejo (closeGrace acotado
            // en WebSocketTransport.close): sin esta espera la TCP muerta
            // convivía ~4 s con la siguiente generación ya abierta (§2.2).
            closeQuietly { generation.transport.close() }
        }
        return death
    }

    /** Cierre de limpieza: no se aborta aunque el intento esté cancelado. */
    private suspend fun closeQuietly(close: suspend () -> Unit) {
        withContext(NonCancellable) { runCatching { close() } }
    }

    private fun onFrame(
        text: String,
        ready: CompletableDeferred<String?>,
    ) {
        if (ready.isCompleted || EventTypes.GATEWAY_READY !in text) {
            return
        }
        val params = readyParams(text) ?: return
        ready.complete((params[KEY_PAYLOAD] as? JsonObject)?.replayEpoch())
    }

    /** `params` del frame sólo si es la notificación `event` con `type == gateway.ready`. */
    private fun readyParams(text: String): JsonObject? {
        val frame = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
        val params = frame?.get(KEY_PARAMS) as? JsonObject
        val isReady =
            (frame?.get(KEY_METHOD) as? JsonPrimitive)?.contentOrNull == EVENT_METHOD &&
                (params?.get(KEY_TYPE) as? JsonPrimitive)?.contentOrNull == EventTypes.GATEWAY_READY
        return params.takeIf { isReady }
    }

    private fun JsonObject.replayEpoch(): String? = (this[KEY_REPLAY_EPOCH] as? JsonPrimitive)?.contentOrNull

    private fun warn(message: String) = runCatching { logger(message) }

    private sealed interface AttemptOutcome {
        data class Success(
            val generation: Generation,
        ) : AttemptOutcome

        data class Failure(
            val cause: Throwable,
        ) : AttemptOutcome

        data class Fatal(
            val cause: Throwable,
        ) : AttemptOutcome
    }

    private class Generation(
        val channel: JsonRpcChannel,
        val transport: Transport,
        val scope: CoroutineScope,
        val replayEpoch: String?,
        private val deadSignal: CompletableDeferred<Throwable>,
    ) {
        suspend fun awaitDead(): Throwable = deadSignal.await()
    }

    /**
     * Lee los frames entrantes antes que el canal: `gateway.ready` no puede
     * perderse. Además reporta el fin del flujo con [onEnd]: cubre la muerte
     * por `close()` externo del canal (el lector se cancela) y cualquier cierre
     * que no pase por `onDead` — sin ella `awaitDead` quedaría colgado y la
     * app mostraría `Connected` eterno sobre un socket muerto.
     */
    private class TapTransport(
        private val delegate: Transport,
        private val onFrame: (String) -> Unit,
        private val onEnd: (Throwable) -> Unit,
    ) : Transport {
        override val incoming: Flow<String> =
            delegate.incoming
                .onEach { text -> runCatching { onFrame(text) } }
                .onCompletion { cause -> runCatching { onEnd(endCause(cause)) } }

        override suspend fun send(text: String) = delegate.send(text)

        override suspend fun close() = delegate.close()

        private companion object {
            fun endCause(cause: Throwable?): Throwable =
                when {
                    cause == null -> ChannelClosedException("transport incoming finished")
                    cause is CancellationException ->
                        ChannelClosedException("channel closed", cause)
                    else -> cause
                }
        }
    }

    private companion object {
        const val EVENT_METHOD = "event"
        const val KEY_METHOD = "method"
        const val KEY_PARAMS = "params"
        const val KEY_TYPE = "type"
        const val KEY_PAYLOAD = "payload"
        const val KEY_REPLAY_EPOCH = "replay_epoch"
        const val EVENT_BUFFER_CAPACITY = 16
    }
}
