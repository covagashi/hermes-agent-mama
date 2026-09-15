package ai.hermes.mama.gateway

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.WebSocket
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Contrato de [WebSocketTransport] (ROADMAP §2.2 y tarea B2) contra un
 * `MockWebServer` real en localhost: sockets de verdad, nada falseado.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Timeout(30)
class WebSocketTransportTest {
    private val servers = mutableListOf<WsTestServer>()

    private fun newServer(): WsTestServer =
        WsTestServer()
            .also {
                it.start()
                servers += it
            }

    @AfterEach
    fun tearDown() {
        servers.forEach { it.close() }
        servers.clear()
    }

    @Test
    fun `conexion el socket abre y los frames viajan en ambos sentidos en UTF-8 estricto`() =
        runTest {
            val srv = newServer()
            val transport = WebSocketTransport(srv.wsUrl("/api/ws", "ticket=t-1"), okHttpClient = srv.client)
            transport.awaitOpen()
            assertTrue(transport.isOpen)

            // Cliente → servidor: el frame llega íntegro (emoji y plano astral).
            val payload = """{"method":"prompt.submit","params":{"text":"hola 👩‍👧‍👦 𝄞 ☕"}}"""
            transport.send(payload)
            assertEquals(payload, srv.awaitMessage())

            // Servidor → cliente: `incoming` emite el frame tal cual.
            val frame = """{"method":"event","params":{"type":"notice","payload":{"m":"hola 👋🏽"}}}"""
            srv.awaitSocket().send(frame)
            assertEquals(frame, transport.incoming.first())
        }

    @Test
    fun `send espera a onOpen internamente sin awaitOpen explicito`() =
        runTest {
            val srv = newServer()
            val transport = WebSocketTransport(srv.wsUrl("/api/ws"), okHttpClient = srv.client)
            // Sin awaitOpen: send suspende hasta que el socket abre y luego emite.
            transport.send("hola")
            assertEquals("hola", srv.awaitMessage())
        }

    @Test
    fun `send devuelve false con el socket cerrado y se mapea a ChannelClosedException`() =
        runTest {
            val srv = newServer()
            val transport = WebSocketTransport(srv.wsUrl("/api/ws"), okHttpClient = srv.client)
            transport.awaitOpen()

            // El servidor cierra: onClosed completa `incoming` (el canal moriría).
            val drained = async { runCatching { transport.incoming.collect {} } }
            srv.awaitSocket().close(NORMAL_CLOSURE, "bye")
            drained.await()

            assertFalse(transport.isOpen)
            // OkHttp WebSocket.send() devuelve false aquí → excepción, nunca drop silencioso.
            val error = assertNotNull(runCatching { transport.send("x") }.exceptionOrNull())
            assertIs<ChannelClosedException>(error)
        }

    @Test
    fun `send con la cola acotada llena lanza en vez de inflar el buffer`() =
        runTest {
            val srv = newServer()
            val transport =
                WebSocketTransport(
                    srv.wsUrl("/api/ws"),
                    okHttpClient = srv.client,
                    maxQueueBytes = 1,
                )
            transport.awaitOpen()
            val error = assertNotNull(runCatching { transport.send("ab") }.exceptionOrNull())
            assertIs<ChannelClosedException>(error)
        }

    @Test
    fun `incoming termina cuando el socket muere para que el canal se de por muerto`() =
        runTest {
            val srv = newServer()
            val transport = WebSocketTransport(srv.wsUrl("/api/ws"), okHttpClient = srv.client)
            transport.awaitOpen()

            val collected = async { runCatching { transport.incoming.collect {} } }
            srv.server.shutdown() // corte TCP sin handshake limpio
            collected.await() // el flujo termina (completa o falla): el canal muere
            assertFalse(transport.isOpen)
        }

    @Test
    fun `handshake rechazado con HTTP 503 hace fallar awaitOpen con la causa real`() =
        runTest {
            val srv = newServer()
            srv.acceptUpgrades = false
            val transport = WebSocketTransport(srv.wsUrl("/api/ws"), okHttpClient = srv.client)

            val error = assertNotNull(runCatching { transport.awaitOpen() }.exceptionOrNull())
            assertIs<IOException>(error)
            assertFalse(transport.isOpen)
        }

    @Test
    fun `awaitOpen cancelado aborta el handshake en vuelo`() =
        runTest {
            val srv = newServer()
            srv.hangUpgrade = true // el upgrade nunca completa: la espera sigue viva al cancelar
            val transport = WebSocketTransport(srv.wsUrl("/api/ws"), okHttpClient = srv.client)

            val opened = CompletableDeferred<Result<WebSocket>>()
            val openJob =
                backgroundScope.launch {
                    opened.complete(runCatching { transport.awaitOpen() })
                }
            val drained = async { runCatching { transport.incoming.collect {} } }
            runCurrent()

            // El llamador cancela la espera: el socket en vuelo se aborta con ella.
            openJob.cancel()
            drained.await() // onFailure ya cerró `incoming`: el socket está muerto
            val error = assertNotNull(opened.await().exceptionOrNull())
            assertIs<CancellationException>(error)
            assertFalse(transport.isOpen)
        }

    @Test
    fun `close es idempotente completa incoming y send posterior falla`() =
        runTest {
            val srv = newServer()
            val transport = WebSocketTransport(srv.wsUrl("/api/ws"), okHttpClient = srv.client)
            transport.awaitOpen()

            transport.close()
            transport.close() // idempotente
            assertFalse(transport.isOpen)
            assertIs<ChannelClosedException>(runCatching { transport.send("x") }.exceptionOrNull())

            // `incoming` ya completó: un lector termina aquí sin bloquearse.
            transport.incoming.collect {}
        }

    @Test
    fun `factory devuelve el transport ya abierto y registra el ticket de la URL`() =
        runTest {
            val srv = newServer()
            val factory = WebSocketTransport.factory(srv.client)

            val transport = factory.connect(ConnectParams(srv.wsUrl("/api/ws", "ticket=t-9")))
            assertIs<WebSocketTransport>(transport)
            assertTrue(transport.isOpen)
            srv.awaitSocket() // el servidor vio el upgrade
            assertEquals(listOf<String?>("t-9"), srv.tickets.toList())
            transport.close()
        }

    @Test
    fun `factory limpia el transport si el handshake falla`() =
        runTest {
            val srv = newServer()
            srv.acceptUpgrades = false
            val factory = WebSocketTransport.factory(srv.client)

            val error =
                assertNotNull(
                    runCatching { factory.connect(ConnectParams(srv.wsUrl("/api/ws"))) }
                        .exceptionOrNull(),
                )
            assertIs<IOException>(error)
        }

    @Test
    fun `url invalida lanza sin exponer el ticket en la excepcion`() =
        runTest {
            val srv = newServer()
            val secret = "ticket=SECRETO-UNICO-123"
            val error =
                assertNotNull(
                    runCatching {
                        WebSocketTransport("ws://[url mal formada?$secret", okHttpClient = srv.client)
                    }.exceptionOrNull(),
                )
            assertIs<IllegalArgumentException>(error)
            assertFalse(
                generateSequence<Throwable>(error) { it.cause }
                    .mapNotNull { it.message }
                    .any { secret in it },
                "§8: ningún mensaje de la cadena puede llevar el ticket",
            )
        }

    @Test
    fun `integrado en JsonRpcChannel el cierre del servidor mata el canal`() =
        runTest {
            val srv = newServer()
            val transport = WebSocketTransport(srv.wsUrl("/api/ws"), okHttpClient = srv.client)
            val dead = CompletableDeferred<Throwable>()
            val channel =
                JsonRpcChannel(
                    transport = transport,
                    scope = backgroundScope,
                    heartbeatInterval = Duration.ZERO,
                    onDead = { dead.complete(it) },
                )
            transport.awaitOpen()

            srv.awaitSocket().close(NORMAL_CLOSURE, "bye")
            assertIs<ChannelClosedException>(dead.await())
            assertTrue(channel.isClosed)
        }

    private companion object {
        const val NORMAL_CLOSURE = 1000
    }
}
