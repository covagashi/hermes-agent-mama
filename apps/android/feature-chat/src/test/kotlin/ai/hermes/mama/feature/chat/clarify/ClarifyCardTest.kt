package ai.hermes.mama.feature.chat.clarify

import ai.hermes.mama.core.ui.theme.MamaTheme
import ai.hermes.mama.feature.chat.R
import ai.hermes.mama.feature.voice.SpeechInput
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.accessibility.AccessibilityChecks
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compose UI Test de la tarjeta de pregunta (C7): opciones grandes, progreso
 * "N de M", chips multi_select + "Listo", campo libre + micrófono (con
 * [SpeechInput] fake), confirmación, fallo de envío y fuente 2.0×.
 * `AccessibilityChecks` (espresso → ATF) rompe el test ante violaciones ERROR.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp")
class ClarifyCardTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `tarjeta con opciones muestra cabecera pregunta y botones grandes`() {
        setCard(choicesState())

        composeRule.onNodeWithText(title).assertIsDisplayed()
        composeRule.onNodeWithText("1 de 2").assertIsDisplayed()
        composeRule.onNodeWithText(QUESTION).assertIsDisplayed()
        composeRule
            .onNodeWithText(CHOICE_1_DISPLAY)
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertHeightIsAtLeast(MIN_TOUCH_DP.dp)
        composeRule
            .onNodeWithText(CHOICE_2)
            .assertHasClickAction()
            .assertHeightIsAtLeast(MIN_TOUCH_DP.dp)
        composeRule.onNodeWithText(recommended).performScrollTo().assertIsDisplayed()
        // El campo libre siempre está ("o responde con tu voz"); puede quedar
        // bajo el pliegue en la pantalla pequeña del emulador → scroll.
        composeRule.onNode(hasSetTextAction()).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription(micCd).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `tap en una opcion responde con la etiqueta wire entera`() {
        var answered: String? = null
        setCard(choicesState(), onAnswer = { answered = it })

        // La opción se pinta SIN el "(Recommended)" del wire pero responde con él.
        composeRule.onNodeWithText(CHOICE_1_DISPLAY).performClick()
        assertEquals(CHOICE_1_WIRE, answered, "el wire va tal cual (el servidor quita el sufijo)")
    }

    @Test
    fun `multi_select marca casillas y Listo envia en orden del servidor`() {
        var selections: List<String>? = null
        setCard(multiState(), onAnswerMulti = { selections = it })

        // "Listo" deshabilitado hasta marcar algo.
        composeRule.onNodeWithText(done).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("Panadería").performScrollTo().performClick()
        composeRule.onNodeWithText("Correo").performScrollTo().performClick()
        composeRule
            .onNodeWithText(done)
            .performScrollTo()
            .assertIsEnabled()
            .performClick()

        // Orden del servidor (correo antes que panadería), no el de los taps —
        // y viajan las etiquetas WIRE (minúscula), no el display.
        assertEquals(listOf("correo", "panadería"), selections)
    }

    @Test
    fun `campo libre escribe y el boton enviar responde el texto`() {
        var answered: String? = null
        setCard(freeTextState(), onAnswer = { answered = it })

        composeRule.onNodeWithContentDescription(micCd).performScrollTo().assertIsDisplayed()
        composeRule.onNode(hasSetTextAction()).performTextInput("para mañana")
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription(micCd).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(sendCd).performScrollTo().performClick()
        assertEquals("para mañana", answered)
    }

    @Test
    fun `microfono dicta el texto al campo`() {
        var answered: String? = null
        val backend = FakeSpeechBackend()
        val speech = SpeechInput(backend = backend, audioPermission = { true })
        setCard(freeTextState(), onAnswer = { answered = it }, speech = speech)

        composeRule.onNodeWithContentDescription(micCd).performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(1, backend.engines.size, "el tap abrió una sesión de dictado")

        // Parcial en vivo → el campo; resultado final → fija el texto.
        backend.engines.single().emitPartial("para ma")
        composeRule.waitForIdle()
        backend.engines.single().emitResult("para mañana")
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription(sendCd).performScrollTo().performClick()
        assertEquals("para mañana", answered)
    }

    @Test
    fun `microfono sin SpeechInput queda deshabilitado`() {
        setCard(freeTextState(), speech = null)

        // El campo sigue vivo: la usuaria siempre puede escribir.
        composeRule.onNodeWithContentDescription(micCd).performScrollTo().assertIsNotEnabled()
        composeRule.onNode(hasSetTextAction()).assertIsEnabled()
    }

    @Test
    fun `tarjeta respondida muestra la confirmacion y ya no es clickable`() {
        setCard(freeTextState().copy(status = ClarifyStatus.Answered))

        composeRule.onNodeWithText(answered).assertIsDisplayed()
        composeRule.onNode(hasSetTextAction()).assertDoesNotExist()
        assertTrue(
            composeRule.onAllNodes(hasClickAction()).fetchSemanticsNodes().isEmpty(),
            "la tarjeta respondida no debe conservar acciones de click",
        )
    }

    @Test
    fun `fallo de envio muestra el aviso y las entradas siguen vivas`() {
        var answered: String? = null
        setCard(
            choicesState().copy(status = ClarifyStatus.SendFailed),
            onAnswer = { answered = it },
        )

        composeRule.onNodeWithText(sendFailed).assertIsDisplayed()
        composeRule.onNodeWithText(CHOICE_2).assertHasClickAction().performClick()
        assertEquals(CHOICE_2, answered, "el reintento debe volver a responder")
    }

    @Test
    @Config(sdk = [34], fontScale = 2.0f, qualifiers = "w390dp")
    fun `fuente 2x no corta la tarjeta`() {
        setCard(multiState())

        // Con fuente ×2 la hoja puede superar la pantalla: el scroll deja
        // llegar a todo (nada cortado) y los controles conservan su tamaño.
        composeRule.onNodeWithText(title).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(MULTI_QUESTION).performScrollTo().assertIsDisplayed()
        composeRule
            .onNodeWithText("Correo")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHeightIsAtLeast(MIN_TOUCH_DP.dp)
        composeRule.onNodeWithText(done).performScrollTo().assertIsDisplayed()
        composeRule.onNode(hasSetTextAction()).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `accesibilidad en pendiente respondida y fallo`() {
        // setContent sólo puede llamarse una vez: el estado muta y recompone.
        val current = mutableStateOf(choicesState())
        composeRule.setContent {
            MamaTheme {
                ClarifyOverlay(
                    state = current.value,
                    onAnswer = {},
                    onAnswerMulti = {},
                    speech = null,
                )
            }
        }
        runEspressoA11yCheck()

        current.value = choicesState().copy(status = ClarifyStatus.Answered)
        composeRule.waitForIdle()
        runEspressoA11yCheck()

        current.value = choicesState().copy(status = ClarifyStatus.SendFailed)
        composeRule.waitForIdle()
        runEspressoA11yCheck()
    }

    // --- soporte ---

    private fun setCard(
        state: ClarifyCardState,
        onAnswer: (String) -> Unit = {},
        onAnswerMulti: (List<String>) -> Unit = {},
        speech: SpeechInput? = null,
    ) {
        composeRule.setContent {
            MamaTheme {
                ClarifyOverlay(
                    state = state,
                    onAnswer = onAnswer,
                    onAnswerMulti = onAnswerMulti,
                    speech = speech,
                )
            }
        }
    }

    private fun choicesState() =
        ClarifyCardState(
            key = "req-test",
            question = QUESTION,
            input =
                ClarifyInput.Choices(
                    options =
                        listOf(
                            ClarifyOption(CHOICE_1_DISPLAY, CHOICE_1_WIRE, recommended = true),
                            ClarifyOption(CHOICE_2, CHOICE_2),
                            ClarifyOption(CHOICE_3, CHOICE_3),
                        ),
                ),
            progress = ClarifyProgress(current = 1, total = 2),
        )

    private fun multiState() =
        ClarifyCardState(
            key = "req-test",
            question = MULTI_QUESTION,
            input =
                ClarifyInput.MultiSelect(
                    options =
                        listOf(
                            ClarifyOption("Correo", "correo"),
                            ClarifyOption("Farmacia", "farmacia"),
                            ClarifyOption("Panadería", "panadería"),
                        ),
                ),
            progress = ClarifyProgress(current = 2, total = 3),
        )

    private fun freeTextState() =
        ClarifyCardState(
            key = "req-test",
            question = FREE_QUESTION,
            input = ClarifyInput.FreeText,
        )

    /** Evalúa la raíz con ATF: violaciones de nivel ERROR rompen el test. */
    private fun runEspressoA11yCheck() {
        onView(isRoot()).check(matches(isDisplayed()))
    }

    private companion object {
        const val MIN_TOUCH_DP = 56
        const val QUESTION = "¿En qué tienda compraste la lavadora?"
        const val CHOICE_1_DISPLAY = "Tienda Ejemplo (3 de septiembre)"
        const val CHOICE_1_WIRE = "Tienda Ejemplo (3 de septiembre) (Recommended)"
        const val CHOICE_2 = "Electro Casa (28 de agosto)"
        const val CHOICE_3 = "No me acuerdo"
        const val MULTI_QUESTION = "¿Qué recados hago esta tarde?"
        const val FREE_QUESTION = "¿Para cuándo lo necesitas?"

        private fun string(id: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(id)

        private val title: String get() = string(R.string.clarify_title)
        private val recommended: String get() = string(R.string.clarify_recommended)
        private val done: String get() = string(R.string.clarify_done)
        private val micCd: String get() = string(R.string.clarify_mic_cd)
        private val sendCd: String get() = string(R.string.clarify_send_cd)
        private val answered: String get() = string(R.string.clarify_answered)
        private val sendFailed: String get() = string(R.string.clarify_send_failed)

        @JvmStatic
        @BeforeClass
        fun enableAccessibilityChecks() {
            AccessibilityChecks
                .enable()
                .setRunChecksFromRootView(true)
                .setThrowExceptionFor(AccessibilityCheckResult.AccessibilityCheckResultType.ERROR)
        }
    }
}
