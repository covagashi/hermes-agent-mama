package ai.hermes.mama.feature.chat.conversation

import ai.hermes.mama.core.storage.MamaDatabase
import ai.hermes.mama.core.storage.SessionGateway
import ai.hermes.mama.core.storage.SessionRepository
import ai.hermes.mama.core.ui.theme.MamaTheme
import ai.hermes.mama.feature.chat.R
import ai.hermes.mama.gateway.ConnectParams
import ai.hermes.mama.gateway.ConnectionManager
import ai.hermes.mama.gateway.ConnectionState
import ai.hermes.mama.gateway.GatewayClient
import ai.hermes.mama.testing.FakeGateway
import ai.hermes.mama.testing.FakeGatewayScript
import android.content.Context
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToLog
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "C4ChatTest"

/**
 * Test instrumentado de C4 (emulador API 35): la pantalla Chat contra el
 * FakeGateway **en proceso** (`:testing`) por `ws://127.0.0.1` — el cableado
 * es el de producción: `ConnectionManager` → `JsonRpcChannel`/`GatewayClient`
 * → `SessionRepository` + Room → `ChatViewModel` → `ChatScreen`.
 *
 * El guion `c4_chat` emite `status.update`, deltas, un `tool.start` de
 * `web_search` (ventana de 1,2 s) y `message.complete`.
 */
@RunWith(AndroidJUnit4::class)
class ChatScreenInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var gateway: FakeGateway
    private lateinit var manager: ConnectionManager
    private lateinit var db: MamaDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setUp() {
        gateway = FakeGateway(FakeGatewayScript.load("c4_chat")).start()
        db =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    MamaDatabase::class.java,
                ).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        manager =
            ConnectionManager(
                scope = scope,
                client = OkHttpClient(),
                onBeforeConnect = { ConnectParams(url = gateway.wsUrl) },
            ).also { it.connect() }

        // `shareIn` (revisor): el flow frío creaba un repo+client POR colector —
        // `first()` dejaba un repo zombi colectando `channel.events` y escribiendo
        // Room en paralelo al repo que liga el VM.
        val generations = chatGenerations(manager, db, scope)
        val generation = runBlocking { generations.first() }
        val storedId = runBlocking { generation.repository.create(title = null).storedId }
        viewModel =
            ChatViewModel(
                storedId = storedId,
                generations = generations,
                scope = scope,
                connectionState = manager.state,
                logger = { Log.w(TAG, it) },
            )
        scope.launch { viewModel.notices.collect { notice -> Log.w(TAG, "notice: $notice") } }
        composeRule.setContent {
            MamaTheme {
                ChatScreen(viewModel = viewModel, onBack = {})
            }
        }
    }

    @After
    fun tearDown() {
        viewModel.close()
        runBlocking { manager.disconnect() }
        scope.cancel()
        db.close()
        gateway.close()
    }

    @Test
    fun streaming_chip_y_complete_se_ven_en_pantalla() {
        // La usuaria escribe y envía. El botón queda deshabilitado hasta que
        // el texto llega al estado — un click inmediato puede ser no-op.
        composeRule.onNode(hasSetTextAction()).performTextInput("hola")
        composeRule.waitUntil(timeoutMillis = WAIT_MS) {
            runCatching {
                composeRule
                    .onNodeWithContentDescription(string(R.string.chat_send))
                    .assertIsEnabled()
            }.isSuccess
        }
        // `performSemanticsAction` invoca el OnClick directamente: `performClick`
        // inyecta un gesto por coordenadas y con el IME abierto tras teclear el
        // botón queda con bounds vacíos o cubiertos en el emulador de CI.
        composeRule
            .onNodeWithContentDescription(string(R.string.chat_send))
            .performSemanticsAction(SemanticsActions.OnClick)

        // Burbuja de la usuaria (optimista → fila real tras el submit).
        try {
            waitForText("hola")
        } catch (e: ComposeTimeoutException) {
            composeRule.onRoot().printToLog(TAG)
            throw AssertionError(
                "burbuja 'hola' nunca apareció: items=${viewModel.items.value} " +
                    "streaming=${viewModel.liveStreaming.value} " +
                    "offline=${viewModel.offline.value} header=${viewModel.header.value}",
                e,
            )
        }

        // Subtítulo "escribiendo" y chip de actividad durante el turno.
        try {
            waitForText(string(R.string.chat_typing))
        } catch (e: ComposeTimeoutException) {
            throw AssertionError(
                "typing nunca apareció: streaming=${viewModel.liveStreaming.value} " +
                    "activity=${viewModel.activity.value} offline=${viewModel.offline.value} " +
                    "items=${viewModel.items.value.size} header=${viewModel.header.value}",
                e,
            )
        }
        waitForText(string(R.string.chat_activity_web))

        // El texto fluye y el complete deja la burbuja final.
        waitForText(FINAL_TEXT)
        composeRule.onNodeWithText(FINAL_TEXT).assertIsDisplayed()

        // Turno cerrado: ni chip ni "escribiendo" quedan visibles.
        waitUntilGone(string(R.string.chat_activity_web))
        waitUntilGone(string(R.string.chat_typing))
    }

    // --- helpers ---

    private fun nodeExists(text: String): Boolean =
        composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun waitForText(
        text: String,
        timeoutMs: Long = WAIT_MS,
    ) {
        composeRule.waitUntil(timeoutMs) { nodeExists(text) }
    }

    private fun waitUntilGone(
        text: String,
        timeoutMs: Long = WAIT_MS,
    ) {
        composeRule.waitUntil(timeoutMs) { !nodeExists(text) }
    }

    private companion object {
        const val WAIT_MS = 15_000L
        const val FINAL_TEXT = "Estoy buscando tu factura."

        private fun string(id: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(id)
    }
}

/**
 * Una [ChatGeneration] por canal `Connected` — la misma fusión que `DevChatHost`,
 * pero `shareIn` la hace caliente: el `map` (que crea repo+client y abre el
 * colector de eventos) corre UNA vez por canal, no por colector.
 */
private fun chatGenerations(
    manager: ConnectionManager,
    db: MamaDatabase,
    scope: CoroutineScope,
): Flow<ChatGeneration> =
    manager.state
        .map { state -> (state as? ConnectionState.Connected)?.channel }
        .distinctUntilChanged()
        .filterNotNull()
        .map { channel ->
            val client = GatewayClient(channel = channel, scope = scope)
            ChatGeneration(
                repository =
                    SessionRepository(
                        gateway = SessionGateway.from(client),
                        db = db,
                        scope = scope,
                        logger = { Log.w(TAG, "repo: $it") },
                    ),
                client = client,
            )
        }.shareIn(scope, SharingStarted.Eagerly, replay = 1)
