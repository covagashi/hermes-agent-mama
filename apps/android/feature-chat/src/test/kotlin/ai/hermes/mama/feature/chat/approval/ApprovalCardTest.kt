package ai.hermes.mama.feature.chat.approval

import ai.hermes.mama.core.ui.theme.MamaTheme
import ai.hermes.mama.feature.chat.R
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
 * Compose UI Test de la tarjeta de aprobación (C6): la pendiente renderiza la
 * acción + botones grandes, Sí/No llaman a sus callbacks, la respondida ya no
 * es clickable, el fallo de envío mantiene los botones vivos y la fuente 2.0×
 * no corta el contenido. `AccessibilityChecks` (espresso → ATF) rompe el test
 * ante violaciones de nivel ERROR.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp")
class ApprovalCardTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `tarjeta pendiente muestra accion y botones grandes`() {
        setCard(pendingState())

        composeRule.onNodeWithText(sendEmailTitle).assertIsDisplayed()
        composeRule.onNodeWithText(DETAIL).assertIsDisplayed()
        composeRule
            .onNodeWithText(yes)
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertHeightIsAtLeast(MIN_TOUCH_DP.dp)
        composeRule
            .onNodeWithText(no)
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertHeightIsAtLeast(MIN_TOUCH_DP.dp)
        composeRule.onNodeWithText(hint).assertIsDisplayed()
    }

    @Test
    fun `tap en Si llama a approve y tap en No a deny`() {
        var approved = 0
        var denied = 0
        setCard(pendingState(), onApprove = { approved++ }, onDeny = { denied++ })

        composeRule.onNodeWithText(yes).performClick()
        assertEquals(1, approved, "tap Sí debe llamar a approve")
        assertEquals(0, denied, "tap Sí no debe llamar a deny")

        composeRule.onNodeWithText(no).performClick()
        assertEquals(1, denied, "tap No debe llamar a deny")
    }

    @Test
    fun `tarjeta respondida muestra la eleccion y ya no es clickable`() {
        setCard(pendingState().copy(status = ApprovalStatus.Approved))

        composeRule.onNodeWithText(answeredYes).assertIsDisplayed()
        // Los botones desaparecen: nada clickable queda en la tarjeta
        // (C6: "respondida ya no es clickable").
        composeRule.onNodeWithText(yes).assertDoesNotExist()
        composeRule.onNodeWithText(no).assertDoesNotExist()
        assertTrue(
            composeRule.onAllNodes(hasClickAction()).fetchSemanticsNodes().isEmpty(),
            "la tarjeta respondida no debe conservar acciones de click",
        )
    }

    @Test
    fun `tarjeta denegada muestra la eleccion`() {
        setCard(pendingState().copy(status = ApprovalStatus.Denied))

        composeRule.onNodeWithText(answeredNo).assertIsDisplayed()
        composeRule.onNodeWithText(yes).assertDoesNotExist()
    }

    @Test
    fun `fallo de envio muestra el aviso y los botones siguen activos`() {
        var approved = 0
        setCard(pendingState().copy(status = ApprovalStatus.SendFailed), onApprove = { approved++ })

        composeRule.onNodeWithText(sendFailed).assertIsDisplayed()
        composeRule.onNodeWithText(yes).assertHasClickAction().performClick()
        assertEquals(1, approved, "el reintento debe volver a llamar a approve")
    }

    @Test
    @Config(sdk = [34], fontScale = 2.0f, qualifiers = "w390dp")
    fun `fuente 2x no corta la tarjeta`() {
        setCard(pendingState())

        // Con fuente ×2 la hoja puede superar la pantalla: el scroll deja
        // llegar a todo (nada cortado) y los botones conservan su tamaño.
        composeRule.onNodeWithText(sendEmailTitle).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(DETAIL).performScrollTo().assertIsDisplayed()
        composeRule
            .onNodeWithText(yes)
            .performScrollTo()
            .assertIsDisplayed()
            .assertHeightIsAtLeast(MIN_TOUCH_DP.dp)
        composeRule.onNodeWithText(no).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(hint).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `accesibilidad en pendiente y respondida`() {
        // setContent sólo puede llamarse una vez: el estado muta y recompone.
        val current = mutableStateOf(pendingState())
        composeRule.setContent {
            MamaTheme {
                ApprovalOverlay(state = current.value, onApprove = {}, onDeny = {})
            }
        }
        runEspressoA11yCheck()

        current.value = pendingState().copy(status = ApprovalStatus.Approved)
        composeRule.waitForIdle()
        runEspressoA11yCheck()

        current.value = pendingState().copy(status = ApprovalStatus.SendFailed)
        composeRule.waitForIdle()
        runEspressoA11yCheck()
    }

    // --- soporte ---

    private fun setCard(
        state: ApprovalCardState,
        onApprove: () -> Unit = {},
        onDeny: () -> Unit = {},
    ) {
        composeRule.setContent {
            MamaTheme {
                ApprovalOverlay(state = state, onApprove = onApprove, onDeny = onDeny)
            }
        }
    }

    private fun pendingState() =
        ApprovalCardState(
            key = "req-test",
            kind = ApprovalKind.SendEmail,
            detail = DETAIL,
            status = ApprovalStatus.Pending,
        )

    /**
     * AccessibilityChecks.enable() engancha ATF a Espresso: evaluar la raíz
     * recorre la jerarquía semántica y lanza ante violaciones ERROR.
     */
    private fun runEspressoA11yCheck() {
        onView(isRoot()).check(matches(isDisplayed()))
    }

    private companion object {
        const val MIN_TOUCH_DP = 56
        const val DETAIL = "Enviar un correo a Farmacia del Barrio"

        private fun string(id: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(id)

        private val sendEmailTitle: String get() = string(R.string.approval_kind_send_email)
        private val yes: String get() = string(R.string.approval_yes)
        private val no: String get() = string(R.string.approval_no)
        private val hint: String get() = string(R.string.approval_hint)
        private val answeredYes: String get() = string(R.string.approval_answered_yes)
        private val answeredNo: String get() = string(R.string.approval_answered_no)
        private val sendFailed: String get() = string(R.string.approval_send_failed)

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
