package ai.hermes.mama.feature.chat.chats

import ai.hermes.mama.contract.SessionListResult
import ai.hermes.mama.contract.SessionListRow
import ai.hermes.mama.core.storage.MamaDatabase
import ai.hermes.mama.core.storage.OpenedChat
import ai.hermes.mama.core.storage.SessionRepository
import ai.hermes.mama.core.ui.theme.MamaTheme
import ai.hermes.mama.feature.chat.R
import ai.hermes.mama.gateway.ConnectionState
import android.content.Context
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests instrumentados de la pantalla Chats (C3, emulador API 35): la pantalla
 * real sobre [SessionRepository] + Room en memoria + [FakeSessionGateway] en
 * proceso — sin red. Cubre la aceptación del roadmap: crear navega vía la
 * señal mock, borrar pide confirmación y sin red la caché sigue visible con
 * franja.
 */
@RunWith(AndroidJUnit4::class)
class ChatsScreenInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ChatsTestActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var db: MamaDatabase? = null

    private val gateway = FakeSessionGateway()
    private val connection = MutableStateFlow<ConnectionState>(ConnectionState.Connecting)

    @Volatile
    private var navigated: OpenedChat? = null

    @After
    fun tearDown() {
        db?.close()
    }

    @Test
    fun crear_un_chat_navega_via_la_senal_de_navegacion() {
        setChatsContent()

        composeRule.onNodeWithText(string(R.string.chats_new_chat)).performClick()

        composeRule.waitUntil(timeoutMillis = WAIT_MS) { navigated != null }
        assertEquals(
            "la señal lleva los dos ids del chat creado",
            OpenedChat(storedId = "stored_new", runtimeId = "sess_new"),
            navigated,
        )
        assertTrue(
            "session.create salió con el título del día",
            gateway.calls.any { it.startsWith("create:Chat de") },
        )
    }

    @Test
    fun borrar_un_chat_pide_confirmacion_antes_de_tocar_el_servidor() {
        gateway.onListSessions = { listResult() }
        setChatsContent()
        awaitChat(FACTURA_TITLE)

        // Swipe → confirmación; el servidor todavía no ha visto un delete.
        composeRule.onNodeWithText(FACTURA_TITLE).performTouchInput { swipeLeft() }
        composeRule.waitUntil(timeoutMillis = WAIT_MS) { isDialogShown() }
        composeRule.onNodeWithText(string(R.string.chats_delete_title)).assertIsDisplayed()
        assertTrue(
            "sin session.delete antes de confirmar",
            gateway.calls.none { it.startsWith("delete:") },
        )

        composeRule.onNodeWithText(string(R.string.chats_delete_confirm)).performClick()

        composeRule.waitUntil(timeoutMillis = WAIT_MS) {
            gateway.calls.any { it == "delete:demo_factura" }
        }
        composeRule.waitUntil(timeoutMillis = WAIT_MS) { !isChatShown(FACTURA_TITLE) }
    }

    @Test
    fun conservar_en_el_dialogo_de_borrado_no_toca_el_servidor() {
        gateway.onListSessions = { listResult() }
        setChatsContent()
        awaitChat(FACTURA_TITLE)

        composeRule.onNodeWithText(FACTURA_TITLE).performTouchInput { swipeLeft() }
        composeRule.waitUntil(timeoutMillis = WAIT_MS) { isDialogShown() }

        composeRule.onNodeWithText(string(R.string.chats_delete_cancel)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(string(R.string.chats_delete_title)).assertDoesNotExist()
        assertTrue(
            "sin session.delete tras Conservar",
            gateway.calls.none { it.startsWith("delete:") },
        )
        composeRule.onNodeWithText(FACTURA_TITLE).assertIsDisplayed()
    }

    @Test
    fun sin_red_la_lista_cacheada_sigue_visible_y_sale_la_franja() {
        gateway.onListSessions = { listResult() }
        setChatsContent()
        awaitChat(FACTURA_TITLE)

        // El mundo "sin red": los RPC caen y la conexión pasa a Disconnected.
        gateway.failAll(IllegalStateException("sin red"))
        connection.value = ConnectionState.Disconnected

        composeRule.waitUntil(timeoutMillis = WAIT_MS) {
            isTextShown(string(R.string.chats_banner_disconnected))
        }
        composeRule.onNodeWithText(string(R.string.chats_banner_disconnected)).assertIsDisplayed()
        // La caché sigue pintada: los chats no desaparecen con la conexión.
        composeRule.onNodeWithText(FACTURA_TITLE).assertIsDisplayed()
        composeRule.onNodeWithText("Correo").assertIsDisplayed()
    }

    @Test
    fun tap_en_una_fila_navega_via_la_senal_de_navegacion() {
        gateway.onListSessions = { listResult() }
        setChatsContent()
        awaitChat(FACTURA_TITLE)

        composeRule.onNodeWithText(FACTURA_TITLE).performClick()

        composeRule.waitUntil(timeoutMillis = WAIT_MS) { navigated != null }
        assertEquals("demo_factura", navigated?.storedId)
        assertEquals("sess_demo_factura", navigated?.runtimeId)
    }

    // --- soporte ---

    /**
     * Monta la pantalla real: repo (Room en memoria) + VM dentro de la
     * composición para que el scope viva lo que la pantalla.
     */
    private fun setChatsContent() {
        navigated = null
        composeRule.setContent {
            val scope = rememberCoroutineScope()
            val appContext = LocalContext.current.applicationContext
            val database =
                remember {
                    Room
                        .inMemoryDatabaseBuilder(appContext, MamaDatabase::class.java)
                        .build()
                        .also { db = it }
                }
            val repository =
                remember {
                    SessionRepository(
                        gateway = gateway,
                        db = database,
                        scope = scope,
                    )
                }
            val viewModel =
                remember {
                    ChatsViewModel(
                        repository = repository,
                        connectionState = connection,
                        newChatTitle = { "Chat de hoy" },
                    )
                }
            MamaTheme {
                ChatsScreen(
                    viewModel = viewModel,
                    onOpenChat = { opened -> navigated = opened },
                )
            }
        }
    }

    /** Espera a que la fila esté pintada (Room escribe en su propio executor). */
    private fun awaitChat(title: String) {
        composeRule.waitUntil(timeoutMillis = WAIT_MS) { isChatShown(title) }
        composeRule.onNodeWithText(title).assertIsDisplayed()
    }

    private fun isChatShown(title: String): Boolean = isTextShown(title)

    private fun isDialogShown(): Boolean = isTextShown(string(R.string.chats_delete_title))

    private fun isTextShown(text: String): Boolean =
        runCatching {
            composeRule
                .onNodeWithText(text)
                .assertIsDisplayed()
        }.isSuccess

    private fun string(id: Int): String = context.getString(id)

    private fun listResult() =
        SessionListResult(
            sessions =
                listOf(
                    SessionListRow(
                        id = "demo_factura",
                        title = FACTURA_TITLE,
                        preview = "He guardado la factura en Descargas",
                        startedAt = System.currentTimeMillis() / MILLIS_PER_SECOND - HOUR_SECONDS,
                        messageCount = 6,
                    ),
                    SessionListRow(
                        id = "demo_correo",
                        title = "Correo",
                        preview = "Tienes 2 correos nuevos de la farmacia",
                        startedAt = System.currentTimeMillis() / MILLIS_PER_SECOND - DAY_SECONDS,
                        messageCount = 3,
                    ),
                ),
        )

    private companion object {
        const val WAIT_MS = 10_000L
        const val FACTURA_TITLE = "Factura de la lavadora"
        const val MILLIS_PER_SECOND = 1_000.0
        const val HOUR_SECONDS = 3_600.0
        const val DAY_SECONDS = 86_400.0
    }
}
