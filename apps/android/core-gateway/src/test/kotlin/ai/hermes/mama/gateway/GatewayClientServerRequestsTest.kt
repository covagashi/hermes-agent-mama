package ai.hermes.mama.gateway

import ai.hermes.mama.contract.RpcMethods
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Peticiones servidor→cliente tipadas (§2.5, B4): `approval`/`clarify` llegan
 * como [ApprovalRequest]/[ClarifyRequest] y esperan respuesta; el resto llega
 * como [UnsupportedRequest] con `-32601` ya enviado. Sobre [FakeTransport].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GatewayClientServerRequestsTest {
    @Test
    fun `approval se tipa con params y approve responde choice once`() =
        runTest {
            val transport = FakeTransport()
            val client = newGatewayClient(transport)

            transport.emit(
                """{"id":"req-1","method":"approval","params":${fixtureText("ApprovalRequestParams")}}""",
            )
            val typed = client.serverRequests.first()

            val approval = assertIs<ApprovalRequest>(typed)
            assertEquals("req-1", approval.id)
            assertEquals("sess_7f2a1c", approval.sessionId)
            assertEquals("req-42", approval.requestId)
            assertEquals("Descargar factura PDF", approval.params.description)
            assertFalse(approval.replayed)
            assertFalse(approval.isAnswered)

            assertTrue(approval.approve())
            runCurrent()

            val frame = transport.sentFrames().single()
            assertEquals("req-1", frame.getValue("id").jsonPrimitive.content)
            assertEquals(
                "once",
                frame
                    .getValue("result")
                    .jsonObject
                    .getValue("choice")
                    .jsonPrimitive.content,
            )

            // Segunda respuesta = no-op alambre (idempotencia de B1 propagada).
            assertTrue(approval.isAnswered)
            assertFalse(approval.deny())
        }

    @Test
    fun `approval deny responde choice deny`() =
        runTest {
            val transport = FakeTransport()
            val client = newGatewayClient(transport)

            transport.emit(
                """{"id":"req-2","method":"approval",""" +
                    """"params":{"session_id":"sess-1","request_id":"r-9","command":"ls -la"}}""",
            )
            val approval = assertIs<ApprovalRequest>(client.serverRequests.first())

            assertTrue(approval.deny())
            runCurrent()

            val frame = transport.sentFrames().single()
            assertEquals(
                "deny",
                frame
                    .getValue("result")
                    .jsonObject
                    .getValue("choice")
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `clarify se tipa con qid del lote y answerAll responde answers`() =
        runTest {
            val transport = FakeTransport()
            val client = newGatewayClient(transport)

            transport.emit(
                """{"id":"req-3","method":"clarify","params":${fixtureText("ClarifyRequestParams")}}""",
            )
            val clarify = assertIs<ClarifyRequest>(client.serverRequests.first())

            assertEquals("sess_7f2a1c", clarify.sessionId)
            assertEquals("¿Qué pedido quieres revisar?", clarify.params.question)
            // El lote trae `qid` (no `id`) — ClarifyQuestion generado.
            assertEquals(
                "q1",
                clarify.params.questions
                    ?.single()
                    ?.qid,
            )

            assertTrue(clarify.answerAll(mapOf("q1" to "El último")))
            runCurrent()

            val frame = transport.sentFrames().single()
            assertEquals("req-3", frame.getValue("id").jsonPrimitive.content)
            val answers =
                frame
                    .getValue("result")
                    .jsonObject
                    .getValue("answers")
                    .jsonObject
            assertEquals("El último", answers.getValue("q1").jsonPrimitive.content)
        }

    @Test
    fun `clarify de pregunta unica responde answer y dismiss cierra con result vacio`() =
        runTest {
            val transport = FakeTransport()
            val client = newGatewayClient(transport)

            transport.emit(
                """{"id":"req-4","method":"clarify",""" +
                    """"params":{"session_id":"sess-1","question":"¿té o café?"}}""",
            )
            val first = assertIs<ClarifyRequest>(client.serverRequests.first())

            assertTrue(first.answer("té"))
            runCurrent()
            assertEquals(
                "té",
                transport
                    .sentFrames()
                    .single()
                    .getValue("result")
                    .jsonObject
                    .getValue("answer")
                    .jsonPrimitive.content,
            )

            transport.emit(
                """{"id":"req-5","method":"clarify",""" +
                    """"params":{"session_id":"sess-1","question":"¿ahora o luego?"}}""",
            )
            val second = assertIs<ClarifyRequest>(client.serverRequests.first())

            assertTrue(second.dismiss())
            runCurrent()
            val dismissResult =
                transport
                    .sentFrames()
                    .last()
                    .getValue("result")
                    .jsonObject
            assertTrue(dismissResult.isEmpty(), "dismiss = result sin answer ni answers (cancel-all)")
        }

    @Test
    fun `peticion no soportada llega como UnsupportedRequest con -32601 ya enviado`() =
        runTest {
            val transport = FakeTransport()
            val client = newGatewayClient(transport)

            transport.emit(
                """{"id":"req-6","method":"sudo",""" +
                    """"params":{"session_id":"sess-1","prompt":"¿seguro?"}}""",
            )
            val typed = client.serverRequests.first()

            val unsupported = assertIs<UnsupportedRequest>(typed)
            assertEquals("req-6", unsupported.id)
            assertEquals("sudo", unsupported.method)
            assertTrue(unsupported.isAnswered, "el -32601 automático se acepta al clasificar")
            runCurrent()

            val frame = transport.sentFrames().single()
            assertEquals("req-6", frame.getValue("id").jsonPrimitive.content)
            val error = frame.getValue("error").jsonObject
            assertEquals(
                -32601,
                error
                    .getValue("code")
                    .jsonPrimitive.long
                    .toInt(),
            )
        }

    @Test
    fun `approval con params malformados cae a UnsupportedRequest con -32601`() =
        runTest {
            val transport = FakeTransport()
            val client = newGatewayClient(transport)

            // Falta session_id/request_id (requeridos por ApprovalRequestParams).
            transport.emit("""{"id":"req-7","method":"approval","params":{"detalle":"valor"}}""")
            val typed = client.serverRequests.first()

            assertIs<UnsupportedRequest>(typed)
            assertEquals("approval", typed.method)
            runCurrent()

            assertEquals(
                -32601,
                transport
                    .sentFrames()
                    .single()
                    .getValue("error")
                    .jsonObject
                    .getValue("code")
                    .jsonPrimitive.long
                    .toInt(),
            )
        }

    @Test
    fun `open_requests del result de resume se tipan marcadas replayed (re-entrega de B1)`() =
        runTest {
            val transport = FakeTransport()
            val client = newGatewayClient(transport)

            val call = async { client.resumeSession("stored_7f2a1c") }
            runCurrent()
            val id = idOf(transport.sentFrames().single())

            transport.emit(
                """{"id":$id,"result":{"session_id":"rt-1","message_count":0,"messages":[],""" +
                    """"info":{},"open_requests":[{"id":"srq-9","method":"clarify",""" +
                    """"params":{"session_id":"rt-1","question":"¿té o café?"}}]}}""",
            )

            val clarify = assertIs<ClarifyRequest>(client.serverRequests.first())
            assertTrue(clarify.replayed, "la re-entrega del canal debe conservar replayed")
            assertEquals("srq-9", clarify.id)
            assertEquals("¿té o café?", clarify.params.question)

            // La llamada se resuelve igualmente (la re-entrega la hizo B1).
            assertEquals(
                "rt-1",
                call
                    .await()
                    .sessionId,
            )
        }

    @Test
    fun `las peticiones esperan en cola aunque lleguen antes de que nadie colecte`() =
        runTest {
            val transport = FakeTransport()
            val client = newGatewayClient(transport)

            // Sin colector activo: la cola retiene la petición (SharedFlow la
            // perdería y una approval sin responder bloquearía el backend).
            transport.emit(
                """{"id":"req-8","method":"approval",""" +
                    """"params":{"session_id":"sess-1","request_id":"r-1"}}""",
            )
            runCurrent()
            assertTrue(transport.sentFrames().isEmpty(), "approval no recibe -32601: espera al colector")

            val approval = assertIs<ApprovalRequest>(client.serverRequests.first())
            assertEquals("req-8", approval.id)
        }
}
