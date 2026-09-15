package ai.hermes.mama.feature.chat.conversation

import ai.hermes.mama.core.storage.MamaDatabase
import ai.hermes.mama.core.storage.MessageEntity
import ai.hermes.mama.core.ui.components.ChatBubbleAuthor
import ai.hermes.mama.gateway.ConnectionManager
import ai.hermes.mama.testing.FakeGateway
import ai.hermes.mama.testing.FakeGatewayScript
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests del [ChatViewModel] (ROADMAP C4) contra el FakeGateway REAL por
 * WebSocket loopback — el cableado es el de producción de punta a punta:
 * `ConnectionManager` (B2) → `JsonRpcChannel`/`GatewayClient` (B1/B4) →
 * `SessionRepository`+Room in-memory (B6) → el VM de C4. Tiempo real.
 */
@RunWith(RobolectricTestRunner::class)
class ChatViewModelTest {
    private lateinit var gateway: FakeGateway
    private lateinit var manager: ConnectionManager
    private lateinit var db: MamaDatabase
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        db =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    MamaDatabase::class.java,
                ).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        runBlocking {
            if (::manager.isInitialized) {
                manager.disconnect()
            }
            scope.cancel()
            db.close()
            if (::gateway.isInitialized) {
                gateway.close()
            }
        }
    }

    /**
     * Conecta, crea un chat si el backend no tiene ninguno y monta el VM con
     * todos sus StateFlow observados (son `WhileSubscribed`).
     */
    private suspend fun bootChat(script: String): ChatViewModel {
        gateway = FakeGateway(FakeGatewayScript.load(script)).start()
        manager = scope.newManager(gateway)
        val generations = chatGenerations(manager, db, scope)
        val first = generations.first()
        val storedId =
            run {
                runCatching { first.repository.refreshList() }
                first.repository.chats
                    .first()
                    .firstOrNull()
                    ?.storedId
                    ?: first.repository.create(title = null).storedId
            }
        return ChatViewModel(
            storedId = storedId,
            generations = generations,
            scope = scope,
            connectionState = manager.state,
        ).also { it.watchIn(scope) }
    }

    // --- transcript + streaming ---

    @Test
    fun `deltas concatenan y el complete persiste la burbuja de Hermes`() =
        runBlocking {
            val vm = bootChat("deltas")
            vm.send("hola")

            // El texto vivo crece con los deltas antes de persistir.
            eventually { vm.liveText.value.takeIf { it.contains("Voy a contarte") } }

            // El complete persiste UNA burbuja con el texto acumulado.
            val assistant =
                eventually {
                    vm.items.value
                        .filterIsInstance<ChatListItem.Message>()
                        .firstOrNull { it.message.author == ChatBubbleAuthor.Hermes }
                        ?.message
                        ?.takeIf { it.text == DELTAS_FULL_TEXT }
                }
            assertFalse(assistant.isError)
            assertFalse(assistant.pending)

            // La fila user del servidor consumió al pendiente: exactamente una.
            val user =
                eventually {
                    vm.items.value
                        .filterIsInstance<ChatListItem.Message>()
                        .map { it.message }
                        .takeIf { messages -> messages.count { it.text == "hola" } == 1 }
                        ?.firstOrNull { it.text == "hola" }
                }
            assertFalse(user.pending)

            // Tras el complete no queda nada en vuelo.
            eventually { Unit.takeIf { !vm.liveStreaming.value && vm.liveText.value.isEmpty() } }
        }

    @Test
    fun `la burbuja optimista se funde con la fila user del servidor`() =
        runBlocking {
            val vm = bootChat("deltas")
            vm.send("mensaje optimista")

            // Optimista al instante (el submit aún no volvió).
            eventually {
                vm.items.value
                    .filterIsInstance<ChatListItem.Message>()
                    .firstOrNull { it.message.text == "mensaje optimista" && it.message.pending }
                    ?.message
            }

            // El history la confirma: una sola fila user, ya no pendiente.
            val user =
                eventually {
                    vm.items.value
                        .filterIsInstance<ChatListItem.Message>()
                        .map { it.message }
                        .takeIf { messages ->
                            messages.count { it.text == "mensaje optimista" } == 1 &&
                                messages.none { it.pending }
                        }?.firstOrNull { it.text == "mensaje optimista" }
                }
            assertEquals(ChatBubbleAuthor.User, user.author)
        }

    // --- actividad (tool.* / status.update / notice) ---

    @Test
    fun `el chip refleja la herramienta y el status el subtitulo`() =
        runBlocking {
            val vm = bootChat("deltas")
            val activities = mutableListOf<ActivityKind?>()
            val statuses = mutableListOf<String?>()
            scope.launch { vm.activity.collect { activities += it } }
            scope.launch { vm.header.collect { statuses += it.statusText } }

            vm.send("hola")

            // tool.start web_search → chip "Buscando en internet".
            eventually { activities.takeIf { ActivityKind.SearchWeb in it } }
            // status.update → subtítulo "Hermes está escribiendo…", luego se limpia.
            eventually { statuses.takeIf { "Hermes está escribiendo…" in it } }

            // Al cerrar el turno, chip y subtítulo quedan ocultos.
            eventually { Unit.takeIf { vm.activity.value == null && vm.header.value.statusText == null } }
        }

    @Test
    fun `message complete con error pinta burbuja de error y avisa`() =
        runBlocking {
            val vm = bootChat("error")
            val notices = mutableListOf<ChatNotice>()
            scope.launch { vm.notices.collect { notices += it } }

            vm.send("falla suavemente")

            val errorBubble =
                eventually {
                    vm.items.value
                        .filterIsInstance<ChatListItem.Message>()
                        .firstOrNull { it.message.isError }
                        ?.message
                }
            assertEquals(ChatBubbleAuthor.Hermes, errorBubble.author)
            val seen = eventually { notices.takeIf { ChatNotice.GatewayError in it } }
            assertTrue(seen.isNotEmpty())
        }

    @Test
    fun `submit rechazado marca la burbuja como fallida y reintentar la repite`() =
        runBlocking {
            val vm = bootChat("error")
            val notices = mutableListOf<ChatNotice>()
            scope.launch { vm.notices.collect { notices += it } }

            // "boom" → submit_error del guion: RPC error, sin turno.
            vm.send("boom")

            val failed =
                eventually {
                    vm.items.value
                        .filterIsInstance<ChatListItem.Message>()
                        .firstOrNull { it.message.failed }
                        ?.message
                }
            assertEquals("boom", failed.text)
            eventually { notices.takeIf { ChatNotice.SendFailed in it } }

            // Reintentar la misma burbuja vuelve a intentarlo (y vuelve a fallar).
            vm.retry(failed.key)
            eventually {
                vm.items.value
                    .filterIsInstance<ChatListItem.Message>()
                    .firstOrNull { it.message.failed }
                    ?.message
            }
            val retried = eventually { notices.takeIf { list -> list.count { it == ChatNotice.SendFailed } >= 2 } }
            assertTrue(retried.isNotEmpty())
        }

    // --- Parar / encolado ---

    @Test
    fun `parar llama a session interrupt y el segundo envio queda encolado`() =
        runBlocking {
            val vm = bootChat("lento")

            vm.send("primero")
            eventually { Unit.takeIf { vm.liveStreaming.value } }

            // Segundo submit con el turno vivo → el fake responde {"status":"queued"}.
            vm.send("segundo")
            eventually { Unit.takeIf { vm.header.value.queued } }

            vm.stop()
            eventually { Unit.takeIf { gateway.received("session.interrupt") } }
            eventually { Unit.takeIf { !vm.liveStreaming.value } }
        }

    // --- reconexión ---

    @Test
    fun `reconexion reabre el chat y resincroniza con events since`() =
        runBlocking {
            val vm = bootChat("lento")

            // "corta" → el guion emite start+delta y CIERRA el socket a mitad de turno.
            vm.send("corta")
            eventually {
                vm.items.value
                    .filterIsInstance<ChatListItem.Message>()
                    .firstOrNull { it.message.text == "corta" }
                    ?.message
            }

            // El manager reconecta solo (mismo FakeGateway, store intacto):
            // el VM reabre (resume), pide el hueco por seq y refresca history.
            eventually { Unit.takeIf { gateway.received("session.events.since") } }
            assertTrue(gateway.received("session.resume"))
            // El history va en una corrutina aparte: puede caer justo después del since.
            eventually { Unit.takeIf { gateway.received("session.history") } }

            // El chat sigue usable tras la reconexión: nuevo turno completo.
            vm.send("otra vuelta")
            eventually {
                vm.items.value
                    .filterIsInstance<ChatListItem.Message>()
                    .firstOrNull {
                        it.message.author == ChatBubbleAuthor.Hermes && it.message.text.contains("listo")
                    }?.message
            }

            // Sin duplicados por el replay: una fila user por envío.
            val userRows =
                vm.items.value
                    .filterIsInstance<ChatListItem.Message>()
                    .map { it.message }
                    .filter { it.author == ChatBubbleAuthor.User }
            assertEquals(1, userRows.count { it.text == "corta" })
            assertEquals(1, userRows.count { it.text == "otra vuelta" })
        }

    // --- modelo ---

    @Test
    fun `mensajes hidden y roles ajenos no se pintan`() {
        val hidden =
            MessageEntity(
                rowId = 1,
                chatId = "c",
                role = "assistant",
                text = "secreto",
                kind = "hidden",
            )
        val tool =
            MessageEntity(
                rowId = 2,
                chatId = "c",
                role = "tool",
                text = "tool out",
            )
        assertNull(hidden.toChatMessage())
        assertNull(tool.toChatMessage())
    }

    private companion object {
        const val DELTAS_FULL_TEXT = "Voy a contarte una cosa muy importante: todo va bien."
    }
}
