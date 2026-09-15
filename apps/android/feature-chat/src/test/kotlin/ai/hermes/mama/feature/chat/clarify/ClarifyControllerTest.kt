package ai.hermes.mama.feature.chat.clarify

import ai.hermes.mama.feature.chat.approval.ApprovalController
import ai.hermes.mama.feature.chat.approval.FakeTransport
import ai.hermes.mama.gateway.GatewayClient
import ai.hermes.mama.gateway.JsonRpcChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ClarifyController] contra un [GatewayClient] real sobre [FakeTransport]
 * con la cadena completa de producción: frame `clarify` → [ApprovalController]
 * (cola C6) → [ClarifyController] → tarjeta → respuesta al wire. Cubre el
 * contrato exacto de C7: `{answer}` en única, `{answers:{qid:…}}` en lote, una
 * a una, cancel, replay/re-entrega y la selección múltiple como JSON array.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClarifyControllerTest {
    @Test
    fun `pregunta simple con opciones muestra tarjeta y responde answer`() =
        runTest {
            val transport = FakeTransport()
            val controller = newController(transport)

            transport.emit(
                clarifyFrame(
                    id = "srq-1",
                    question = "¿Lo quieres en rojo o en azul?",
                    choices = """["rojo","azul"]""",
                ),
            )
            runCurrent()

            val card = assertNotNull(controller.card.value)
            assertEquals("¿Lo quieres en rojo o en azul?", card.question)
            assertNull(card.progress, "pregunta única: sin 'N de M'")
            assertEquals(ClarifyStatus.Pending, card.status)
            val input = assertIs<ClarifyInput.Choices>(card.input)
            assertEquals(listOf("rojo", "azul"), input.options.map { it.display })

            controller.answer(card.key, card.questionNumber, "rojo")
            runCurrent()

            assertEquals(ClarifyStatus.Answered, controller.card.value?.status)
            val response = transport.sentResponses().single()
            assertEquals("srq-1", response.getValue("id").jsonPrimitive.content)
            assertEquals(
                "rojo",
                response
                    .getValue("result")
                    .jsonObject
                    .getValue("answer")
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `pregunta sin opciones muestra texto libre`() =
        runTest {
            val transport = FakeTransport()
            val controller = newController(transport)

            transport.emit(clarifyFrame(id = "srq-2", question = "¿Para cuándo lo necesitas?"))
            runCurrent()

            val card = assertNotNull(controller.card.value)
            assertIs<ClarifyInput.FreeText>(card.input)
        }

    @Test
    fun `lote de tres preguntas va una a una y envia answers por qid`() =
        runTest {
            val transport = FakeTransport()
            val controller = newController(transport)

            transport.emit(batchFrame(id = "srq-3"))
            runCurrent()

            // 1 de 3: choices.
            var card = assertNotNull(controller.card.value)
            assertEquals(ClarifyProgress(current = 1, total = 3), card.progress)
            assertEquals("¿De qué color lo quieres?", card.question)
            assertIs<ClarifyInput.Choices>(card.input)
            controller.answer(card.key, card.questionNumber, "rojo")
            runCurrent()
            assertTrue(transport.sentResponses().isEmpty(), "la 1ª del lote no emite nada aún")

            // 2 de 3: multi_select.
            card = assertNotNull(controller.card.value)
            assertEquals(ClarifyProgress(current = 2, total = 3), card.progress)
            assertEquals("¿Qué recados hago esta tarde?", card.question)
            assertIs<ClarifyInput.MultiSelect>(card.input)
            controller.answerSelections(card.key, card.questionNumber, listOf("correo", "farmacia"))
            runCurrent()
            assertTrue(transport.sentResponses().isEmpty(), "la 2ª del lote tampoco emite")

            // 3 de 3: texto libre → AHORA sale el frame único del lote.
            card = assertNotNull(controller.card.value)
            assertEquals(ClarifyProgress(current = 3, total = 3), card.progress)
            assertEquals("¿Para cuándo lo necesitas?", card.question)
            assertIs<ClarifyInput.FreeText>(card.input)
            controller.answer(card.key, card.questionNumber, "para mañana")
            runCurrent()

            assertEquals(ClarifyStatus.Answered, controller.card.value?.status)
            val response = transport.sentResponses().single()
            assertEquals("srq-3", response.getValue("id").jsonPrimitive.content)
            val answers =
                response
                    .getValue("result")
                    .jsonObject
                    .getValue("answers")
                    .jsonObject
            assertEquals("rojo", answers.getValue("color").jsonPrimitive.content)
            assertEquals("para mañana", answers.getValue("cuando").jsonPrimitive.content)
            // multi_select viaja como string JSON de array (§2.5 / _clean_answer).
            assertEquals("[\"correo\",\"farmacia\"]", answers.getValue("recados").jsonPrimitive.content)
        }

    @Test
    fun `multi_select de una sola marca tambien viaja como array`() =
        runTest {
            val transport = FakeTransport()
            val controller = newController(transport)

            transport.emit(
                clarifyFrame(
                    id = "srq-4",
                    question = "¿Qué días?",
                    choices = """["lunes","martes"]""",
                    multiSelect = true,
                ),
            )
            runCurrent()

            val card = assertNotNull(controller.card.value)
            assertIs<ClarifyInput.MultiSelect>(card.input)
            controller.answerSelections(card.key, card.questionNumber, listOf("lunes"))
            runCurrent()

            val answer =
                transport
                    .sentResponses()
                    .single()
                    .getValue("result")
                    .jsonObject
                    .getValue("answer")
                    .jsonPrimitive.content
            assertEquals("[\"lunes\"]", answer)
        }

    @Test
    fun `opcion recomendada se pinta sin el sufijo y el wire lo lleva entero`() =
        runTest {
            val transport = FakeTransport()
            val controller = newController(transport)

            transport.emit(
                clarifyFrame(
                    id = "srq-5",
                    question = "¿Cuál prefieres?",
                    choices = """["Tienda Ejemplo (Recommended)","Otra tienda"]""",
                ),
            )
            runCurrent()

            val input = assertIs<ClarifyInput.Choices>(controller.card.value?.input)
            assertEquals("Tienda Ejemplo", input.options[0].display)
            assertTrue(input.options[0].recommended)
            assertEquals("Otra tienda", input.options[1].display)

            // La respuesta devuelve la etiqueta EXACTA del wire (el servidor
            // le quita el sufijo él mismo con strip_recommended).
            controller.answer(controller.headKey(), 1, input.options[0].wire)
            runCurrent()
            assertEquals(
                "Tienda Ejemplo (Recommended)",
                transport
                    .sentResponses()
                    .single()
                    .getValue("result")
                    .jsonObject
                    .getValue("answer")
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `request cancel con el id de la tarjeta la cierra`() =
        runTest {
            val transport = FakeTransport()
            val controller = newController(transport)

            transport.emit(clarifyFrame(id = "srq-6", question = "¿Algo?"))
            runCurrent()
            assertNotNull(controller.card.value)

            transport.emit(cancelFrame(id = "srq-6"))
            runCurrent()

            assertNull(controller.card.value)
            assertTrue(transport.sentResponses().isEmpty(), "cancel no debe responder nada")
        }

    @Test
    fun `request cancel de otra id o de approval no toca la tarjeta`() =
        runTest {
            val transport = FakeTransport()
            val controller = newController(transport)

            transport.emit(clarifyFrame(id = "srq-7", question = "¿Algo?"))
            runCurrent()

            transport.emit(cancelFrame(id = "srq-otra"))
            transport.emit(cancelFrame(id = "srq-7", method = "approval"))
            runCurrent()

            assertNotNull(controller.card.value, "ni otra id ni un cancel de approval cierran la tarjeta")
        }

    @Test
    fun `re-entrega con mismo id no duplica y responde por el frame nuevo`() =
        runTest {
            val transport = FakeTransport()
            val controller = newController(transport)

            transport.emit(clarifyFrame(id = "srq-8", question = "¿Color?"))
            runCurrent()
            // Reconexión: el backend re-entrega la misma clarify (mismo srq).
            transport.emit(clarifyFrame(id = "srq-8", question = "¿Color?"))
            runCurrent()

            assertNotNull(controller.card.value, "sigue habiendo una sola tarjeta")
            controller.answer(controller.headKey(), 1, "rojo")
            runCurrent()

            assertEquals(1, transport.sentResponses().size)
        }

    @Test
    fun `re-entrega de una ya respondida reenvia el mismo result`() =
        runTest {
            val transport = FakeTransport()
            val controller = newController(transport)

            transport.emit(clarifyFrame(id = "srq-9", question = "¿Color?"))
            runCurrent()
            controller.answer(controller.headKey(), 1, "rojo")
            runCurrent()
            assertEquals(1, transport.sentResponses().size)

            // El servidor no recibió la respuesta (socket cayó) y re-entrega.
            transport.emit(clarifyFrame(id = "srq-9", question = "¿Color?"))
            runCurrent()

            val responses = transport.sentResponses()
            assertEquals(2, responses.size, "la misma respuesta se reenvía sola")
            assertEquals(
                "rojo",
                responses
                    .last()
                    .getValue("result")
                    .jsonObject
                    .getValue("answer")
                    .jsonPrimitive.content,
            )
            assertEquals(ClarifyStatus.Answered, controller.card.value?.status)
        }

    @Test
    fun `re-entrega de lote respeta las answers ya bloqueadas`() =
        runTest {
            val transport = FakeTransport()
            val controller = newController(transport)

            // Replay tras reconexión: el servidor ya tiene "color" respondida.
            transport.emit(batchFrame(id = "srq-10", locked = """"answers":{"color":"rojo"},"""))
            runCurrent()

            val card = assertNotNull(controller.card.value)
            assertEquals(ClarifyProgress(current = 2, total = 3), card.progress)
            assertEquals("¿Qué recados hago esta tarde?", card.question)
        }

    @Test
    fun `tap tardio sobre la pregunta anterior no responde la siguiente`() =
        runTest {
            val transport = FakeTransport()
            val controller = newController(transport)

            transport.emit(batchFrame(id = "srq-11"))
            runCurrent()

            // La usuaria toca dos veces seguidas la 1ª opción: el segundo tap
            // (mismo questionNumber=1, lote ya en la 2) es no-op.
            controller.answer("srq-11", 1, "rojo")
            controller.answer("srq-11", 1, "azul")
            runCurrent()

            val card = assertNotNull(controller.card.value)
            assertEquals("¿Qué recados hago esta tarde?", card.question, "la 2ª pregunta sigue intacta")
            controller.answer("srq-11", 2, "correo")
            controller.answer("srq-11", 3, "mañana")
            runCurrent()

            val answers =
                transport
                    .sentResponses()
                    .single()
                    .getValue("result")
                    .jsonObject
                    .getValue("answers")
                    .jsonObject
            assertEquals("rojo", answers.getValue("color").jsonPrimitive.content)
            // "correo" llegó por answer() (no answerSelections): viaja tal cual.
            assertEquals("correo", answers.getValue("recados").jsonPrimitive.content)
        }

    @Test
    fun `clarify sin pregunta ni lote se responde cancel-all`() =
        runTest {
            val transport = FakeTransport()
            val controller = newController(transport)

            transport.emit(
                """{"id":"srq-12","method":"clarify","params":{"session_id":"sess-1"}}""",
            )
            runCurrent()

            val dismissed = transport.sentResponses().single()
            assertEquals("srq-12", dismissed.getValue("id").jsonPrimitive.content)
            assertTrue(
                dismissed.getValue("result").jsonObject.isEmpty(),
                "cancel-all = result vacío (doc del schema)",
            )
            assertNull(controller.card.value)
        }

    @Test
    fun `dismiss responde result vacio y cierra`() =
        runTest {
            val transport = FakeTransport()
            val controller = newController(transport)

            transport.emit(clarifyFrame(id = "srq-13", question = "¿Algo?"))
            runCurrent()

            controller.dismiss("srq-13")
            runCurrent()

            val dismissed = transport.sentResponses().single()
            assertTrue(dismissed.getValue("result").jsonObject.isEmpty())
            assertNull(controller.card.value)
        }

    @Test
    fun `dos clarifies esperan en cola y la segunda aparece al cerrarse la primera`() =
        runTest {
            val transport = FakeTransport()
            val controller = newController(transport)

            transport.emit(clarifyFrame(id = "srq-14", question = "¿Primera?"))
            transport.emit(clarifyFrame(id = "srq-15", question = "¿Segunda?"))
            runCurrent()

            assertEquals("¿Primera?", controller.card.value?.question)

            controller.answer("srq-14", 1, "sí")
            runCurrent()
            advanceTimeBy(VISIBLE_MS + 1)
            runCurrent()

            val next = assertNotNull(controller.card.value)
            assertEquals("¿Segunda?", next.question)
            assertEquals(ClarifyStatus.Pending, next.status)
        }

    @Test
    fun `answer con canal muerto muestra el estado de error`() =
        runTest {
            val transport = FakeTransport()
            val client =
                GatewayClient(channel = JsonRpcChannel(transport, backgroundScope), scope = backgroundScope)
            val approvals = ApprovalController(client, backgroundScope).also { it.start() }
            val controller =
                ClarifyController(client, approvals.clarifyRequests, backgroundScope, VISIBLE_MS)
                    .also { it.start() }

            transport.emit(clarifyFrame(id = "srq-16", question = "¿Algo?"))
            runCurrent()

            client.close()
            runCurrent()

            controller.answer("srq-16", 1, "sí")
            runCurrent()

            assertEquals(ClarifyStatus.SendFailed, controller.card.value?.status)
        }

    @Test
    fun `close oculta la tarjeta y start la recupera de la cola`() =
        runTest {
            val transport = FakeTransport()
            val client =
                GatewayClient(
                    channel = JsonRpcChannel(transport, backgroundScope),
                    scope = backgroundScope,
                )
            val approvals = ApprovalController(client, backgroundScope).also { it.start() }
            val controller =
                ClarifyController(client, approvals.clarifyRequests, backgroundScope, VISIBLE_MS)
                    .also { it.start() }

            transport.emit(clarifyFrame(id = "srq-17", question = "¿Algo?"))
            runCurrent()
            assertNotNull(controller.card.value)

            controller.close()
            assertNull(controller.card.value)

            controller.start()
            runCurrent()
            // La entrada sobrevive en la cola del controller (el servidor la
            // sigue esperando): start() re-publica la cabeza.
            assertEquals("¿Algo?", controller.card.value?.question)
        }

    // --- soporte ---

    /** La `key` de la tarjeta visible — lo que la UI pasaría a answer/dismiss. */
    private fun ClarifyController.headKey(): String = card.value?.key ?: error("tarjeta no visible")

    private fun TestScope.newController(transport: FakeTransport): ClarifyController {
        val client =
            GatewayClient(
                channel = JsonRpcChannel(transport = transport, scope = backgroundScope),
                scope = backgroundScope,
            )
        // La cadena real de producción: C6 reenvía clarifies a C7 por la cola.
        val approvals = ApprovalController(client, backgroundScope).also { it.start() }
        return ClarifyController(client, approvals.clarifyRequests, backgroundScope, VISIBLE_MS)
            .also { it.start() }
    }

    private fun clarifyFrame(
        id: String,
        question: String,
        choices: String? = null,
        multiSelect: Boolean = false,
        sessionId: String = "sess-1",
    ): String {
        val choicesPart = choices?.let { ""","choices":$it""" } ?: ""
        val multiPart = if (multiSelect) ""","multi_select":true""" else ""
        return """{"id":"$id","method":"clarify","params":{"session_id":"$sessionId",""" +
            """"question":"$question"$choicesPart$multiPart}}"""
    }

    /** Lote de 3 preguntas del guion clarify3: choices → multi_select → libre. */
    private fun batchFrame(
        id: String,
        locked: String = "",
        sessionId: String = "sess-1",
    ): String =
        """{"id":"$id","method":"clarify","params":{"session_id":"$sessionId",$locked""" +
            """"questions":[""" +
            """{"qid":"color","question":"¿De qué color lo quieres?","choices":["rojo","azul","verde"]},""" +
            """{"qid":"recados","question":"¿Qué recados hago esta tarde?",""" +
            """"choices":["correo","farmacia","panadería"],"multi_select":true},""" +
            """{"qid":"cuando","question":"¿Para cuándo lo necesitas?"}]}}"""

    private fun cancelFrame(
        id: String,
        method: String = "clarify",
    ): String =
        """{"method":"event","params":{"type":"request.cancel","session_id":"sess-1",""" +
            """"payload":{"id":"$id","method":"$method","reason":"timeout"}}}"""

    private companion object {
        const val VISIBLE_MS = 1_600L
    }
}
