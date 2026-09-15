package ai.hermes.mama.feature.chat.approval

import ai.hermes.mama.core.ui.theme.MamaTheme
import ai.hermes.mama.feature.chat.R
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val SHEET_TAG = "approval_sheet"

/** Ancho de los mockups de design/mockups (390 px). */
private val MockupWidth = 390.dp

/**
 * Capturas Roborazzi de la tarjeta de aprobación (C6): pendiente/aprobada/
 * denegada/fallo, en claro/oscuro y con fuente 1.0×/1.3×/2.0×. Los PNG caen en
 * `apps/android/screenshots/` (`:feature-chat:recordRoborazzi`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// w390dp: la pantalla de Robolectric es 320 dp por defecto y coaccionaría el
// Surface de MockupWidth — los goldens deben ser 390 dp reales.
@Config(sdk = [34], qualifiers = "w390dp")
class ApprovalCardScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun approvalCard_pending_light() =
        capture("approval_card_pending_light_font100.png", darkTheme = false) { PendingSheet() }

    @Test
    fun approvalCard_pending_dark() =
        capture("approval_card_pending_dark_font100.png", darkTheme = true) { PendingSheet() }

    @Test
    @Config(sdk = [34], fontScale = 1.3f)
    fun approvalCard_pending_light_font130() =
        capture("approval_card_pending_light_font130.png", darkTheme = false) { PendingSheet() }

    @Test
    // h800dp: la hoja entera cabe en la captura aunque supere la pantalla de
    // 414 px (el scroll para llegar a todo lo cubre el Compose UI Test).
    @Config(sdk = [34], fontScale = 2.0f, qualifiers = "w390dp-h800dp")
    fun approvalCard_pending_light_font200() =
        capture("approval_card_pending_light_font200.png", darkTheme = false) { PendingSheet() }

    @Test
    fun approvalCard_answered_yes_light() =
        capture("approval_card_answered_yes_light_font100.png", darkTheme = false) {
            Sheet(stateOf(ApprovalStatus.Approved))
        }

    @Test
    fun approvalCard_answered_no_light() =
        capture("approval_card_answered_no_light_font100.png", darkTheme = false) {
            Sheet(stateOf(ApprovalStatus.Denied))
        }

    @Test
    fun approvalCard_send_failed_light() =
        capture("approval_card_send_failed_light_font100.png", darkTheme = false) {
            Sheet(stateOf(ApprovalStatus.SendFailed))
        }

    private fun capture(
        fileName: String,
        darkTheme: Boolean,
        content: @Composable () -> Unit,
    ) {
        composeRule.setContent {
            MamaTheme(darkTheme = darkTheme) {
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    modifier =
                        Modifier
                            .width(MockupWidth)
                            .testTag(SHEET_TAG),
                ) {
                    content()
                }
            }
        }
        composeRule.onNodeWithTag(SHEET_TAG).captureRoboImage(fileName)
    }
}

@Composable
private fun stateOf(status: ApprovalStatus) =
    ApprovalCardState(
        kind = ApprovalKind.SendEmail,
        detail = stringResource(R.string.approval_example_detail),
        status = status,
    )

@Composable
private fun PendingSheet() {
    Sheet(state = stateOf(ApprovalStatus.Pending))
}

@Composable
private fun Sheet(state: ApprovalCardState) {
    ApprovalCard(state = state, onApprove = {}, onDeny = {})
}
