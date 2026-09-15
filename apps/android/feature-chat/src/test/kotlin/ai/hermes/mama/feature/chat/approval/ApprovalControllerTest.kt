package ai.hermes.mama.feature.chat.approval

import ai.hermes.mama.gateway.GatewayClient
import ai.hermes.mama.gateway.JsonRpcChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ApprovalController] contra un [GatewayClient] real sobre [FakeTransport]:
 * el cable completo se ejercita (petición → tarjeta → respuesta al wire), no
 * sólo el estado interno — ROADMAP C6 ("respuesta `once`/`deny`, replay no
 * duplica tarjeta, cancel cierra").
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalControllerTest {
    @Test
    fun `approval viva muestra tarjeta y approve responde choice once`() =
        runTest {
            val transport = FakeTransport()
            val controller = newController(transport)

            transport.emit(
                approvalFrame(
                    id = "srq-1",
                    requestId = "req-42",
                    description = "Enviar un correo a Farmacia del Barrio",
                    toolName = "send_email",
                ),
            )
            runCurrent()

            val card = assertNotNull(controller.card.value)
            assertEquals(ApprovalKind.SendEmail, card.kind)
            assertEquals("Enviar un correo a Farmacia del Barrio", card.detail)
            assertEquals(ApprovalStatus.Pending, card.status)

            controller.approve(controller.headKey())
            runCurrent()

            assertEquals(ApprovalStatus.Approved, controller.card.value?.status)
            val response = transport.sentResponses().single()
            assertEquals("srq-1", response.getValue("id").jsonPrimitive.content)
            assertEquals(
                "once",
                response
                    .getValue("result")
                    .jsonObject
                    .getValue("choice")
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `deny responde choice deny y la tarjeta muestra la eleccion antes de cerrarse`() =
        runTest {
            val transport = FakeTransport()
            val controller = newController(transport)

            transport.emit(approvalFrame(id = "srq-2", requestId = "req-7", description = "Borrar un archivo"))
            runCurrent()

            controller.deny(controller.headKey())
            runCurrent()

            // La elección queda visible answeredVisibleMs y después la tarjeta se cierra sola.
            assertEquals(ApprovalStatus.Denied, controller.card.value?.status)
            val response = transport.sentResponses().single()
            assertEquals(
                "deny",
                response
                    .getValue("result")
                    .jsonObject
                    .getValue("choice")
                    .jsonPrimitive.content,
            )

            advanceTimeBy(VISIBLE_MS + 1)
            runCurrent()
            assertNull(controller.card.value)
        }

    @Test
    fun `request cancel con el id de la tarjeta la cierra`() =
        runTest {
            val transport = FakeTransport()
            val controller = newController(transport)

            transport.emit(approvalFrame(id = "srq-3", requestId = "req-8", description = "Enviar un correo"))
            runCurrent()
            assertNotNull(controller.card.value)

            transport.emit(cancelFrame(id = "srq-3"))
            runCurrent()

            assertNull(controller.card.value)
            assertTrue(transport.sentResponses().isEmpty(), "cancel no debe responder nada")
        }

    @Test
    fun `request cancel de otra id o de clarify no toca la tarjeta`() =
        runTest {
            val transport = FakeTransport()
            val controller = newController(transport)

            transport.emit(approvalFrame(id = "srq-4", requestId = "req-9", description = "Enviar un correo"))
            runCurrent()

            transport.emit(cancelFrame(id = "srq-otra"))
            transport.emit(cancelFrame(id = "srq-4", method = "clarify"))
            runCurrent()

            assertNotNull(controller.card.value, "ni otra id ni un cancel de clarify cierran la tarjeta")
        }

    @Test
    fun `re-entrega con mismo request_id no duplica y responde por el frame nuevo`() =
        runTest {
            val transport = FakeTransport()
            val controller = newController(transport)

            transport.emit(approvalFrame(id = "srq-5", requestId = "req-10", description = "Enviar un correo"))
            runCurrent()
            // Reconexión: el backend re-entrega la misma aprobación con otro id de frame.
            transport.emit(approvalFrame(id = "srq-6", requestId = "req-10", description = "Enviar un correo"))
            runCurrent()

            assertNotNull(controller.card.value, "sigue habiendo una sola tarjeta")

            controller.approve(controller.headKey())
            runCurrent()

            val response = transport.sentResponses().single()
            assertEquals(
                "srq-6",
                response.getValue("id").jsonPrimitive.content,
                "la respuesta sale por la re-entrega viva, no por el frame viejo",
            )
        }

    @Test
    fun `re-entrega de una ya respondida reenvia la misma eleccion sin nueva tarjeta`() =
        runTest {
            val transport = FakeTransport()
            val controller = newController(transport)

            transport.emit(approvalFrame(id = "srq-7", requestId = "req-11", description = "Enviar un correo"))
            runCurrent()
            controller.deny(controller.headKey())
            runCurrent()
            assertEquals(1, transport.sentResponses().size)

            // El servidor no recibió el deny (socket cayó) y re-entrega al reconectar.
            transport.emit(approvalFrame(id = "srq-8", requestId = "req-11", description = "Enviar un correo"))
            runCurrent()

            val responses = transport.sentResponses()
            assertEquals(2, responses.size, "la misma elección se reenvía sola")
            val resend = responses.last()
            assertEquals("srq-8", resend.getValue("id").jsonPrimitive.content)
            assertEquals(
                "deny",
                resend
                    .getValue("result")
                    .jsonObject
                    .getValue("choice")
                    .jsonPrimitive.content,
            )
            // Sigue siendo la misma tarjeta (Denied), no una pendiente nueva.
            assertEquals(ApprovalStatus.Denied, controller.card.value?.status)
        }

    @Test
    fun `resync con approval pending muestra tarjeta y responde por approval respond`() =
        runTest {
            val transport = FakeTransport()
            val controller = newController(transport)

            val resync = async { controller.resync("sess-1") }
            runCurrent()

            val pendingCall = transport.sentCalls("approval.pending").single()
            transport.emit(
                """{"id":${pendingCall.getValue("id").jsonPrimitive.long},""" +
                    """"result":{"approvals":[{"request_id":"req-42",""" +
                    """"description":"Descargar factura PDF","tool_name":"browser_navigate"}]}}""",
            )
            resync.await()

            val card = assertNotNull(controller.card.value)
            assertEquals(ApprovalKind.BrowseWeb, card.kind)
            assertEquals("Descargar factura PDF", card.detail)

            controller.approve(controller.headKey())
            runCurrent()

            val respondCall = transport.sentCalls("approval.respond").single()
            val params = respondCall.getValue("params").jsonObject
            assertEquals("req-42", params.getValue("request_id").jsonPrimitive.content)
            assertEquals("once", params.getValue("choice").jsonPrimitive.content)
            assertEquals("sess-1", params.getValue("session_id").jsonPrimitive.content)

            transport.emit("""{"id":${respondCall.getValue("id").jsonPrimitive.long},"result":{"resolved":1}}""")
            runCurrent()
            assertEquals(ApprovalStatus.Approved, controller.card.value?.status)
        }

    @Test
    fun `resync retira entradas re-sincronizadas que el servidor ya no tiene pendientes`() =
        runTest {
            val transport = FakeTransport()
            val controller = newController(transport)

            resyncWithIds(controller, transport, "req-1", "req-2")
            assertNotNull(controller.card.value)

            // La segunda re-sync ya no trae req-1/req-2: las resolvió otra superficie.
            resyncWithIds(controller, transport)
            assertNull(controller.card.value, "entradas ausentes en el servidor → la tarjeta se cierra")
        }

    @Test
    fun `resync no duplica una approval ya viva en la tarjeta`() =
        runTest {
            val transport = FakeTransport()
            val controller = newController(transport)

            transport.emit(approvalFrame(id = "srq-9", requestId = "req-42", description = "Enviar un correo"))
            runCurrent()

            val resync = async { controller.resync("sess-1") }
            runCurrent()
            val call = transport.sentCalls("approval.pending").single()
            transport.emit(
                """{"id":${call.getValue("id").jsonPrimitive.long},""" +
                    """"result":{"approvals":[{"request_id":"req-42","description":"Enviar un correo"}]}}""",
            )
            resync.await()

            controller.approve(controller.headKey())
            runCurrent()

            // La respuesta sale por el wire vivo (srq-9), no por approval.respond.
            assertTrue(transport.sentCalls("approval.respond").isEmpty())
            assertEquals(
                "srq-9",
                transport
                    .sentResponses()
                    .single()
                    .getValue("id")
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `resync no poda una entrada re-sincronizada ya re-ligada al wire`() =
        runTest {
            val transport = FakeTransport()
            val controller = newController(transport)

            // 1) llega por approval.pending; 2) el wire la re-entrega (mismo request_id);
            // 3) la siguiente re-sync ya no la trae — pero ahora la gobierna el wire.
            resyncWithIds(controller, transport, "req-50")
            transport.emit(approvalFrame(id = "srq-20", requestId = "req-50", description = "Enviar un correo"))
            runCurrent()
            resyncWithIds(controller, transport)

            assertNotNull(controller.card.value, "la entrada del wire no la poda la re-sync")

            controller.approve(controller.headKey())
            runCurrent()
            // La respuesta sale por el frame vivo, no por approval.respond.
            assertTrue(transport.sentCalls("approval.respond").isEmpty())
            assertEquals(
                "srq-20",
                transport
                    .sentResponses()
                    .single()
                    .getValue("id")
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `request cancel cierra una resync re-ligada al wire por su id de frame`() =
        runTest {
            val transport = FakeTransport()
            val controller = newController(transport)

            // Llega por approval.pending (sin frame) y luego el wire la re-entrega:
            // el cancel del servidor referencia el id de frame nuevo, no request_id.
            resyncWithIds(controller, transport, "req-50")
            transport.emit(approvalFrame(id = "srq-20", requestId = "req-50", description = "Enviar un correo"))
            runCurrent()

            transport.emit(cancelFrame(id = "srq-20"))
            runCurrent()

            assertNull(controller.card.value, "request.cancel con el srq de la re-entrega debe cerrar la tarjeta")
        }

    @Test
    fun `approve con la key de otra tarjeta no responde la cabeza`() =
        runTest {
            val transport = FakeTransport()
            val controller = newController(transport)

            transport.emit(approvalFrame(id = "srq-30", requestId = "req-30", description = "Primera acción"))
            transport.emit(approvalFrame(id = "srq-31", requestId = "req-31", description = "Segunda acción"))
            runCurrent()

            // La cabeza cambia entre render y tap: el cancel retira req-30 y
            // req-31 pasa a mostrarse. Un tap "viejo" sobre req-30 es no-op.
            transport.emit(cancelFrame(id = "srq-30"))
            runCurrent()

            controller.approve("req-30")
            runCurrent()
            assertTrue(transport.sentResponses().isEmpty(), "una key retirada no responde nada")

            val shown = assertNotNull(controller.card.value)
            assertEquals("req-31", shown.key)
            controller.approve(shown.key)
            runCurrent()
            assertEquals(
                "srq-31",
                transport
                    .sentResponses()
                    .single()
                    .getValue("id")
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `fallo de approval respond muestra error y el reintento responde`() =
        runTest {
            val transport = FakeTransport()
            val controller = newController(transport)

            resyncWithIds(controller, transport, "req-60")
            val key = controller.headKey()

            // El servidor rechaza la respuesta (error RPC): canal sigue vivo,
            // la tarjeta muestra el fallo y Sí sigue pulsable.
            controller.approve(key)
            runCurrent()
            val failed = transport.sentCalls("approval.respond").single()
            transport.emit(
                """{"id":${failed.getValue("id").jsonPrimitive.long},"error":{"code":-32000,"message":"boom"}}""",
            )
            runCurrent()
            assertEquals(ApprovalStatus.SendFailed, controller.card.value?.status)

            controller.approve(key)
            runCurrent()
            val retry = transport.sentCalls("approval.respond").last()
            transport.emit("""{"id":${retry.getValue("id").jsonPrimitive.long},"result":{"resolved":1}}""")
            runCurrent()
            assertEquals(ApprovalStatus.Approved, controller.card.value?.status)
        }

    @Test
    fun `approval respond con resolved 0 cierra la tarjeta`() =
        runTest {
            val transport = FakeTransport()
            val controller = newController(transport)

            resyncWithIds(controller, transport, "req-61")
            controller.approve(controller.headKey())
            runCurrent()

            // resolved:0 = ya la resolvió otra superficie → cerrar sin ruido.
            val call = transport.sentCalls("approval.respond").single()
            transport.emit("""{"id":${call.getValue("id").jsonPrimitive.long},"result":{"resolved":0}}""")
            runCurrent()

            assertNull(controller.card.value)
        }

    @Test
    fun `fallo de approval pending deja la resync sin efecto`() =
        runTest {
            val transport = FakeTransport()
            val controller = newController(transport)

            val resync = async { controller.resync("sess-1") }
            runCurrent()
            val call = transport.sentCalls("approval.pending").single()
            transport.emit(
                """{"id":${call.getValue("id").jsonPrimitive.long},"error":{"code":-32000,"message":"boom"}}""",
            )
            resync.await()

            assertNull(controller.card.value, "una resync fallida no muestra ni tumba nada")
        }

    @Test
    fun `doble tap concurrente emite una sola respuesta`() =
        runTest {
            val transport = FakeTransport()
            val controller = newController(transport)

            transport.emit(approvalFrame(id = "srq-40", requestId = "req-40", description = "Enviar un correo"))
            runCurrent()
            val key = controller.headKey()

            controller.approve(key)
            controller.approve(key)
            controller.deny(key)
            runCurrent()

            assertEquals(1, transport.sentResponses().size, "Sí+Sí+No simultáneos = una respuesta")
            assertEquals(ApprovalStatus.Approved, controller.card.value?.status)
        }

    @Test
    fun `cola de clarify llena responde cancel-all a la que no cabe`() =
        runTest {
            val transport = FakeTransport()
            val controller = newController(transport)

            fun clarifyFrame(i: Int) =
                """{"id":"srq-c$i","method":"clarify","params":{"session_id":"sess-1","question":"¿algo?"}}"""

            // Nadie colecta clarifyRequests: las primeras 64 llenan la cola
            // (a tandas, para no desbordar el buffer SharedFlow del canal, 64).
            repeat(64) { i ->
                transport.emit(clarifyFrame(i))
            }
            runCurrent()

            // La 65ª no cabe → cancel-all (result {} vacío) en vez de quedarse
            // colgando el backend.
            transport.emit(clarifyFrame(64))
            runCurrent()

            val dismissed = transport.sentResponses().single()
            assertEquals("srq-c64", dismissed.getValue("id").jsonPrimitive.content)
            assertTrue(
                dismissed.getValue("result").jsonObject.isEmpty(),
                "cancel-all = result vacío (doc del schema)",
            )
        }

    @Test
    fun `start tras close reanuda la cola y las clarifies siguen llegando`() =
        runTest {
            val transport = FakeTransport()
            val controller = newController(transport)

            transport.emit(approvalFrame(id = "srq-70", requestId = "req-70", description = "Primera"))
            runCurrent()
            controller.close()
            assertNull(controller.card.value)

            // Llega durante el stop: queda en el canal del client hasta el restart.
            transport.emit(approvalFrame(id = "srq-71", requestId = "req-71", description = "Segunda"))
            controller.start()
            runCurrent()

            // La pendiente sobrevive el stop (el servidor la sigue esperando)
            // y la nueva se ha drenado detrás en la cola.
            val revived = assertNotNull(controller.card.value, "start tras close recolecta otra vez")
            assertEquals("req-70", revived.key)
            controller.approve(revived.key)
            runCurrent()
            advanceTimeBy(VISIBLE_MS + 1)
            runCurrent()
            assertEquals("req-71", controller.card.value?.key)

            val clarify = async { controller.clarifyRequests.first() }
            runCurrent()
            transport.emit(
                """{"id":"srq-72","method":"clarify","params":{"session_id":"sess-1","question":"¿té o café?"}}""",
            )
            runCurrent()
            assertEquals("srq-72", clarify.await().id, "la cola de clarify no queda cerrada tras el restart")
        }

    @Test
    fun `respond con canal muerto muestra el estado de error y sigue pulsable`() =
        runTest {
            val transport = FakeTransport()
            val client = GatewayClient(channel = JsonRpcChannel(transport, backgroundScope), scope = backgroundScope)
            val controller = ApprovalController(client, backgroundScope, VISIBLE_MS)
            controller.start()

            transport.emit(approvalFrame(id = "srq-10", requestId = "req-12", description = "Enviar un correo"))
            runCurrent()

            client.close()
            runCurrent()

            controller.approve(controller.headKey())
            runCurrent()

            assertEquals(ApprovalStatus.SendFailed, controller.card.value?.status)
        }

    @Test
    fun `dos approvals esperan en cola y la segunda aparece al cerrarse la primera`() =
        runTest {
            val transport = FakeTransport()
            val controller = newController(transport)

            transport.emit(approvalFrame(id = "srq-11", requestId = "req-20", description = "Primera acción"))
            transport.emit(
                approvalFrame(
                    id = "srq-12",
                    requestId = "req-21",
                    description = "Segunda acción",
                    toolName = "terminal",
                ),
            )
            runCurrent()

            assertEquals("Primera acción", controller.card.value?.detail)

            controller.approve(controller.headKey())
            runCurrent()
            advanceTimeBy(VISIBLE_MS + 1)
            runCurrent()

            val next = assertNotNull(controller.card.value)
            assertEquals("Segunda acción", next.detail)
            assertEquals(ApprovalKind.RunCommand, next.kind)
            assertEquals(ApprovalStatus.Pending, next.status)
        }

    @Test
    fun `peticion no soportada emite aviso y clarify se reenvia al flujo de C7`() =
        runTest {
            val transport = FakeTransport()
            val controller = newController(transport)

            // Suscriptores activos ANTES de emitir (SharedFlow/Channel sin replay).
            val notice = async { controller.notices.first() }
            val clarify = async { controller.clarifyRequests.first() }
            runCurrent()

            transport.emit("""{"id":"srq-13","method":"sudo","params":{"session_id":"sess-1"}}""")
            transport.emit(
                """{"id":"srq-14","method":"clarify","params":{"session_id":"sess-1","question":"¿té o café?"}}""",
            )
            runCurrent()

            // UnsupportedRequest: -32601 automático de B4 + aviso para la UI.
            val errorResponse = transport.sentResponses().single()
            assertEquals("srq-13", errorResponse.getValue("id").jsonPrimitive.content)
            assertTrue(errorResponse.containsKey("error"))
            assertIs<ApprovalNotice.UnsupportedRequest>(notice.await())

            // La clarify se entrega a C7 entera y sin responder.
            assertEquals("srq-14", clarify.await().id)
        }

    // --- soporte ---

    /** La `key` de la tarjeta visible — lo que la UI pasaría a approve/deny. */
    private fun ApprovalController.headKey(): String = card.value?.key ?: error("tarjeta no visible")

    /** Corre una [ApprovalController.resync] contestando `approval.pending` con los request_ids dados. */
    private suspend fun TestScope.resyncWithIds(
        controller: ApprovalController,
        transport: FakeTransport,
        vararg requestIds: String,
    ) {
        val resync = async { controller.resync("sess-1") }
        runCurrent()
        val call = transport.sentCalls("approval.pending").last()
        val items = requestIds.joinToString(",") { """{"request_id":"$it","description":"Algo"}""" }
        transport.emit("""{"id":${call.getValue("id").jsonPrimitive.long},"result":{"approvals":[$items]}}""")
        resync.await()
    }

    private fun TestScope.newController(transport: FakeTransport): ApprovalController {
        val client =
            GatewayClient(
                channel = JsonRpcChannel(transport = transport, scope = backgroundScope),
                scope = backgroundScope,
            )
        return ApprovalController(client, backgroundScope, VISIBLE_MS).also { it.start() }
    }

    private fun approvalFrame(
        id: String,
        requestId: String,
        description: String,
        toolName: String? = null,
        sessionId: String = "sess-1",
    ): String {
        val tool = toolName?.let { ""","tool_name":"$it"""" } ?: ""
        return """{"id":"$id","method":"approval","params":{"session_id":"$sessionId",""" +
            """"request_id":"$requestId","description":"$description"$tool}}"""
    }

    private fun cancelFrame(
        id: String,
        method: String = "approval",
    ): String =
        """{"method":"event","params":{"type":"request.cancel","session_id":"sess-1",""" +
            """"payload":{"id":"$id","method":"$method","reason":"timeout"}}}"""

    private companion object {
        const val VISIBLE_MS = 1_600L
    }
}
