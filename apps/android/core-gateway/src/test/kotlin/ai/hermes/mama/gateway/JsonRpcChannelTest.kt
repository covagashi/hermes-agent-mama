package ai.hermes.mama.gateway

import ai.hermes.mama.contract.EventTypes
import ai.hermes.mama.contract.RpcMethods
import ai.hermes.mama.contract.ServerRequests
import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contrato del canal JSON-RPC (ROADMAP §2.2 y tarea B1) sobre [FakeTransport].
 * Todo el timing es virtual (`TestScope` + `advanceTimeBy`): sin `Thread.sleep`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JsonRpcChannelTest {
    private val json = Json

    private fun TestScope.channel(
        transport: FakeTransport,
        logger: (String) -> Unit = {},
        onDead: (Throwable) -> Unit = {},
    ): JsonRpcChannel =
        JsonRpcChannel(
            transport = transport,
            scope = backgroundScope,
            logger = logger,
            onDead = onDead,
        )

    private fun idOf(frame: JsonObject): Long = frame.getValue("id").jsonPrimitive.long

    private fun JsonObject.isPing(): Boolean = this["method"]?.jsonPrimitive?.content == RpcMethods.GATEWAY_PING

    @Test
    fun `respuesta OK resuelve la llamada con su result`() =
        runTest {
            val transport = FakeTransport(autoPong = true)
            val channel = channel(transport)

            val call =
                async {
                    channel.call(
                        RpcMethods.SESSION_LIST,
                        buildJsonObject { put("limit", 10) },
                    )
                }
            runCurrent()

            val request = transport.sentFrames().single()
            assertEquals("2.0", request.getValue("jsonrpc").jsonPrimitive.content)
            assertEquals(RpcMethods.SESSION_LIST, request.getValue("method").jsonPrimitive.content)
            assertEquals(
                10,
                request
                    .getValue("params")
                    .jsonObject
                    .getValue("limit")
                    .jsonPrimitive.int,
            )
            val id = idOf(request)

            transport.emit("""{"jsonrpc":"2.0","id":$id,"result":{"sessions":[]}}""")
            assertEquals(json.parseToJsonElement("""{"sessions":[]}"""), call.await())
        }

    @Test
    fun `error JSON-RPC produce JsonRpcException con code y data`() =
        runTest {
            val transport = FakeTransport(autoPong = true)
            val channel = channel(transport)

            val call = async { runCatching { channel.call(RpcMethods.SESSION_RESUME) } }
            runCurrent()
            val id = idOf(transport.sentFrames().single())

            transport.emit(
                """{"id":$id,"error":{"code":-32601,"message":"method not found",""" +
                    """"data":{"method":"session.resume","hint":"redacted"}}}""",
            )
            val error = assertNotNull(call.await().exceptionOrNull())
            assertIs<JsonRpcException>(error)
            assertEquals(-32601, error.code)
            assertEquals("method not found", error.message)
            assertEquals(
                "redacted",
                error.data
                    ?.jsonObject
                    ?.get("hint")
                    ?.jsonPrimitive
                    ?.content,
            )
        }

    @Test
    fun `llamada sin respuesta expira con JsonRpcTimeoutException a los 120s`() =
        runTest {
            // autoPong mantiene vivo el watchdog: lo que expira es la llamada, no el canal.
            val transport = FakeTransport(autoPong = true)
            val channel = channel(transport)

            val call = async { runCatching { channel.call(RpcMethods.SESSION_HISTORY) } }
            runCurrent()

            advanceTimeBy(120_001)
            runCurrent()

            val error = assertNotNull(call.await().exceptionOrNull())
            assertIs<JsonRpcTimeoutException>(error)
            assertEquals(RpcMethods.SESSION_HISTORY, error.method)
            assertFalse(channel.isClosed, "el timeout de una llamada no debe matar el canal")
        }

    @Test
    fun `evento se decodifica con type sessionId seq y payload, gateway-ready es un evento mas`() =
        runTest {
            val transport = FakeTransport(autoPong = true)
            val channel = channel(transport)

            channel.events.test {
                runCurrent() // la suscripción de Turbine queda activa antes de emitir
                transport.emit(
                    """{"method":"event","params":{"type":"gateway.ready",""" +
                        """"payload":{"skin":{},"change_events":true,"replay_epoch":"e-1","heartbeat":true}}}""",
                )
                val ready = awaitItem()
                assertEquals(EventTypes.GATEWAY_READY, ready.type)
                assertNull(ready.sessionId)
                assertNull(ready.seq)
                assertEquals(
                    "e-1",
                    ready.payload.jsonObject
                        .getValue("replay_epoch")
                        .jsonPrimitive.content,
                )

                transport.emit(
                    """{"method":"event","params":{"type":"message.delta","session_id":"sess-1",""" +
                        """"seq":12,"payload":{"text":"hola 👋🏽, tu factura 📄"}}}""",
                )
                val delta = awaitItem()
                assertEquals(EventTypes.MESSAGE_DELTA, delta.type)
                assertEquals("sess-1", delta.sessionId)
                assertEquals(12L, delta.seq)
                assertEquals(
                    "hola 👋🏽, tu factura 📄",
                    delta.payload.jsonObject
                        .getValue("text")
                        .jsonPrimitive.content,
                )

                // Tipo desconocido: se tolera y se emite igual.
                transport.emit("""{"method":"event","params":{"type":"futuro.desconocido","seq":13}}""")
                assertEquals("futuro.desconocido", awaitItem().type)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `server request sin handler ni colector recibe -32601 automatico`() =
        runTest {
            val transport = FakeTransport()
            val channel = channel(transport)

            transport.emit(
                """{"id":"req-7","method":"sudo","params":{"session_id":"sess-1","prompt":"¿seguro?"}}""",
            )
            runCurrent()

            val frame = transport.sentFrames().single()
            assertEquals("2.0", frame.getValue("jsonrpc").jsonPrimitive.content)
            assertEquals("req-7", frame.getValue("id").jsonPrimitive.content)
            val error = frame.getValue("error").jsonObject
            assertEquals(-32601, error.getValue("code").jsonPrimitive.int)
        }

    @Test
    fun `server request que un handler no reclama tambien recibe -32601`() =
        runTest {
            val transport = FakeTransport()
            val channel = channel(transport)
            channel.addServerRequestHandler { false }

            transport.emit("""{"id":"req-8","method":"tour","params":{}}""")
            runCurrent()

            val frame = transport.sentFrames().single()
            assertEquals(
                -32601,
                frame
                    .getValue("error")
                    .jsonObject
                    .getValue("code")
                    .jsonPrimitive.int,
            )
        }

    @Test
    fun `respond y fail son idempotentes y emiten un solo frame`() =
        runTest {
            val transport = FakeTransport()
            val channel = channel(transport)

            val firstRequest = async { channel.serverRequests.first() }
            runCurrent() // asegura la suscripción antes de que llegue el frame

            transport.emit(
                """{"id":"req-9","method":"approval","params":{"session_id":"sess-1","request_id":"r-1"}}""",
            )
            val request = firstRequest.await()
            assertEquals("req-9", request.id)
            assertEquals(ServerRequests.APPROVAL, request.method)
            assertFalse(request.replayed)
            assertEquals(
                "r-1",
                request.params
                    .getValue("request_id")
                    .jsonPrimitive.content,
            )

            assertTrue(request.respond(buildJsonObject { put("choice", "once") }))
            assertTrue(request.isAnswered)
            // Segunda y tercera respuesta: no-op alambre (§2.2: re-entrega con mismo id).
            assertFalse(request.respond(buildJsonObject { put("choice", "deny") }))
            assertFalse(request.fail(-32601, "tarde"))
            runCurrent()

            val frames = transport.sentFrames()
            assertEquals(1, frames.size, "respond/fail repetidos no deben emitir más frames")
            assertEquals(
                "req-9",
                frames
                    .single()
                    .getValue("id")
                    .jsonPrimitive.content,
            )
            assertEquals(
                "once",
                frames
                    .single()
                    .getValue("result")
                    .jsonObject
                    .getValue("choice")
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `handler registrado reclama la peticion y no hay -32601`() =
        runTest {
            val transport = FakeTransport()
            val channel = channel(transport)
            var seen: ServerRequest? = null
            channel.addServerRequestHandler(
                ServerRequestHandler { request ->
                    seen = request
                    true
                },
            )

            transport.emit("""{"id":"req-10","method":"clarify","params":{"question":"¿cuál?"}}""")
            runCurrent()

            val request = assertNotNull(seen)
            assertEquals("clarify", request.method)
            request.fail(-32000, "cancelada")
            runCurrent()

            val frame = transport.sentFrames().single()
            assertEquals("req-10", frame.getValue("id").jsonPrimitive.content)
            assertEquals(
                -32000,
                frame
                    .getValue("error")
                    .jsonObject
                    .getValue("code")
                    .jsonPrimitive.int,
            )
        }

    @Test
    fun `open_requests dentro de un result se re-entregan como server requests replayed`() =
        runTest {
            val transport = FakeTransport(autoPong = true)
            val channel = channel(transport)

            val firstRequest = async { channel.serverRequests.first() }
            runCurrent()

            val call = async { channel.call(RpcMethods.SESSION_RESUME) }
            runCurrent()
            val id = idOf(transport.sentFrames().single())

            transport.emit(
                """{"id":$id,"result":{"session_id":"rt-1","open_requests":""" +
                    """[{"id":"srq-abc","method":"clarify",""" +
                    """"params":{"session_id":"s-1","question":"¿té o café?"}}]}}""",
            )

            val request = firstRequest.await()
            assertTrue(request.replayed, "la petición re-entregada debe marcarse replayed")
            assertEquals("srq-abc", request.id)
            assertEquals(ServerRequests.CLARIFY, request.method)

            // La llamada se resuelve DESPUÉS de re-entregar la petición (como en apps/shared).
            assertEquals(
                "rt-1",
                call
                    .await()
                    .jsonObject
                    .getValue("session_id")
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `frames malformados se loguean y el canal sigue funcionando`() =
        runTest {
            val warnings = mutableListOf<String>()
            val transport = FakeTransport(autoPong = true)
            val channel = channel(transport, logger = { warnings += it })

            transport.emit("esto no es json {{{")
            transport.emit("""[1,2,3]""") // no es objeto
            transport.emit("""{"method":"event","params":{"sin_type":true}}""") // evento sin type
            transport.emit("""{"id":999,"result":{}}""") // respuesta sin llamada pendiente
            transport.emit("""{"id":"ajeno","result":{}}""") // respuesta con id no numérico
            transport.emit("""{"method":"misterio"}""") // notificación que no es event
            runCurrent()

            assertTrue(warnings.isNotEmpty(), "los frames malformados deben quedar logueados")

            // Y el canal sigue respondiendo.
            val call = async { channel.call(RpcMethods.GATEWAY_CAPABILITIES) }
            runCurrent()
            val id = idOf(transport.sentFrames().single())
            transport.emit("""{"id":$id,"result":{"per_session_exclusive_submit":true}}""")
            assertEquals(
                "true",
                call
                    .await()
                    .jsonObject
                    .getValue("per_session_exclusive_submit")
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `heartbeat envia gateway-ping cada 15s y con pongs sigue vivo`() =
        runTest {
            var deadCause: Throwable? = null
            val transport = FakeTransport(autoPong = true)
            val channel = channel(transport, onDead = { deadCause = it })

            advanceTimeBy(15_000)
            runCurrent()
            assertEquals(1, transport.sentFrames().count { it.isPing() })

            advanceTimeBy(15_000)
            runCurrent()
            assertEquals(2, transport.sentFrames().count { it.isPing() })

            // Más de 45 s con pongs: el canal sigue vivo (el watchdog se alimenta).
            advanceTimeBy(60_000)
            runCurrent()
            assertFalse(channel.isClosed)
            assertEquals(null, deadCause)
        }

    @Test
    fun `sin respuesta en 45s el canal muere cierra el transport y avisa`() =
        runTest {
            var deadCause: Throwable? = null
            val transport = FakeTransport(autoPong = false)
            val channel = channel(transport, onDead = { deadCause = it })

            val stuckCall = async { runCatching { channel.call(RpcMethods.SESSION_LIST) } }
            runCurrent()

            advanceTimeBy(45_001)
            runCurrent()

            assertIs<HeartbeatTimeoutException>(deadCause)
            assertTrue(transport.closed, "el canal debe cerrar el transport")
            assertTrue(channel.isClosed)
            assertIs<HeartbeatTimeoutException>(assertNotNull(stuckCall.await().exceptionOrNull()))

            // Los pings sí salieron (a 15 s y 30 s) pero nadie respondió.
            val pings = transport.sentFrames().filter { it.isPing() }
            assertTrue(pings.isNotEmpty(), "el heartbeat debió emitir gateway.ping")
            assertTrue(pings.size <= 3, "no deben acumularse pings sin respuesta (≤45s/15s)")
        }

    @Test
    fun `1000 llamadas concurrentes no mezclan ids`() =
        runTest {
            val transport = FakeTransport(autoPong = true)
            val channel = channel(transport)

            val calls =
                (1..CALL_COUNT).map { n ->
                    async { n to channel.call("test.echo", buildJsonObject { put("n", n) }) }
                }
            runCurrent()

            val frames = transport.sentFrames()
            assertEquals(CALL_COUNT, frames.size)
            // ids incrementales y únicos
            assertEquals(CALL_COUNT, frames.map { idOf(it) }.toSet().size)

            // Respuestas en orden barajado: la correlación es por id, no por orden.
            frames.shuffled().forEach { frame ->
                val id = idOf(frame)
                val n =
                    frame
                        .getValue("params")
                        .jsonObject
                        .getValue("n")
                        .jsonPrimitive.int
                transport.emit("""{"id":$id,"result":{"echo":$n}}""")
            }
            runCurrent()

            calls.forEach { deferred ->
                val (n, result) = deferred.await()
                assertEquals(
                    n,
                    result.jsonObject
                        .getValue("echo")
                        .jsonPrimitive.int,
                )
            }
        }

    @Test
    fun `texto con emoji y plano astral viaja intacto por el canal`() =
        runTest {
            val transport = FakeTransport(autoPong = true)
            val channel = channel(transport)
            val emojiText = "Factura 📄 de la compra 👩‍👧‍👦 𝄞 café ☕"

            val call = async { channel.call(RpcMethods.PROMPT_SUBMIT, buildJsonObject { put("text", emojiText) }) }
            runCurrent()

            // El frame crudo conserva el UTF-8 (sin escapes \uXXXX para emoji).
            val raw = transport.sent.single()
            assertTrue(raw.contains("📄"), "el frame debe llevar el emoji en UTF-8 literal")
            assertTrue(raw.contains("𝄞"), "plano astral sin escapar")
            val id = idOf(transport.sentFrames().single())

            transport.emit(
                buildJsonObject {
                    put("id", id)
                    put("result", buildJsonObject { put("echo", emojiText) })
                }.toString(),
            )
            assertEquals(
                emojiText,
                call
                    .await()
                    .jsonObject
                    .getValue("echo")
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `close es idempotente y call posterior falla con ChannelClosedException`() =
        runTest {
            val transport = FakeTransport()
            val channel = channel(transport)

            channel.close()
            channel.close()
            assertTrue(channel.isClosed)
            assertTrue(transport.closed)

            assertIs<ChannelClosedException>(
                assertNotNull(runCatching { channel.call(RpcMethods.SESSION_LIST) }.exceptionOrNull()),
            )
        }

    @Test
    fun `send que lanza mata el canal y call falla con ChannelClosedException`() =
        runTest {
            var deadCause: Throwable? = null
            val transport = FakeTransport()
            val channel = channel(transport, onDead = { deadCause = it })
            transport.failOnSend = IOException("socket roto")

            val error = assertNotNull(runCatching { channel.call(RpcMethods.SESSION_LIST) }.exceptionOrNull())
            assertIs<ChannelClosedException>(error)
            assertIs<IOException>(error.cause)
            assertIs<IOException>(deadCause)
            runCurrent()
            assertTrue(channel.isClosed)
            assertTrue(transport.closed, "dead() debe cerrar el transport")
        }

    @Test
    fun `incoming que falla mata el canal y las pendientes reciben ChannelClosedException`() =
        runTest {
            var deadCause: Throwable? = null
            val transport = FakeTransport()
            val channel = channel(transport, onDead = { deadCause = it })

            val call = async { runCatching { channel.call(RpcMethods.SESSION_LIST) } }
            runCurrent()
            assertEquals(1, transport.sentFrames().size)

            transport.failIncoming(IOException("socket reset"))
            runCurrent()

            val error = assertNotNull(call.await().exceptionOrNull())
            assertIs<ChannelClosedException>(error)
            // La IOException cruda va encadenada bajo el ChannelClosedException.
            // OJO: en la JVM de tests (-ea) kotlinx "recupera" la traza de la
            // corrutina copiando la excepción — la copia lleva el original como
            // cause — así que el aserto recorre toda la cadena, no sólo .cause.
            assertTrue(
                generateSequence<Throwable>(error) { it.cause }.any { it is IOException },
                "la causa cruda del transporte va encadenada",
            )
            assertIs<IOException>(deadCause)
            assertTrue(channel.isClosed)
            assertTrue(transport.closed)
        }

    @Test
    fun `cancelar el scope mata el canal cierra el transport y call falla rapido`() =
        runTest {
            var deadCause: Throwable? = null
            val transport = FakeTransport()
            // Scope propio sobre el dispatcher del test: podemos cancelarlo sin
            // tocar el scope del test ni el tiempo virtual.
            val channelScope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
            val channel =
                JsonRpcChannel(
                    transport = transport,
                    scope = channelScope,
                    onDead = { deadCause = it },
                )

            val pendingCall = async { runCatching { channel.call(RpcMethods.SESSION_LIST) } }
            runCurrent()
            assertEquals(1, transport.sentFrames().size)

            channelScope.cancel()
            runCurrent()

            assertTrue(channel.isClosed)
            assertTrue(transport.closed, "el transport debe cerrarse aunque el scope muera")
            assertIs<ChannelClosedException>(assertNotNull(deadCause))
            assertIs<ChannelClosedException>(assertNotNull(pendingCall.await().exceptionOrNull()))

            // Una llamada posterior falla al momento y NO emite ningún frame.
            val error = assertNotNull(runCatching { channel.call(RpcMethods.SESSION_LIST) }.exceptionOrNull())
            assertIs<ChannelClosedException>(error)
            assertEquals(1, transport.sentFrames().size)
        }

    @Test
    fun `respuesta duplicada con el mismo id se ignora y se loguea`() =
        runTest {
            val warnings = mutableListOf<String>()
            val transport = FakeTransport(autoPong = true)
            val channel = channel(transport, logger = { warnings += it })

            val call = async { channel.call(RpcMethods.SESSION_LIST) }
            runCurrent()
            val id = idOf(transport.sentFrames().single())

            transport.emit("""{"id":$id,"result":{"sessions":[]}}""")
            transport.emit("""{"id":$id,"result":{"sessions":[]}}""")
            runCurrent()

            assertEquals(json.parseToJsonElement("""{"sessions":[]}"""), call.await())
            assertTrue(warnings.any { it.contains("sin llamada pendiente") })
        }

    private companion object {
        const val CALL_COUNT = 1_000
    }
}
