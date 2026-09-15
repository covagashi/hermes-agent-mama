package ai.hermes.mama.testing

import ai.hermes.mama.gateway.Transport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * `Transport` (B1) real sobre OkHttp para los tests del [FakeGateway]: el canal
 * cliente habla con el servidor de verdad por un socket local — mismo camino
 * que `WsTransport` de B2 en la app.
 *
 * ```kotlin
 * val transport = OkHttpWsTransport.connect(gateway.wsUrl)
 * val channel = JsonRpcChannel(transport, scope)
 * ```
 */
class OkHttpWsTransport private constructor(
    private val client: OkHttpClient,
) : WebSocketListener(),
    Transport {
    private val incomingChannel = Channel<String>(capacity = Channel.UNLIMITED)
    private val opened = CompletableDeferred<Unit>()
    private val failed = CompletableDeferred<Throwable>()
    private var socket: WebSocket? = null

    override val incoming: Flow<String> = incomingChannel.receiveAsFlow()

    override suspend fun send(text: String) {
        val ws = socket ?: throw IOException("websocket no conectado")
        if (!ws.send(text)) {
            throw IOException("websocket send() rechazó el frame (${text.length} chars)")
        }
    }

    override suspend fun close() {
        socket?.close(NORMAL_CLOSURE_STATUS, "test done")
        incomingChannel.close()
    }

    // --- WebSocketListener ---

    override fun onOpen(
        webSocket: WebSocket,
        response: Response,
    ) {
        opened.complete(Unit)
    }

    override fun onMessage(
        webSocket: WebSocket,
        text: String,
    ) {
        incomingChannel.trySend(text)
    }

    override fun onClosing(
        webSocket: WebSocket,
        code: Int,
        reason: String,
    ) {
        webSocket.close(NORMAL_CLOSURE_STATUS, null)
    }

    override fun onClosed(
        webSocket: WebSocket,
        code: Int,
        reason: String,
    ) {
        incomingChannel.close()
    }

    override fun onFailure(
        webSocket: WebSocket,
        t: Throwable,
        response: Response?,
    ) {
        val error =
            if (response != null) {
                IOException("websocket upgrade falló: HTTP ${response.code}")
            } else {
                IOException("websocket failure", t)
            }
        failed.complete(error)
        if (!opened.isCompleted) {
            opened.completeExceptionally(error)
        }
        incomingChannel.close(error)
    }

    companion object {
        const val NORMAL_CLOSURE_STATUS = 1000
        private const val CONNECT_TIMEOUT_S = 10L

        /**
         * Abre el WS y espera al `onOpen`; si el servidor rechaza el upgrade
         * (ticket inválido → 401) lanza [IOException] con el código HTTP.
         */
        suspend fun connect(
            url: String,
            client: OkHttpClient = OkHttpClient(),
        ): OkHttpWsTransport {
            val transport = OkHttpWsTransport(client)
            val ws =
                client.newWebSocket(
                    Request.Builder().url(url).build(),
                    transport,
                )
            transport.socket = ws
            transport.opened.await()
            return transport
        }

        /** Cliente con timeouts cortos para tests (los defaults de OkHttp son 10 s). */
        fun defaultClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
                .readTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
                .build()
    }
}
