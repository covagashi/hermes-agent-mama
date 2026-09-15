package ai.hermes.mama.feature.chat.clarify

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

private const val SHEET_TAG = "clarify_sheet"

/** Ancho de los mockups de design/mockups (390 px). */
private val MockupWidth = 390.dp

/**
 * Capturas Roborazzi de la tarjeta de pregunta (C7): opciones (claro/oscuro y
 * fuente 1.0×/1.3×/2.0×), multi_select, texto libre, respondida y fallo de
 * envío. Los PNG caen en `apps/android/screenshots/` (`:feature-chat:recordRoborazzi`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// w390dp: la pantalla de Robolectric es 320 dp por defecto y coaccionaría el
// Surface de MockupWidth — los goldens deben ser 390 dp reales. h800dp: la
// hoja con 3 opciones + campo supera los ~414 dp por defecto y el scroll la
// recortaría en la captura.
@Config(sdk = [34], qualifiers = "w390dp-h800dp")
class ClarifyCardScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun clarifyCard_choices_light() =
        capture("clarify_card_choices_light_font100.png", darkTheme = false) { ChoicesSheet() }

    @Test
    fun clarifyCard_choices_dark() =
        capture("clarify_card_choices_dark_font100.png", darkTheme = true) { ChoicesSheet() }

    @Test
    @Config(sdk = [34], fontScale = 1.3f)
    fun clarifyCard_choices_light_font130() =
        capture("clarify_card_choices_light_font130.png", darkTheme = false) { ChoicesSheet() }

    @Test
    // h800dp: la hoja entera cabe en la captura aunque supere la pantalla de
    // 414 px (el scroll para llegar a todo lo cubre el Compose UI Test).
    @Config(sdk = [34], fontScale = 2.0f, qualifiers = "w390dp-h800dp")
    fun clarifyCard_choices_light_font200() =
        capture("clarify_card_choices_light_font200.png", darkTheme = false) { ChoicesSheet() }

    @Test
    fun clarifyCard_multi_light() = capture("clarify_card_multi_light_font100.png", darkTheme = false) { MultiSheet() }

    @Test
    fun clarifyCard_multi_dark() = capture("clarify_card_multi_dark_font100.png", darkTheme = true) { MultiSheet() }

    @Test
    fun clarifyCard_free_text_light() =
        capture("clarify_card_free_text_light_font100.png", darkTheme = false) { FreeTextSheet() }

    @Test
    fun clarifyCard_free_text_dark() =
        capture("clarify_card_free_text_dark_font100.png", darkTheme = true) { FreeTextSheet() }

    @Test
    fun clarifyCard_answered_light() =
        capture("clarify_card_answered_light_font100.png", darkTheme = false) {
            Sheet(state = choicesState(status = ClarifyStatus.Answered))
        }

    @Test
    fun clarifyCard_send_failed_light() =
        capture("clarify_card_send_failed_light_font100.png", darkTheme = false) {
            Sheet(state = freeTextState(status = ClarifyStatus.SendFailed))
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

// --- soporte: estados de ejemplo (strings ficticios de strings_preguntas.xml) ---

@Composable
private fun choicesState(status: ClarifyStatus = ClarifyStatus.Pending) =
    ClarifyCardState(
        key = "req-shot",
        question = stringResource(R.string.clarify_example_question),
        input =
            ClarifyInput.Choices(
                options =
                    listOf(
                        ClarifyOption(
                            display = stringResource(R.string.clarify_example_choice_1),
                            wire = stringResource(R.string.clarify_example_choice_1),
                            recommended = true,
                        ),
                        ClarifyOption(
                            display = stringResource(R.string.clarify_example_choice_2),
                            wire = stringResource(R.string.clarify_example_choice_2),
                        ),
                        ClarifyOption(
                            display = stringResource(R.string.clarify_example_choice_3),
                            wire = stringResource(R.string.clarify_example_choice_3),
                        ),
                    ),
            ),
        progress = ClarifyProgress(current = 1, total = 2),
        status = status,
    )

@Composable
private fun multiState() =
    ClarifyCardState(
        key = "req-shot",
        question = stringResource(R.string.clarify_example_multi_question),
        input =
            ClarifyInput.MultiSelect(
                options =
                    listOf(
                        ClarifyOption(
                            display = stringResource(R.string.clarify_example_multi_1),
                            wire = stringResource(R.string.clarify_example_multi_1),
                        ),
                        ClarifyOption(
                            display = stringResource(R.string.clarify_example_multi_2),
                            wire = stringResource(R.string.clarify_example_multi_2),
                        ),
                        ClarifyOption(
                            display = stringResource(R.string.clarify_example_multi_3),
                            wire = stringResource(R.string.clarify_example_multi_3),
                        ),
                    ),
            ),
        progress = ClarifyProgress(current = 2, total = 3),
    )

@Composable
private fun freeTextState(status: ClarifyStatus = ClarifyStatus.Pending) =
    ClarifyCardState(
        key = "req-shot",
        question = stringResource(R.string.clarify_example_free_question),
        input = ClarifyInput.FreeText,
        status = status,
    )

@Composable
private fun ChoicesSheet() {
    Sheet(state = choicesState())
}

@Composable
private fun MultiSheet() {
    Sheet(state = multiState())
}

@Composable
private fun FreeTextSheet() {
    Sheet(state = freeTextState())
}

@Composable
private fun Sheet(state: ClarifyCardState) {
    ClarifyCard(state = state, onAnswer = {}, onAnswerMulti = {}, speech = null)
}
