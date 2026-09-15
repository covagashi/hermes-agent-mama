package ai.hermes.mama.gateway

import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Servidor WS de pruebas sobre `MockWebServer` (tarea B2): acepta upgrades en
 * `/api/ws`, graba los sockets del lado servidor, lo que el cliente envía y el
 * `?ticket=` de cada petición. Sin datos reales (§8): sólo localhost.
 *
 * - [onSocketOpen]: qué enviar al aceptar cada socket (p. ej. `gateway.ready`).
 * - [onClientMessage]: reaccionar a frames del cliente (p. ej. auto-pong).
 * - [acceptUpgrades] = false simula "servidor caído" (HTTP 503 al handshake).
 */
class WsTestServer : Closeable {
    val server = MockWebServer()
    val client = OkHttpClient()

    /** Sockets aceptados, en orden de llegada. */
    val serverSockets = CopyOnWriteArrayList<WebSocket>()

    /** Frames recibidos del cliente, en orden. */
    val received = CopyOnWriteArrayList<String>()

    /** Query param `ticket` de cada petición HTTP (null si no venía). */
    val tickets = CopyOnWriteArrayList<String?>()

    /** `false` → el dispatcher responde 503 al upgrade (simula red/servidor caído). */
    @Volatile
    var acceptUpgrades: Boolean = true

    /** Hook por socket aceptado (típico: `ws.send(readyFrame)`). */
    var onSocketOpen: (WebSocket) -> Unit = {}

    /** Hook por frame entrante del cliente (típico: responder `gateway.ping`). */
    var onClientMessage: (WebSocket, String) -> Unit = { _, _ -> }

    private val opens = Channel<WebSocket>(Channel.UNLIMITED)
    private val inbox = Channel<String>(Channel.UNLIMITED)

    private val listener =
        object : WebSocketListener() {
            override fun onOpen(
                webSocket: WebSocket,
                response: Response,
            ) {
                serverSockets += webSocket
                opens.trySend(webSocket)
                onSocketOpen(webSocket)
            }

            override fun onMessage(
                webSocket: WebSocket,
                text: String,
            ) {
                received += text
                inbox.trySend(text)
                onClientMessage(webSocket, text)
            }
        }

    fun start() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    tickets += request.requestUrl?.queryParameter("ticket")
                    val isUpgrade = request.path?.startsWith("/api/ws") == true
                    return if (acceptUpgrades && isUpgrade) {
                        MockResponse().withWebSocketUpgrade(listener)
                    } else {
                        MockResponse().setResponseCode(HTTP_UNAVAILABLE)
                    }
                }
            }
        server.start()
    }

    /** URL `ws://` para `ConnectParams`/`WebSocketTransport`. */
    fun wsUrl(
        path: String,
        query: String? = null,
    ): String {
        val builder = server.url(path).newBuilder()
        query?.let { builder.encodedQuery(it) }
        return builder
            .build()
            .toString()
            .replace(SCHEME_HTTP, SCHEME_WS)
    }

    /** Suspende hasta que el servidor acepta el siguiente socket. */
    suspend fun awaitSocket(): WebSocket = opens.receive()

    /** Suspende hasta recibir el siguiente frame del cliente. */
    suspend fun awaitMessage(): String = inbox.receive()

    override fun close() {
        serverSockets.forEach { it.close(NORMAL_CLOSURE_CODE, "test done") }
        server.shutdown()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private companion object {
        const val HTTP_UNAVAILABLE = 503
        const val NORMAL_CLOSURE_CODE = 1000
        const val SCHEME_HTTP = "http://"
        const val SCHEME_WS = "ws://"
    }
}
