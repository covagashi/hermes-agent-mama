package ai.hermes.mama.core.storage

import ai.hermes.mama.contract.EventTypes
import ai.hermes.mama.contract.SessionListResult
import ai.hermes.mama.contract.SessionListRow
import ai.hermes.mama.contract.TranscriptMessage
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * Lista de chats + eventos de sesión (§5/B6): `session.list`, `sessions.changed`,
 * `session.title`, `session.info` y la aceptación "sin red → última lista".
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SessionRepositoryListTest {
    private lateinit var db: MamaDatabase
    private lateinit var gateway: FakeSessionGateway

    @Before
    fun setUp() {
        db =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    MamaDatabase::class.java,
                ).build()
        gateway = FakeSessionGateway()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `refreshList actualiza la lista desde session punto list`() =
        runTest {
            gateway.onListSessions = {
                SessionListResult(
                    sessions =
                        listOf(
                            SessionListRow(
                                id = "s-1",
                                title = "Recetas",
                                preview = "hola",
                                startedAt = 100.0,
                                messageCount = 4,
                            ),
                            SessionListRow(
                                id = "s-2",
                                title = "Médico",
                                preview = "cita",
                                startedAt = 200.0,
                                messageCount = 2,
                            ),
                        ),
                )
            }
            val repo = newRepository(gateway, db)

            repo.refreshList()

            val chats = repo.chats.first()
            assertEquals(listOf("s-2", "s-1"), chats.map { it.storedId }) // startedAt DESC
            assertEquals("Médico", chats[0].title)
            assertEquals("cita", chats[0].preview)
            assertEquals(2L, chats[0].messageCount)
        }

    @Test
    fun `refreshList conserva runtimeId y borra los chats que el servidor ya no devuelve`() =
        runTest {
            gateway.onListSessions = { SessionListResult(sessions = listOf(row("s-1"), row("s-2"))) }
            gateway.onResumeSession = { stored -> resumeResult(runtimeId = "rt-1", storedId = stored) }
            val repo = newRepository(gateway, db)
            repo.refreshList()
            repo.open("s-1")
            val beforeRefresh = repo.chats.first()
            val ids = beforeRefresh.mapTo(mutableSetOf()) { it.storedId }
            assertEquals(setOf("s-1", "s-2"), ids)

            gateway.onListSessions = { SessionListResult(sessions = listOf(row("s-1"))) }
            repo.refreshList()

            val chats = repo.chats.first()
            assertEquals(listOf("s-1"), chats.map { it.storedId })
            assertEquals("rt-1", chats[0].runtimeId) // el mapeo vivo sobrevive al refresh
            val s2Messages = repo.messages("s-2").first()
            assertTrue(s2Messages.isEmpty())
        }

    @Test
    fun `sessions punto changed broadcast dispara un refresh`() =
        runTest {
            var listCalls = 0
            gateway.onListSessions = {
                listCalls += 1
                SessionListResult(sessions = listOf(row("s-9", title = "Nuevo")))
            }
            newRepository(gateway, db)

            // Broadcast: session_id == null (§2.4) — no debe filtrarse fuera.
            // Los colectores viven en backgroundScope: advanceUntilIdle() no los drena, runCurrent() sí.
            gateway.emit(EventTypes.SESSIONS_CHANGED, sessionId = null, payload = EMPTY_PAYLOAD)
            runCurrent()

            assertEquals(1, listCalls)
            val chat = eventually { db.chatDao().findByStoredId("s-9") }
            assertEquals("Nuevo", chat.title)
        }

    @Test
    fun `session punto title actualiza el título por runtimeId`() =
        runTest {
            gateway.onListSessions = { SessionListResult(sessions = listOf(row("s-1"))) }
            gateway.onResumeSession = { stored -> resumeResult(runtimeId = "rt-1", storedId = stored) }
            val repo = newRepository(gateway, db)
            repo.refreshList()
            repo.open("s-1")

            gateway.emit(
                EventTypes.SESSION_TITLE,
                sessionId = "rt-1",
                payload =
                    buildJsonObject {
                        put("session_id", "rt-1")
                        put("title", "Receta de lentejas")
                    },
            )

            // El chat ya tenía título ("t-s-1"): hay que esperar al nuevo valor exacto.
            val renamed =
                eventually {
                    db
                        .chatDao()
                        .findByStoredId("s-1")
                        ?.takeIf { it.title == "Receta de lentejas" }
                }
            assertEquals("Receta de lentejas", renamed.title)
        }

    @Test
    fun `session punto info reaprende el runtimeId a partir de stored_session_id`() =
        runTest {
            gateway.onListSessions = { SessionListResult(sessions = listOf(row("s-1"))) }
            val repo = newRepository(gateway, db)
            repo.refreshList()
            assertNull(db.chatDao().findByStoredId("s-1")?.runtimeId)

            gateway.emit(
                EventTypes.SESSION_INFO,
                sessionId = "rt-7",
                payload = buildJsonObject { put("stored_session_id", "s-1") },
            )

            val chat = eventually { db.chatDao().findByStoredId("s-1")?.takeIf { it.runtimeId != null } }
            assertEquals("rt-7", chat.runtimeId)
        }

    @Test
    fun `sin red se ven la última lista y el transcript cacheado`() =
        runTest {
            gateway.onListSessions = { SessionListResult(sessions = listOf(row("s-1"))) }
            gateway.onResumeSession = { stored ->
                resumeResult(
                    runtimeId = "rt-1",
                    storedId = stored,
                    messages =
                        listOf(
                            TranscriptMessage(role = "user", text = "¿qué pone aquí?"),
                            TranscriptMessage(role = "assistant", text = "una receta"),
                        ),
                )
            }
            val repo = newRepository(gateway, db)
            repo.refreshList()
            repo.open("s-1")

            gateway.failAll(IOException("sin red"))
            // Un repo "nuevo" (app reabierta sin red) lee lo mismo de Room.
            val offlineRepo = newRepository(gateway, db)

            assertEquals(listOf("s-1"), offlineRepo.chats.first().map { it.storedId })
            assertEquals(
                listOf("¿qué pone aquí?", "una receta"),
                offlineRepo.messages("s-1").first().map { it.text },
            )
        }
}
