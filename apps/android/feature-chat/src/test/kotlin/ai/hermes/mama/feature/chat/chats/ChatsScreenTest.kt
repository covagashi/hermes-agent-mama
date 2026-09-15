package ai.hermes.mama.feature.chat.chats

import ai.hermes.mama.core.ui.theme.MamaTheme
import ai.hermes.mama.feature.chat.R
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
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
 * Compose UI Test de la pantalla Chats (C3, mockup Main.dc.html, 390 dp):
 * filas del mockup, botón "＋ Nuevo chat", swipe→confirmación, franja de
 * conexión, estado vacío, objetivos ≥ 56 dp, fuente 1.3×/2.0× y
 * `AccessibilityChecks` (espresso → ATF) sobre los estados principales.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp")
class ChatsScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `la lista muestra titulo preview hora y emoji de cada chat`() {
        setContent(state = sampleState())

        composeRule.onNodeWithText("Factura de la lavadora").assertIsDisplayed()
        composeRule.onNodeWithText("He guardado la factura en Descargas").assertIsDisplayed()
        composeRule.onNodeWithText("10:24").assertIsDisplayed()
        composeRule.onNodeWithText("🧾").assertIsDisplayed()
        composeRule.onNodeWithText("Ayer").assertIsDisplayed()
        // El punto verde lleva su significado para TalkBack.
        composeRule
            .onNodeWithContentDescription(running, substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun `tap en una fila pide abrir ese chat`() {
        var opened: ChatRowUi? = null
        setContent(state = sampleState(), onChatClick = { opened = it })

        composeRule.onNodeWithText("Correo").performClick()

        assertEquals("s-correo", opened?.storedId)
    }

    @Test
    fun `nuevo chat es un boton grande y llama a onNewChat`() {
        var created = 0
        setContent(state = sampleState(), onNewChat = { created++ })

        composeRule
            .onNodeWithText(newChat)
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertHeightIsAtLeast(MIN_TOUCH_DP.dp)
            .assertIsEnabled()
            .performClick()

        assertEquals(1, created)
    }

    @Test
    fun `mientras se crea el boton queda deshabilitado`() {
        setContent(state = sampleState().copy(creating = true))

        composeRule.onNodeWithText(newChat).assertIsNotEnabled()
    }

    @Test
    fun `deslizar una fila pide confirmacion antes de borrar`() {
        var requested: ChatRowUi? = null
        // El swipe dispara la petición; el estado refleja el diálogo abierto
        // (lo que el ViewModel haría con pendingDelete).
        val state = mutableStateOf(sampleState())
        composeRule.setContent {
            MamaTheme {
                Content(
                    state = state.value,
                    onDeleteRequest = {
                        requested = it
                        state.value = state.value.copy(pendingDelete = it)
                    },
                )
            }
        }

        composeRule.onNodeWithText("Correo").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertEquals("s-correo", requested?.storedId)
        composeRule.onNodeWithText(deleteTitle).assertIsDisplayed()
        // La confirmación lleva el título del chat en el cuerpo.
        composeRule.onNodeWithText("Se borrará «Correo»", substring = true).assertIsDisplayed()
        composeRule
            .onNodeWithText(deleteConfirm)
            .assertHasClickAction()
            .assertHeightIsAtLeast(MIN_TOUCH_DP.dp)
        composeRule.onNodeWithText(deleteCancel).assertIsDisplayed()
    }

    @Test
    fun `la accion accesible de borrar tambien pide confirmacion`() {
        var requested: ChatRowUi? = null
        val state = mutableStateOf(sampleState())
        composeRule.setContent {
            MamaTheme {
                Content(
                    state = state.value,
                    onDeleteRequest = {
                        requested = it
                        state.value = state.value.copy(pendingDelete = it)
                    },
                )
            }
        }

        // TalkBack no hace swipes: la fila lleva la acción "Borrar el chat X"
        // como customAction de semantics — se lee del nodo fusionado y se invoca.
        val node = composeRule.onNodeWithText("Correo").fetchSemanticsNode()
        val actions = node.config.getOrElse(SemanticsActions.CustomActions) { emptyList() }
        assertEquals(listOf("Borrar el chat Correo"), actions.map { it.label })

        actions.first().action()
        composeRule.waitForIdle()

        assertEquals("s-correo", requested?.storedId)
        composeRule.onNodeWithText(deleteTitle).assertIsDisplayed()
    }

    @Test
    fun `confirmar borrado llama a onDeleteConfirm y conservar no`() {
        var confirmed = 0
        var dismissed = 0
        val pending = sampleState().chats[0]
        // El estado se lee dentro de la composición: mutarlo recompone.
        val state = mutableStateOf(sampleState().copy(pendingDelete = pending))
        composeRule.setContent {
            MamaTheme {
                Content(
                    state = state.value,
                    onDeleteConfirm = {
                        confirmed++
                        state.value = state.value.copy(pendingDelete = null)
                    },
                    onDeleteDismiss = {
                        dismissed++
                        state.value = state.value.copy(pendingDelete = null)
                    },
                )
            }
        }

        composeRule.onNodeWithText(deleteConfirm).performClick()
        assertEquals(1, confirmed)
        composeRule.waitForIdle()

        state.value = sampleState().copy(pendingDelete = pending)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(deleteCancel).performClick()
        assertEquals(1, dismissed)
        assertEquals(1, confirmed, "confirmar sólo una vez")
    }

    @Test
    fun `la franja sale con cada estado de fallo y se esconde conectado`() {
        // El estado se lee dentro de la composición: mutarlo recompone.
        val state = mutableStateOf(sampleState())
        composeRule.setContent {
            MamaTheme { Content(state = state.value) }
        }

        // Sin banner: Connected/Connecting no pintan nada.
        composeRule.onNodeWithText(bannerReconnecting).assertDoesNotExist()

        state.value = sampleState().copy(banner = ChatsBanner.Reconnecting)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(bannerReconnecting).assertIsDisplayed()

        state.value = sampleState().copy(banner = ChatsBanner.Failed)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(bannerFailed).assertIsDisplayed()

        state.value = sampleState().copy(banner = ChatsBanner.Disconnected)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(bannerDisconnected).assertIsDisplayed()
    }

    @Test
    fun `sin chats se ve el estado vacio`() {
        setContent(state = ChatsUiState(loaded = true))

        composeRule.onNodeWithText(emptyTitle).assertIsDisplayed()
        composeRule.onNodeWithText(newChat).assertIsDisplayed()
    }

    @Test
    fun `la ayuda de la barra explica la pantalla`() {
        setContent(state = sampleState())

        composeRule.onNodeWithContentDescription(help).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(helpTitle).assertIsDisplayed()
        composeRule.onNodeWithText(helpOk).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(helpTitle).assertDoesNotExist()
    }

    @Test
    @Config(sdk = [34], fontScale = 1.3f, qualifiers = "w390dp")
    fun `fuente 1 3x mantiene todo legible`() {
        setContent(state = sampleState())

        composeRule.onNodeWithText("Factura de la lavadora").assertIsDisplayed()
        composeRule.onNodeWithText(newChat).assertIsDisplayed()
        composeRule
            .onNodeWithText(newChat)
            .assertHeightIsAtLeast(MIN_TOUCH_DP.dp)
    }

    @Test
    @Config(sdk = [34], fontScale = 2.0f, qualifiers = "w390dp-h800dp")
    fun `fuente 2x no corta la lista ni el boton`() {
        setContent(state = sampleState())

        // Con fuente ×2 la lista scrollea: todo sigue siendo alcanzable. El
        // botón está fijo abajo (no scrollea): basta con que se vea.
        composeRule.onNodeWithText("Cita del médico").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(newChat).assertIsDisplayed()
    }

    @Test
    fun `accesibilidad en lista vacio y dialogo`() {
        val state = mutableStateOf(sampleState())
        composeRule.setContent {
            MamaTheme { Content(state = state.value) }
        }
        runEspressoA11yCheck()

        state.value = ChatsUiState(loaded = true)
        composeRule.waitForIdle()
        runEspressoA11yCheck()

        state.value = sampleState().copy(pendingDelete = sampleState().chats[0])
        composeRule.waitForIdle()
        runEspressoA11yCheck()
    }

    // --- soporte ---

    private fun setContent(
        state: ChatsUiState,
        onChatClick: (ChatRowUi) -> Unit = {},
        onNewChat: () -> Unit = {},
        onDeleteRequest: (ChatRowUi) -> Unit = {},
        onDeleteConfirm: () -> Unit = {},
        onDeleteDismiss: () -> Unit = {},
    ) {
        composeRule.setContent {
            MamaTheme {
                Content(
                    state = state,
                    onChatClick = onChatClick,
                    onNewChat = onNewChat,
                    onDeleteRequest = onDeleteRequest,
                    onDeleteConfirm = onDeleteConfirm,
                    onDeleteDismiss = onDeleteDismiss,
                )
            }
        }
    }

    @Composable
    private fun Content(
        state: ChatsUiState,
        onChatClick: (ChatRowUi) -> Unit = {},
        onNewChat: () -> Unit = {},
        onDeleteRequest: (ChatRowUi) -> Unit = {},
        onDeleteConfirm: () -> Unit = {},
        onDeleteDismiss: () -> Unit = {},
    ) {
        ChatsContent(
            state = state,
            snackbarHostState = remember { SnackbarHostState() },
            onChatClick = onChatClick,
            onNewChat = onNewChat,
            onRefresh = {},
            onDeleteRequest = onDeleteRequest,
            onDeleteConfirm = onDeleteConfirm,
            onDeleteDismiss = onDeleteDismiss,
        )
    }

    /** AccessibilityChecks.enable() engancha ATF a Espresso: evaluar la raíz lanza ante errores. */
    private fun runEspressoA11yCheck() {
        onView(isRoot()).check(matches(isDisplayed()))
    }

    private companion object {
        const val MIN_TOUCH_DP = 56

        private fun string(id: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(id)

        private val newChat: String get() = string(R.string.chats_new_chat)
        private val emptyTitle: String get() = string(R.string.chats_empty_title)
        private val running: String get() = string(R.string.chats_running)
        private val help: String get() = string(R.string.chats_help_button)
        private val helpTitle: String get() = string(R.string.chats_help_title)
        private val helpOk: String get() = string(R.string.chats_help_ok)
        private val deleteTitle: String get() = string(R.string.chats_delete_title)
        private val deleteConfirm: String get() = string(R.string.chats_delete_confirm)
        private val deleteCancel: String get() = string(R.string.chats_delete_cancel)
        private val bannerReconnecting: String get() = string(R.string.chats_banner_reconnecting)
        private val bannerFailed: String get() = string(R.string.chats_banner_failed)
        private val bannerDisconnected: String get() = string(R.string.chats_banner_disconnected)

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

/** Los 4 chats del mockup como estado de UI (ids ficticios). */
internal fun sampleState(): ChatsUiState =
    ChatsUiState(
        loaded = true,
        chats =
            listOf(
                ChatRowUi(
                    storedId = "s-factura",
                    title = "Factura de la lavadora",
                    preview = "He guardado la factura en Descargas",
                    emoji = "🧾",
                    timeLabel = ChatTimeLabel.Today("10:24"),
                    running = true,
                ),
                ChatRowUi(
                    storedId = "s-correo",
                    title = "Correo",
                    preview = "Tienes 2 correos nuevos de la farmacia",
                    emoji = "📧",
                    timeLabel = ChatTimeLabel.Yesterday,
                    running = false,
                ),
                ChatRowUi(
                    storedId = "s-recetas",
                    title = "Recetas",
                    preview = "Lentejas con verduras: 40 minutos",
                    emoji = "🍲",
                    timeLabel = ChatTimeLabel.Weekday("Lun"),
                    running = false,
                ),
                ChatRowUi(
                    storedId = "s-medico",
                    title = "Cita del médico",
                    preview = "Miércoles 24 a las 9:30, lleva la tarjeta",
                    emoji = "🩺",
                    timeLabel = ChatTimeLabel.Date("3 sep"),
                    running = false,
                ),
            ),
    )
