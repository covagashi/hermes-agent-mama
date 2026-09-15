package ai.hermes.mama.feature.chat.clarify

import ai.hermes.mama.contract.SessionCreateParams
import ai.hermes.mama.core.ui.theme.MamaTheme
import ai.hermes.mama.feature.chat.approval.ApprovalController
import ai.hermes.mama.gateway.GatewayClient
import ai.hermes.mama.gateway.JsonRpcChannel
import ai.hermes.mama.gateway.WebSocketTransport
import ai.hermes.mama.gateway.createSession
import ai.hermes.mama.gateway.submitPrompt
import ai.hermes.mama.testing.FakeGateway
import ai.hermes.mama.testing.FakeGatewayScript
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Flujo E2E instrumentado de C7 en dispositivo/emulador real: la app entera
 * (WebSocket → JsonRpcChannel → GatewayClient → ApprovalController →
 * ClarifyController → tarjeta Compose) contra el [FakeGateway] empotrado con
 * el guion `clarify3` — un lote de TRES preguntas (choices → multi_select →
 * texto libre) que la usuaria responde una a una y que sale al wire como UN
 * `{"answers":{qid:…}}` (ROADMAP §2.5, errata `qid` corregida).
 */
@RunWith(AndroidJUnit4::class)
class ClarifyFlowInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun loteDeTresPreguntas_vaUnaAUnaYEnviaAnswersPorQid() {
        val gateway = FakeGateway(FakeGatewayScript.load("clarify3")).start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val transport = WebSocketTransport(gateway.wsUrl)
            runBlocking { transport.awaitOpen() }
            val channel = JsonRpcChannel(transport, scope)
            val client = GatewayClient(channel, scope)
            // La cadena real de producción: C6 reenvía clarifies a C7 por la cola.
            val approvals = ApprovalController(client, scope).also { it.start() }
            val controller =
                ClarifyController(client, approvals.clarifyRequests, scope, ANSWERED_VISIBLE_MS)
                    .also { it.start() }

            composeRule.setContent {
                MamaTheme {
                    val card by controller.card.collectAsState()
                    card?.let { shown ->
                        ClarifyOverlay(
                            state = shown,
                            onAnswer = { controller.answer(shown.key, shown.questionNumber, it) },
                            onAnswerMulti = {
                                controller.answerSelections(shown.key, shown.questionNumber, it)
                            },
                            speech = null,
                        )
                    }
                }
            }

            runBlocking {
                val session = client.createSession(SessionCreateParams(title = "Test C7"))
                client.submitPrompt(session.sessionId, "quiero que me hagas tres preguntas")
            }

            // 1 de 3 — choices.
            composeRule.waitUntil(WAIT_MS) { controller.card.value != null }
            composeRule.onNodeWithText("Hermes pregunta").assertIsDisplayed()
            composeRule.onNodeWithText("1 de 3").assertIsDisplayed()
            composeRule.onNodeWithText("¿De qué color lo quieres?").assertIsDisplayed()
            composeRule.onNodeWithText("azul").performClick()

            // 2 de 3 — multi_select: nada sale al wire todavía.
            composeRule.waitUntil(WAIT_MS) {
                controller.card.value
                    ?.progress
                    ?.current == 2
            }
            assertEquals(0, gateway.answeredRequests.size)
            composeRule.onNodeWithText("2 de 3").assertIsDisplayed()
            composeRule.onNodeWithText("¿Qué recados hago esta tarde?").assertIsDisplayed()
            composeRule.onNodeWithText("farmacia").performClick()
            composeRule.onNodeWithText("correo").performClick()
            composeRule.onNodeWithText("Listo").performClick()

            // 3 de 3 — texto libre; al responder sale el frame ÚNICO del lote.
            composeRule.waitUntil(WAIT_MS) {
                controller.card.value
                    ?.progress
                    ?.current == 3
            }
            assertEquals(0, gateway.answeredRequests.size)
            composeRule.onNodeWithText("3 de 3").assertIsDisplayed()
            composeRule.onNodeWithText("¿Para cuándo lo necesitas?").assertIsDisplayed()
            composeRule.onNode(hasSetTextAction()).performTextInput("para mañana")
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("Enviar la respuesta").performClick()

            composeRule.waitUntil(WAIT_MS) { gateway.answeredRequests.isNotEmpty() }
            val answered = gateway.answeredRequests.single()
            assertEquals("clarify", answered.method)
            val answers =
                answered.result!!
                    .jsonObject
                    .getValue("answers")
                    .jsonObject
            assertEquals("azul", answers.getValue("color").jsonPrimitive.content)
            // multi_select viaja como string JSON de array (§2.5).
            assertEquals(
                "[\"correo\",\"farmacia\"]",
                answers.getValue("recados").jsonPrimitive.content,
            )
            assertEquals("para mañana", answers.getValue("cuando").jsonPrimitive.content)
        } finally {
            scope.cancel()
            gateway.close()
        }
    }

    private companion object {
        /** Holgura para WS + Compose en emulador lento. */
        const val WAIT_MS = 15_000L

        /** Confirmación visible más corta que en producción (el test no espera). */
        const val ANSWERED_VISIBLE_MS = 800L
    }
}
