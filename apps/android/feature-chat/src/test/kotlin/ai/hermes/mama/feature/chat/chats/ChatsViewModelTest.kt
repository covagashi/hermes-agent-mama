package ai.hermes.mama.feature.chat.chats

import ai.hermes.mama.contract.SessionListResult
import ai.hermes.mama.contract.SessionListRow
import ai.hermes.mama.core.storage.MamaDatabase
import ai.hermes.mama.core.storage.OpenedChat
import ai.hermes.mama.gateway.ConnectionState
import ai.hermes.mama.gateway.JsonRpcChannel
import ai.hermes.mama.gateway.JsonRpcException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

/**
 * ChatsViewModel (C3) sobre repositorio real + [FakeSessionGateway] + Room en
 * memoria (la pila de B6): lista cacheada, franja de conexión, crear/abrir con
 * señal de navegación, borrado con confirmación, refresh y mundo "sin red".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class ChatsViewModelTest {
    // Unconfined y no Standard: Room emite desde un executor REAL — con un
    // dispatcher encolado, cada emisión queda pendiente en el scheduler
    // virtual hasta que alguien lo bombee, y bajo carga el sondeo expira con
    // la fila ya escrita (flake). Con Unconfined el colector de `uiState`
    // reanuda inline en el hilo que emite y `uiState.value` refleja Room en
    // cuanto la IO real termina — el `eventually` sólo espera IO de verdad.
    private val testDispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    private lateinit var db: MamaDatabase
    private lateinit var gateway: FakeSessionGateway
    private lateinit var connection: MutableStateFlow<ConnectionState>

    @Before
    fun setUp() {
        db =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    MamaDatabase::class.java,
                ).build()
        gateway = FakeSessionGateway()
        connection = MutableStateFlow(ConnectionState.Connected(channel = mockk<JsonRpcChannel>()))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `la lista muestra los chats del servidor con emoji y preview`() =
        runTest(testDispatcher) {
            gateway.onListSessions = { listResult(row("s-1", title = "Recetas", preview = "lentejas")) }
            val vm = newViewModel()
            advanceUntilIdle()

            val rows =
                eventually {
                    vm.uiState.value.chats
                        .takeIf { it.isNotEmpty() }
                }
            assertEquals("Recetas", rows[0].title)
            assertEquals("lentejas", rows[0].preview)
            assertEquals("🍲", rows[0].emoji)
            assertNull(vm.uiState.value.banner)
        }

    @Test
    fun `sin red la lista cacheada sigue visible y sale la franja`() =
        runTest(testDispatcher) {
            gateway.onListSessions = { listResult(row("s-1", title = "Correo")) }
            val vm = newViewModel()
            advanceUntilIdle()
            eventually {
                vm.uiState.value.chats
                    .takeIf { it.isNotEmpty() }
            }

            // El mundo "sin red": los RPC fallan y la conexión cae.
            gateway.failAll(JsonRpcException(-32000, "boom"))
            connection.value = ConnectionState.Disconnected

            // La franja viaja por connectionState → combine → stateIn: se
            // sondea hasta que aterriza (igual que el resto de aserciones de
            // estado de este archivo — uiState no se propaga en un solo tick).
            val state =
                eventually {
                    vm.uiState.value.takeIf { it.banner == ChatsBanner.Disconnected }
                }
            assertEquals(listOf("Correo"), state.chats.map { it.title })
        }

    @Test
    fun `la franja se esconde conectado o conectando y aparece en cada fallo`() =
        runTest(testDispatcher) {
            val vm = newViewModel()
            advanceUntilIdle()

            connection.value = ConnectionState.Connecting
            connection.value = ConnectionState.Reconnecting(attempt = 2, retryIn = 5.seconds)
            eventually { vm.uiState.value.takeIf { it.banner == ChatsBanner.Reconnecting } }

            connection.value = ConnectionState.Failed(cause = IllegalStateException("auth"))
            eventually { vm.uiState.value.takeIf { it.banner == ChatsBanner.Failed } }

            connection.value = ConnectionState.Disconnected
            eventually { vm.uiState.value.takeIf { it.banner == ChatsBanner.Disconnected } }

            connection.value = ConnectionState.Connected(channel = mockk())
            val settled = eventually { vm.uiState.value.takeIf { it.loaded && it.banner == null } }
            assertNull("conectado esconde la franja", settled.banner)
        }

    @Test
    fun `crear chat llama a session create con el titulo Chat de fecha y navega`() =
        runTest(testDispatcher) {
            val vm = newViewModel()
            advanceUntilIdle()

            // La señal es SharedFlow replay=0: hay que estar suscrito ANTES
            // del emit o se pierde (igual que los eventos del canal, §2.4).
            val nav = async { vm.navigation.first() }
            vm.createChat()
            advanceUntilIdle()

            val opened = nav.await()
            assertEquals(OpenedChat(storedId = "stored_new", runtimeId = "sess_new"), opened)
            assertTrue(
                "session.create debe llevar el título del día: ${gateway.calls}",
                gateway.calls.any { it == "create:Chat de prueba" },
            )
            // El draft localOnly aparece en la lista al instante (aunque
            // session.list aún no lo devuelva).
            val draft =
                eventually {
                    vm.uiState.value.chats
                        .firstOrNull { it.storedId == "stored_new" }
                }
            assertEquals("Chat de prueba", draft.title)
        }

    @Test
    fun `el chat nuevo sobrevive a un refresh que no lo lista (localOnly)`() =
        runTest(testDispatcher) {
            val vm = newViewModel()
            advanceUntilIdle()

            vm.createChat()
            advanceUntilIdle()
            eventually {
                vm.uiState.value.chats
                    .firstOrNull { it.storedId == "stored_new" }
            }

            // Refresh real: el servidor no devuelve el draft y aun así queda.
            gateway.onListSessions = { listResult(row("s-1", title = "Correo")) }
            vm.refresh()
            advanceUntilIdle()

            eventually {
                vm.uiState.value.chats
                    .takeIf { rows -> rows.any { it.storedId == "s-1" } }
            }
            assertTrue(
                "el draft localOnly no debe desaparecer tras el refresh",
                vm.uiState.value.chats
                    .any { it.storedId == "stored_new" },
            )
        }

    @Test
    fun `abrir un chat hace session resume por stored id y emite navegacion`() =
        runTest(testDispatcher) {
            gateway.onListSessions = { listResult(row("s-1", title = "Médico")) }
            val vm = newViewModel()
            advanceUntilIdle()
            val row =
                eventually {
                    vm.uiState.value.chats
                        .firstOrNull()
                }

            val nav = async { vm.navigation.first() }
            vm.openChat(row)
            advanceUntilIdle()

            val opened = nav.await()
            assertEquals(OpenedChat(storedId = "s-1", runtimeId = "sess_s-1"), opened)
            assertTrue("resume por stored id", gateway.calls.contains("resume:s-1"))
        }

    @Test
    fun `pedir borrado abre confirmacion y confirmar borra via repositorio`() =
        runTest(testDispatcher) {
            gateway.onListSessions = { listResult(row("s-1", title = "Factura")) }
            val vm = newViewModel()
            advanceUntilIdle()
            val row =
                eventually {
                    vm.uiState.value.chats
                        .firstOrNull()
                }

            vm.requestDelete(row)
            val pending = eventually { vm.uiState.value.pendingDelete }
            assertEquals(row, pending)

            vm.confirmDelete()
            eventually {
                vm.uiState.value.takeIf { it.pendingDelete == null }
            }

            eventually { gateway.calls.takeIf { calls -> calls.contains("delete:s-1") } }
            val remaining =
                eventually {
                    vm.uiState.value.chats
                        .takeIf { rows -> rows.none { it.storedId == "s-1" } }
                }
            assertTrue("la fila borrada sale de la lista", remaining.isEmpty())
        }

    @Test
    fun `conservar cierra el dialogo sin tocar el servidor`() =
        runTest(testDispatcher) {
            gateway.onListSessions = { listResult(row("s-1", title = "Factura")) }
            val vm = newViewModel()
            advanceUntilIdle()
            val row =
                eventually {
                    vm.uiState.value.chats
                        .firstOrNull()
                }

            vm.requestDelete(row)
            eventually { vm.uiState.value.pendingDelete }
            vm.dismissDelete()

            eventually {
                vm.uiState.value.takeIf { it.pendingDelete == null }
            }
            assertTrue(
                "ningún session.delete tras Conservar",
                gateway.calls.none { it.startsWith("delete:") },
            )
        }

    @Test
    fun `borrar un chat vivo hace close antes de delete`() =
        runTest(testDispatcher) {
            gateway.onListSessions = { listResult(row("s-1", title = "Factura")) }
            val vm = newViewModel()
            advanceUntilIdle()
            val row =
                eventually {
                    vm.uiState.value.chats
                        .firstOrNull()
                }

            // El chat queda vivo en el servidor tras abrirlo (resume → runtime id).
            val nav = async { vm.navigation.first() }
            vm.openChat(row)
            advanceUntilIdle()
            nav.await()

            vm.requestDelete(row)
            vm.confirmDelete()
            advanceUntilIdle()

            eventually { gateway.calls.takeIf { it.contains("delete:s-1") } }
            val close = gateway.calls.indexOfFirst { it == "close:sess_s-1" }
            val delete = gateway.calls.indexOfFirst { it == "delete:s-1" }
            assertTrue("close antes de delete (${gateway.calls})", close in 0 until delete)
        }

    @Test
    fun `refresh llama a session list y un fallo avisa por snackbar`() =
        runTest(testDispatcher) {
            val vm = newViewModel()
            // El refresco silencioso del arranque tiene que terminar antes de
            // romper el gateway (si no, `refresh` sale por el guard `refreshing`).
            eventually { vm.uiState.value.takeIf { it.loaded && !it.refreshing } }
            assertTrue("el arranque refresca", gateway.calls.contains("list"))

            gateway.onListSessions = { throw JsonRpcException(-32000, "caído") }
            val notice = async { vm.notices.first() }
            vm.refresh()
            advanceUntilIdle()

            assertEquals(ChatsNotice.RefreshFailed, notice.await())
        }

    @Test
    fun `un create que falla avisa y no navega`() =
        runTest(testDispatcher) {
            gateway.onCreateSession = { throw JsonRpcException(-32000, "caído") }
            val vm = newViewModel()
            advanceUntilIdle()

            val notice = async { vm.notices.first() }
            vm.createChat()
            advanceUntilIdle()

            assertEquals(ChatsNotice.CreateFailed, notice.await())
            // La señal de navegación no se emite: una primera recogida expira.
            assertNull(
                "sin navigation tras el fallo",
                withTimeoutOrNull(200) { vm.navigation.first() },
            )
        }

    private fun TestScope.newViewModel() =
        ChatsViewModel(
            repository = newRepository(gateway, db),
            connectionState = connection,
            nowMillis = { TEST_NOW_MILLIS },
            newChatTitle = { "Chat de prueba" },
        )

    private fun listResult(vararg rows: SessionListRow) = SessionListResult(sessions = rows.toList())

    private fun row(
        id: String,
        title: String,
        preview: String = "p-$id",
    ) = SessionListRow(id = id, title = title, preview = preview, startedAt = 100.0)
}
