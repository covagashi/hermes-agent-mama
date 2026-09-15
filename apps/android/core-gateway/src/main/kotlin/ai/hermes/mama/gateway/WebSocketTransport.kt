package ai.hermes.mama.gateway

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.utf8Size
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [Transport] sobre el WebSocket de OkHttp (ROADMAP §2.2, tarea B2).
 *
 * - Un frame JSON de texto por mensaje, UTF-8 estricto (emoji y plano astral).
 * - [send] cumple el contrato duro de [Transport]: el `Boolean` de
 *   `OkHttp WebSocket.send` se mapea a excepción (`false` →
 *   [ChannelClosedException]) para que un frame rechazado nunca deje una
 *   `call()` colgada hasta el timeout. No suspende indefinidamente: el
 *   handshake lo acotan en tiempo real los timeouts de OkHttp
 *   (`connectTimeout`/`readTimeout`) y, en el manager, `attemptTimeout`;
 *   la cola de envío por [maxQueueBytes] (un peer que no drena hace fallar
 *   el envío en vez de inflar el buffer interno de OkHttp, 16 MiB).
 * - [incoming] se completa en `onClosed` y falla en `onFailure`: al terminar
 *   el flujo el [JsonRpcChannel] se da por muerto y el [ConnectionManager]
 *   dispara la reconexión.
 * - El handshake arranca en el constructor (`newWebSocket`); [awaitOpen]
 *   suspende hasta `onOpen`/`onFailure` y aborta el socket si el llamador
 *   se cancela.
 *
 * §8: jamás se loguea la URL (lleva el ticket de un solo uso) ni contenido de
 * frames — sólo tipos de evento y tamaños. Por eso `url` no es propiedad
 * pública y el `IllegalArgumentException` de `Request.Builder` (que incrusta la
 * URL completa en su `message`) se re-lanza sanitizado.
 */
class WebSocketTransport(
    url: String,
    cookieJar: CookieJar = CookieJar.NO_COOKIES,
    okHttpClient: OkHttpClient? = null,
    private val headers: Map<String, String> = emptyMap(),
    private val closeGrace: Duration = DEFAULT_CLOSE_GRACE,
    private val maxQueueBytes: Long = DEFAULT_MAX_QUEUE_BYTES,
    private val logger: (String) -> Unit = {},
) : Transport {
    private val client = okHttpClient ?: defaultClient(cookieJar)

    /** El cliente es nuestro si no nos pasaron uno: [close] lo apaga (§8: sin fugas). */
    private val ownsClient = okHttpClient == null

    private val openSignal = CompletableDeferred<WebSocket>()
    private val closedSignal = CompletableDeferred<Unit>()
    private val opened = AtomicBoolean(false)
    private val closeRequested = AtomicBoolean(false)

    private val incomingFrames = Channel<String>(Channel.UNLIMITED)

    /** Frames entrantes; completa al cierre limpio, falla con la causa real si el socket muere. */
    override val incoming: Flow<String> = incomingFrames.receiveAsFlow()

    private val socket: WebSocket

    init {
        require(!(okHttpClient != null && cookieJar !== CookieJar.NO_COOKIES)) {
            "cookieJar se ignora cuando pasas okHttpClient: mete el jar en ese cliente"
        }
        val request =
            try {
                Request
                    .Builder()
                    .url(url)
                    .apply { headers.forEach { (name, value) -> header(name, value) } }
                    .build()
            } catch (e: IllegalArgumentException) {
                // §8: el message de OkHttp incrusta la URL/headers completos (ticket);
                // se re-lanza sanitizado (sólo el tipo de la causa, nunca su mensaje).
                throw IllegalArgumentException("invalid websocket url or headers (${e::class.simpleName})")
            }
        socket = client.newWebSocket(request, Listener())
    }

    /** `true` con el socket abierto y sin cierre iniciado ni fallo registrado. */
    val isOpen: Boolean
        get() = opened.get() && !closeRequested.get() && !closedSignal.isCompleted

    /**
     * Suspende hasta que el handshake termina (`onOpen`/`onFailure`). La espera
     * la acota OkHttp en tiempo real (`connectTimeout` del upgrade): aquí no hay
     * timeout propio para que un scheduler de test pueda gobernar la espera.
     * Si el llamador se cancela (timeout del intento, `disconnect`), el socket
     * en vuelo se aborta — nunca queda un handshake huérfano.
     */
    suspend fun awaitOpen(): WebSocket =
        try {
            openSignal.await()
        } catch (e: CancellationException) {
            socket.cancel()
            throw e
        }

    /**
     * Envía un frame de texto. Lanza [ChannelClosedException] si el socket no
     * lo acepta (cerrándose o cola llena) — contrato [Transport]: el `false` de
     * OkHttp nunca es un drop silencioso.
     */
    override suspend fun send(text: String) {
        val ws = awaitOpen()
        if (closeRequested.get()) {
            throw ChannelClosedException("send sobre un transport cerrado")
        }
        val pendingBytes = ws.queueSize() + text.utf8Size()
        if (pendingBytes > maxQueueBytes) {
            // El peer no drena: el canal muere y la reconexión decide (§2.2).
            throw ChannelClosedException("websocket send queue full ($pendingBytes > $maxQueueBytes bytes)")
        }
        if (!ws.send(text)) {
            throw ChannelClosedException("websocket rejected the frame (send() = false)")
        }
    }

    /**
     * Cierre idempotente: `close(1000)` si el socket abrió, `cancel()` si el
     * handshake seguía en vuelo. Espera acotada al `onClosed` real y corte
     * brusco si el peer no responde — el [ConnectionManager] necesita el
     * socket viejo muerto antes de abrir el siguiente ("nunca dos sockets").
     */
    override suspend fun close() {
        if (!closeRequested.compareAndSet(false, true)) {
            return
        }
        try {
            incomingFrames.close()
            // Quien espere un handshake en vuelo sale ya, aunque lo de abajo se interrumpa.
            openSignal.completeExceptionally(ChannelClosedException("transport closed"))
            if (opened.get()) {
                socket.close(NORMAL_CLOSURE_CODE, CLOSE_REASON)
            } else {
                socket.cancel()
            }
            if (withTimeoutOrNull(closeGrace) { closedSignal.await() } == null) {
                socket.cancel()
            }
        } finally {
            if (ownsClient) {
                // El cliente lo creó este transport: apagarlo con él (precedente WsTestServer).
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
    }

    private fun finishIncoming(cause: Throwable?) {
        closedSignal.complete(Unit)
        if (cause == null) {
            incomingFrames.close()
        } else {
            incomingFrames.close(cause)
        }
    }

    private fun warn(message: String) = runCatching { logger(message) }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            opened.set(true)
            openSignal.complete(webSocket)
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            incomingFrames.trySend(text)
        }

        override fun onMessage(
            webSocket: WebSocket,
            bytes: ByteString,
        ) {
            // §2.2 sólo habla de frames de texto: un binario se tolera y se loguea.
            warn("binary websocket frame ignored (${bytes.size} bytes)")
        }

        override fun onClosing(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            // Ack del cierre del peer: OkHttp completa el handshake y dispara onClosed.
            webSocket.close(code, null)
        }

        override fun onClosed(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            finishIncoming(null)
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
        ) {
            warn("websocket failed (${t::class.simpleName})")
            // Si aún no abrió, awaitOpen()/connect() fallan con la causa real.
            openSignal.completeExceptionally(t)
            finishIncoming(t)
        }
    }

    companion object {
        /** Espera al `onClosed` del peer antes de abortar el socket. */
        val DEFAULT_CLOSE_GRACE: Duration = 5.seconds

        /** Techo propio de la cola de envío; la mitad del hard-cap de OkHttp (16 MiB). */
        const val DEFAULT_MAX_QUEUE_BYTES: Long = 8L * 1024 * 1024

        /** Ping WS a nivel TCP/TLS para mantener la ruta (el heartbeat útil es `gateway.ping`, §2.2). */
        private const val PING_INTERVAL_SECONDS = 20L

        private const val CONNECT_TIMEOUT_SECONDS = 10L
        private const val NORMAL_CLOSURE_CODE = 1000
        private const val CLOSE_REASON = "client closing"

        /**
         * Cliente por defecto: cookies del jar, timeout de handshake y ping WS.
         * `followSslRedirects(false)` (§8): la URL lleva el ticket de un solo
         * uso — no reemitir el upgrade (ni sus headers) a otro host.
         */
        fun defaultClient(cookieJar: CookieJar): OkHttpClient =
            OkHttpClient
                .Builder()
                .cookieJar(cookieJar)
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
                .followSslRedirects(false)
                .build()

        /**
         * [TransportFactory] real: abre un [WebSocketTransport] y espera `onOpen`.
         * Si el handshake falla o la corrutina se cancela, el transport a medio
         * abrir se cierra — nunca queda un socket abierto sin dueño.
         *
         * OJO (B3): el [client] debe traer `connectTimeout` y `pingInterval`
         * configurados — ver [defaultClient]; un cliente pelado no acota el
         * handshake ni mantiene la ruta TCP/TLS dormida.
         */
        @Suppress("TooGenericExceptionCaught")
        fun factory(
            client: OkHttpClient,
            logger: (String) -> Unit = {},
        ): TransportFactory =
            TransportFactory { params ->
                val transport =
                    WebSocketTransport(
                        url = params.url,
                        okHttpClient = client,
                        headers = params.headers,
                        logger = logger,
                    )
                try {
                    transport.awaitOpen()
                } catch (e: Throwable) {
                    // Incluye la cancelación del intento: el socket se cierra y la causa sube.
                    transport.close()
                    throw e
                }
                transport
            }
    }
}
