package ai.hermes.mama.feature.chat.conversation

import ai.hermes.mama.core.ui.components.ChatBubbleAuthor
import ai.hermes.mama.core.ui.theme.MamaTheme
import ai.hermes.mama.feature.chat.R
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.accessibility.AccessibilityChecks
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Compose UI Test de la pantalla Chat (C4): transcript + día, burbuja viva que
 * crece con `liveText` sin tocar el transcript, chip/Parar, composer, avisos y
 * la burbuja fallida con reintento. `AccessibilityChecks` (ATF) falla ante
 * violaciones ERROR.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp")
class ChatScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // --- transcript ---

    @Test
    fun `transcript con separador de dia y burbujas`() {
        setChat(items = sampleItems())

        composeRule.onNodeWithText(USER_TEXT).assertIsDisplayed()
        composeRule.onNodeWithText(HERMES_TEXT).assertIsDisplayed()
        composeRule.onNodeWithText(today).assertIsDisplayed()
        composeRule.onNodeWithText(TITLE).assertIsDisplayed()
    }

    @Test
    fun `chat vacio muestra la invitacion`() {
        setChat(items = emptyList())

        composeRule.onNodeWithText(string(R.string.chat_empty)).assertIsDisplayed()
    }

    // --- streaming ---

    @Test
    fun `la burbuja viva crece con liveText`() {
        val live = MutableStateFlow("")
        setChat(items = sampleItems(), streaming = true, liveText = live)

        // Vacío → el placeholder "…"; al llegar deltas se ve el texto.
        live.value = "Estoy miran"
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Estoy miran").assertIsDisplayed()

        live.value = "Estoy mirando tu correo"
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Estoy mirando tu correo").assertIsDisplayed()

        // El subtítulo marca "escribiendo" mientras hay turno.
        composeRule.onNodeWithText(string(R.string.chat_typing)).assertIsDisplayed()
    }

    // --- actividad y Parar ---

    @Test
    fun `chip de actividad y Parar llaman a su callback`() {
        var stopped = false
        setChat(items = sampleItems(), activity = ActivityKind.Browse, onStop = { stopped = true })

        composeRule.onNodeWithText(string(R.string.chat_activity_browser)).assertIsDisplayed()
        composeRule
            .onNodeWithText(string(R.string.chat_stop))
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        assertTrue(stopped)
    }

    @Test
    fun `sin actividad ni turno no hay franja de trabajo`() {
        setChat(items = sampleItems())

        composeRule.onNodeWithText(string(R.string.chat_stop)).assertDoesNotExist()
    }

    // --- composer ---

    @Test
    fun `composer envia el texto recortado y limpia el campo`() {
        val sent = mutableListOf<String>()
        setChat(items = sampleItems(), onSend = { sent += it })

        // Enviar deshabilitado con el campo vacío (objetivo ≥ 56 dp igualmente).
        composeRule
            .onNodeWithContentDescription(string(R.string.chat_send))
            .assertIsNotEnabled()
            .assertHeightIsAtLeast(56.dp)

        composeRule.onNode(hasSetTextAction()).performTextInput("  hola mamá  ")
        composeRule
            .onNodeWithContentDescription(string(R.string.chat_send))
            .assertIsEnabled()
            .performClick()

        assertEquals(listOf("hola mamá"), sent)
    }

    // --- avisos y fallos ---

    @Test
    fun `notice del backend pinta la franja`() {
        val notices = MutableSharedFlow<ChatNotice>(extraBufferCapacity = 1)
        setChat(items = sampleItems(), notices = notices)

        assertTrue(notices.tryEmit(ChatNotice.Info("Hermes terminó lo anterior")))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Hermes terminó lo anterior").assertIsDisplayed()
    }

    @Test
    fun `offline pinta la franja de conexion`() {
        setChat(items = sampleItems(), offline = true)

        composeRule.onNodeWithText(string(R.string.chat_offline)).assertIsDisplayed()
    }

    @Test
    fun `burbuja fallida reintenta al tocarla`() {
        val retried = mutableListOf<String>()
        val failed =
            ChatListItem.Message(
                ChatMessage(
                    key = "pend-0",
                    author = ChatBubbleAuthor.User,
                    text = "boom",
                    failed = true,
                ),
            )
        setChat(items = sampleItems() + failed, onRetry = { retried += it })

        composeRule.onNodeWithText(string(R.string.chat_retry)).assertIsDisplayed().performClick()
        assertEquals(listOf("pend-0"), retried)
    }

    @Test
    fun `burbuja de error usa el aviso amable`() {
        val errorItem =
            ChatListItem.Message(
                ChatMessage(
                    key = "msg-9",
                    author = ChatBubbleAuthor.Hermes,
                    text = "No pude terminar de buscarlo.",
                    isError = true,
                ),
            )
        setChat(items = sampleItems() + errorItem)

        composeRule.onNodeWithText("No pude terminar de buscarlo.").assertIsDisplayed()
    }

    // --- accesibilidad ---

    @Test
    fun `pantalla completa sin violaciones de accesibilidad`() {
        val live = MutableStateFlow("Escribiendo…")
        setChat(
            items = sampleItems(),
            streaming = true,
            activity = ActivityKind.SearchWeb,
            liveText = live,
        )

        // Evaluar la raíz recorre toda la jerarquía semántica (ATF).
        onView(isRoot()).check(matches(isDisplayed()))
    }

    // --- soporte ---

    private fun setChat(
        items: List<ChatListItem>,
        header: ChatHeader = ChatHeader(title = TITLE),
        activity: ActivityKind? = null,
        offline: Boolean = false,
        streaming: Boolean = false,
        liveText: MutableStateFlow<String> = MutableStateFlow(""),
        notices: Flow<ChatNotice> = MutableSharedFlow(),
        onSend: (String) -> Unit = {},
        onStop: () -> Unit = {},
        onRetry: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            MamaTheme {
                ChatContent(
                    items = items,
                    header = header,
                    activity = activity,
                    offline = offline,
                    streaming = streaming,
                    liveText = liveText,
                    notices = notices,
                    onSend = onSend,
                    onStop = onStop,
                    onRetry = onRetry,
                    onBack = {},
                )
            }
        }
    }

    private companion object {
        const val TITLE = "Factura de la lavadora"
        const val USER_TEXT = "Compré una lavadora y no encuentro la factura"
        const val HERMES_TEXT = "Voy a mirar en tu correo. Un momento."

        private fun string(id: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(id)

        private val today: String get() = string(R.string.chat_today)

        private fun sampleItems(): List<ChatListItem> =
            listOf(
                ChatListItem.DayHeader(key = "day-1", ts = 0.0),
                ChatListItem.Message(
                    ChatMessage(key = "msg-1", author = ChatBubbleAuthor.User, text = USER_TEXT),
                ),
                ChatListItem.Message(
                    ChatMessage(key = "msg-2", author = ChatBubbleAuthor.Hermes, text = HERMES_TEXT),
                ),
            )

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
