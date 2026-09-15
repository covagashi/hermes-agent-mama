package ai.hermes.mama.feature.chat.conversation

import ai.hermes.mama.core.ui.components.ChatBubbleAuthor
import ai.hermes.mama.core.ui.theme.MamaTheme
import ai.hermes.mama.feature.chat.R
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val CHAT_TAG = "chat_screen"

/**
 * Capturas Roborazzi de la pantalla Chat (C4) al tamaño del mockup
 * (390 × 844 dp): transcript, streaming + chip, oscuro, errores y fuentes
 * grandes. Los PNG caen en `apps/android/screenshots/`
 * (`:feature-chat:recordRoborazzi`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp")
class ChatScreenScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun chatScreen_transcript_light() =
        capture("chat_screen_transcript_light_font100.png", darkTheme = false) { TranscriptScreen() }

    @Test
    fun chatScreen_streaming_light() =
        capture("chat_screen_streaming_light_font100.png", darkTheme = false) { StreamingScreen() }

    @Test
    fun chatScreen_activity_dark() =
        capture("chat_screen_activity_dark_font100.png", darkTheme = true) { StreamingScreen() }

    @Test
    fun chatScreen_errors_light() = capture("chat_screen_errors_light_font100.png", darkTheme = false) { ErrorScreen() }

    @Test
    @Config(sdk = [34], fontScale = 1.3f, qualifiers = "w390dp-h844dp")
    fun chatScreen_transcript_light_font130() =
        capture("chat_screen_transcript_light_font130.png", darkTheme = false) { TranscriptScreen() }

    @Test
    @Config(sdk = [34], fontScale = 2.0f, qualifiers = "w390dp-h844dp")
    fun chatScreen_streaming_light_font200() =
        capture("chat_screen_streaming_light_font200.png", darkTheme = false) { StreamingScreen() }

    private fun capture(
        fileName: String,
        darkTheme: Boolean,
        content: @Composable () -> Unit,
    ) {
        composeRule.setContent {
            MamaTheme(darkTheme = darkTheme) {
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.fillMaxSize().testTag(CHAT_TAG),
                ) {
                    content()
                }
            }
        }
        composeRule.onNodeWithTag(CHAT_TAG).captureRoboImage(fileName)
    }
}

// --- escenas fijas (datos del mockup: strings de ejemplo ficticios) ---

@Composable
private fun TranscriptScreen() {
    ChatContent(
        items =
            listOf(
                ChatListItem.DayHeader(key = "day-1", ts = 0.0),
                ChatListItem.Message(
                    ChatMessage(
                        key = "msg-1",
                        author = ChatBubbleAuthor.User,
                        text = stringResource(R.string.chat_example_user),
                    ),
                ),
                ChatListItem.Message(
                    ChatMessage(
                        key = "msg-2",
                        author = ChatBubbleAuthor.Hermes,
                        text = stringResource(R.string.chat_example_hermes),
                    ),
                ),
            ),
        header = ChatHeader(title = stringResource(R.string.chat_example_title)),
        activity = null,
        offline = false,
        streaming = false,
        liveText = MutableStateFlow(""),
        notices = emptyFlow(),
        onSend = {},
        onStop = {},
        onRetry = {},
        onBack = {},
    )
}

@Composable
private fun StreamingScreen() {
    ChatContent(
        items =
            listOf(
                ChatListItem.DayHeader(key = "day-1", ts = 0.0),
                ChatListItem.Message(
                    ChatMessage(
                        key = "msg-1",
                        author = ChatBubbleAuthor.User,
                        text = stringResource(R.string.chat_example_user),
                    ),
                ),
                ChatListItem.Message(
                    ChatMessage(
                        key = "msg-2",
                        author = ChatBubbleAuthor.Hermes,
                        text = stringResource(R.string.chat_example_hermes),
                    ),
                ),
            ),
        header = ChatHeader(title = stringResource(R.string.chat_example_title), streaming = true),
        activity = ActivityKind.Browse,
        offline = false,
        streaming = true,
        liveText = MutableStateFlow(stringResource(R.string.chat_example_stream)),
        notices = emptyFlow(),
        onSend = {},
        onStop = {},
        onRetry = {},
        onBack = {},
    )
}

@Composable
private fun ErrorScreen() {
    val notices = MutableSharedFlow<ChatNotice>(extraBufferCapacity = 1)
    notices.tryEmit(ChatNotice.GatewayError)
    ChatContent(
        items =
            listOf(
                ChatListItem.DayHeader(key = "day-1", ts = 0.0),
                ChatListItem.Message(
                    ChatMessage(
                        key = "msg-1",
                        author = ChatBubbleAuthor.Hermes,
                        text = "No pude terminar de buscarlo. Lo siento.",
                        isError = true,
                    ),
                ),
                ChatListItem.Message(
                    ChatMessage(
                        key = "pend-0",
                        author = ChatBubbleAuthor.User,
                        text = "¿Y la factura?",
                        failed = true,
                    ),
                ),
            ),
        header = ChatHeader(title = stringResource(R.string.chat_example_title)),
        activity = null,
        offline = false,
        streaming = false,
        liveText = MutableStateFlow(""),
        notices = notices,
        onSend = {},
        onStop = {},
        onRetry = {},
        onBack = {},
    )
}
