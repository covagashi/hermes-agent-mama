package ai.hermes.mama.feature.chat.chats

import ai.hermes.mama.core.ui.theme.MamaTheme
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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

private const val SCREEN_TAG = "chats_screen"

/** Ancho de los mockups de design/mockups (390 px). */
private val MockupWidth = 390.dp

/**
 * Capturas Roborazzi de la pantalla Chats (C3, mockup Main.dc.html): la lista
 * con los 4 chats del mockup, la franja de conexión y el vacío, en claro/
 * oscuro y fuente 1.0×/1.3×/2.0×. Los PNG caen en `apps/android/screenshots/`
 * (`:feature-chat:recordRoborazzi`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// w390dp: la pantalla de Robolectric es 320 dp por defecto y coaccionaría el
// Surface de MockupWidth — los goldens deben ser 390 dp reales.
@Config(sdk = [34], qualifiers = "w390dp-h844dp")
class ChatsScreenScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun chats_list_light() =
        capture("chats_list_light_font100.png", darkTheme = false) { Screen(state = sampleState()) }

    @Test
    fun chats_list_dark() = capture("chats_list_dark_font100.png", darkTheme = true) { Screen(state = sampleState()) }

    @Test
    @Config(sdk = [34], fontScale = 1.3f, qualifiers = "w390dp-h844dp")
    fun chats_list_light_font130() =
        capture("chats_list_light_font130.png", darkTheme = false) { Screen(state = sampleState()) }

    @Test
    @Config(sdk = [34], fontScale = 2.0f, qualifiers = "w390dp-h900dp")
    fun chats_list_light_font200() =
        capture("chats_list_light_font200.png", darkTheme = false) { Screen(state = sampleState()) }

    @Test
    @Config(sdk = [34], fontScale = 1.3f, qualifiers = "w390dp-h844dp")
    fun chats_list_dark_font130() =
        capture("chats_list_dark_font130.png", darkTheme = true) { Screen(state = sampleState()) }

    @Test
    @Config(sdk = [34], fontScale = 2.0f, qualifiers = "w390dp-h900dp")
    fun chats_list_dark_font200() =
        capture("chats_list_dark_font200.png", darkTheme = true) { Screen(state = sampleState()) }

    @Test
    fun chats_banner_light() =
        capture("chats_banner_light_font100.png", darkTheme = false) {
            Screen(state = sampleState().copy(banner = ChatsBanner.Reconnecting))
        }

    @Test
    fun chats_banner_dark() =
        capture("chats_banner_dark_font100.png", darkTheme = true) {
            Screen(state = sampleState().copy(banner = ChatsBanner.Reconnecting))
        }

    @Test
    fun chats_empty_light() =
        capture("chats_empty_light_font100.png", darkTheme = false) {
            Screen(state = ChatsUiState(loaded = true))
        }

    @Test
    fun chats_empty_dark() =
        capture("chats_empty_dark_font100.png", darkTheme = true) {
            Screen(state = ChatsUiState(loaded = true))
        }

    @Test
    fun chats_delete_dialog_light() =
        capture("chats_delete_dialog_light_font100.png", darkTheme = false) {
            Screen(state = sampleState().copy(pendingDelete = sampleState().chats[1]))
        }

    @Test
    fun chats_delete_dialog_dark() =
        capture("chats_delete_dialog_dark_font100.png", darkTheme = true) {
            Screen(state = sampleState().copy(pendingDelete = sampleState().chats[1]))
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
                            .testTag(SCREEN_TAG),
                ) {
                    content()
                }
            }
        }
        composeRule.onNodeWithTag(SCREEN_TAG).captureRoboImage(fileName)
    }
}

@Composable
private fun Screen(state: ChatsUiState) {
    ChatsContent(
        state = state,
        snackbarHostState = remember { SnackbarHostState() },
        onChatClick = {},
        onNewChat = {},
        onRefresh = {},
        onDeleteRequest = {},
        onDeleteConfirm = {},
        onDeleteDismiss = {},
    )
}
