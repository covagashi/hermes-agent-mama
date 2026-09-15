package ai.hermes.mama.core.storage

import ai.hermes.mama.contract.EventTypes
import ai.hermes.mama.contract.SessionCreateResult
import ai.hermes.mama.contract.SessionHistoryResult
import ai.hermes.mama.contract.SessionLiveInfo
import ai.hermes.mama.contract.TranscriptMessage
import ai.hermes.mama.gateway.JsonRpcException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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

/**
 * Ciclo de vida de un chat + streaming (§5/B6): `open` (resume → runtime id),
 * `history`, `create`, `rename`, `delete` y la escritura incremental de
 * `message.*` (memoria hasta `message.complete`, que persiste).
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SessionRepositoryChatTest {
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
    fun `open mapea stored a runtime y cachea el transcript`() =
        runTest {
            gateway.onResumeSession = { stored ->
                resumeResult(
                    runtimeId = "rt-1",
                    storedId = stored,
                    messages =
                        listOf(
                            TranscriptMessage(role = "user", text = "hola", timestamp = 1.0, rowId = 1),
                            TranscriptMessage(role = "assistant", text = "buenas", timestamp = 2.0, rowId = 2),
                        ),
                )
            }
            val repo = newRepository(gateway, db)

            val opened = repo.open("s-1")

            assertEquals(OpenedChat(storedId = "s-1", runtimeId = "rt-1"), opened)
            assertEquals("rt-1", db.chatDao().findByStoredId("s-1")?.runtimeId)
            val cached = repo.messages("s-1").first()
            assertEquals(listOf("hola", "buenas"), cached.map { it.text })
            assertEquals(listOf("user", "assistant"), cached.map { it.role })
            assertEquals(listOf(1L, 2L), cached.map { it.remoteRowId })
        }

    @Test
    fun `open canonicaliza por session punto key cuando no hay stored_session_id`() =
        runTest {
            // Los caminos reales de resume devuelven session_key (la punta del
            // linaje de compresión), no stored_session_id.
            gateway.onResumeSession = {
                resumeResult(
                    runtimeId = "rt-9",
                    storedId = "s-old",
                    sessionKey = "s-tip",
                    storedSessionId = null,
                )
            }
            val repo = newRepository(gateway, db)

            val opened = repo.open("s-old")

            assertEquals(OpenedChat(storedId = "s-tip", runtimeId = "rt-9"), opened)
            assertNull(db.chatDao().findByStoredId("s-old"))
            assertEquals("rt-9", db.chatDao().findByStoredId("s-tip")?.runtimeId)
        }

    @Test
    fun `open reintenta una vez ante un 4007 transitorio`() =
        runTest {
            var resumeCalls = 0
            gateway.onResumeSession = { stored ->
                resumeCalls += 1
                if (resumeCalls == 1) {
                    // _reattach_refusal: la sesión se fue entre el locate y el attach.
                    throw JsonRpcException(4007, "session no longer live; retry resume")
                }
                resumeResult(runtimeId = "rt-1", storedId = stored)
            }
            val repo = newRepository(gateway, db)

            val opened = repo.open("s-1")

            assertEquals(OpenedChat(storedId = "s-1", runtimeId = "rt-1"), opened)
            assertEquals(2, resumeCalls)
        }

    @Test
    fun `open concurrente sobre el mismo chat serializa los resume`() =
        runTest {
            var inFlight = 0
            var maxInFlight = 0
            val gate = CompletableDeferred<Unit>()
            gateway.onResumeSession = { stored ->
                inFlight += 1
                maxInFlight = maxOf(maxInFlight, inFlight)
                gate.await()
                inFlight -= 1
                resumeResult(runtimeId = "rt-1", storedId = stored)
            }
            val repo = newRepository(gateway, db)

            val first = async { repo.open("s-1") }
            runCurrent()
            val second = async { repo.open("s-1") }
            runCurrent()
            gate.complete(Unit)

            assertEquals(OpenedChat(storedId = "s-1", runtimeId = "rt-1"), first.await())
            assertEquals(OpenedChat(storedId = "s-1", runtimeId = "rt-1"), second.await())
            assertEquals(1, maxInFlight) // el Mutex por storedId serializó los resume
        }

    @Test
    fun `open propaga el running del result al chat`() =
        runTest {
            gateway.onResumeSession = { stored ->
                resumeResult(runtimeId = "rt-1", storedId = stored, running = true)
            }
            val repo = newRepository(gateway, db)

            repo.open("s-1")

            assertEquals(true, db.chatDao().findByStoredId("s-1")?.running)
        }

    @Test
    fun `history recarga el transcript por runtime id`() =
        runTest {
            gateway.onResumeSession = { stored -> resumeResult(runtimeId = "rt-1", storedId = stored) }
            gateway.onSessionHistory = { runtime ->
                assertEquals("rt-1", runtime) // history va por runtime id, no por stored
                SessionHistoryResult(
                    count = 1,
                    messages = listOf(TranscriptMessage(role = "assistant", text = "refrescado", rowId = 7)),
                )
            }
            val repo = newRepository(gateway, db)
            repo.open("s-1")

            val count = repo.history("s-1")

            assertEquals(1L, count)
            assertEquals(listOf("refrescado"), repo.messages("s-1").first().map { it.text })
        }

    @Test
    fun `history con runtimeId obsoleto reabre por storedId y reintenta`() =
        runTest {
            var resumeCalls = 0
            gateway.onResumeSession = { stored ->
                resumeCalls += 1
                resumeResult(runtimeId = "rt-$resumeCalls", storedId = stored)
            }
            var historyCalls = 0
            gateway.onSessionHistory = {
                historyCalls += 1
                if (historyCalls == 1) {
                    throw JsonRpcException(4001, "session not found") // runtime id obsoleto
                }
                SessionHistoryResult(count = 0, messages = emptyList())
            }
            val repo = newRepository(gateway, db)
            repo.open("s-1") // cachea rt-1; luego el gateway "se reinicia" y rt-1 ya no existe allí

            repo.history("s-1")

            assertEquals(2, historyCalls) // falló con rt-1, reabrió (rt-2) y repitió
            assertEquals(2, resumeCalls)
            assertEquals("rt-2", db.chatDao().findByStoredId("s-1")?.runtimeId)
        }

    @Test
    fun `create inserta el chat con stored y runtime id`() =
        runTest {
            gateway.onCreateSession = {
                SessionCreateResult(
                    sessionId = "rt-new",
                    storedSessionId = "s-new",
                    messageCount = 0,
                    messages = emptyList(),
                    info = SessionLiveInfo(),
                )
            }
            val repo = newRepository(gateway, db)

            val opened = repo.create("Chat de hoy")

            assertEquals(OpenedChat(storedId = "s-new", runtimeId = "rt-new"), opened)
            assertTrue(gateway.calls.contains("create:Chat de hoy"))
            val chat = db.chatDao().findByStoredId("s-new")
            assertEquals("rt-new", chat?.runtimeId)
            assertEquals("Chat de hoy", chat?.title)
            // El backend no persiste la fila hasta el primer prompt → draft local.
            assertEquals(true, chat?.localOnly)
        }

    @Test
    fun `rename renombra remoto por runtimeId y actualiza el título local`() =
        runTest {
            gateway.onResumeSession = { stored -> resumeResult(runtimeId = "rt-1", storedId = stored) }
            val repo = newRepository(gateway, db)
            repo.open("s-1")

            repo.rename("s-1", "Facturas")

            assertTrue(gateway.calls.contains("rename:rt-1:Facturas")) // remoto por runtime id
            assertEquals("Facturas", db.chatDao().findByStoredId("s-1")?.title)
        }

    @Test
    fun `delete cierra la sesión viva antes de borrar remoto y local`() =
        runTest {
            gateway.onResumeSession = { stored ->
                resumeResult(
                    runtimeId = "rt-1",
                    storedId = stored,
                    messages = listOf(TranscriptMessage(role = "user", text = "hola")),
                )
            }
            val repo = newRepository(gateway, db)
            repo.open("s-1")
            assertTrue(repo.messages("s-1").first().isNotEmpty())

            repo.delete("s-1")

            // El fake modela el 4023 real: sin session.close previo el
            // delete habría fallado ("cannot delete an active session").
            val closeIdx = gateway.calls.indexOf("close:rt-1")
            val deleteIdx = gateway.calls.indexOf("delete:s-1")
            assertTrue(closeIdx >= 0 && deleteIdx > closeIdx)
            assertNull(db.chatDao().findByStoredId("s-1"))
            assertTrue(repo.messages("s-1").first().isEmpty())
        }

    @Test
    fun `delete de un draft localOnly cierra el runtime sin session punto delete remoto`() =
        runTest {
            gateway.onCreateSession = {
                SessionCreateResult(
                    sessionId = "rt-new",
                    storedSessionId = "s-new",
                    messageCount = 0,
                    messages = emptyList(),
                    info = SessionLiveInfo(),
                )
            }
            val repo = newRepository(gateway, db)
            repo.create("borrador")

            repo.delete("s-new")

            // El draft no tiene fila en state.db: session.delete daría 4023
            // (la sesión sigue viva); sólo se cierra el runtime y se borra local.
            assertTrue(gateway.calls.contains("close:rt-new"))
            assertTrue(gateway.calls.none { it.startsWith("delete:") })
            assertNull(db.chatDao().findByStoredId("s-new"))
        }

    @Test
    fun `delete que falla en remoto no toca la caché`() =
        runTest {
            gateway.onResumeSession = { stored -> resumeResult(runtimeId = "rt-1", storedId = stored) }
            gateway.onDeleteSession = { throw JsonRpcException(4023, "cannot delete an active session") }
            val repo = newRepository(gateway, db)
            repo.open("s-1")

            val failure = runCatching { repo.delete("s-1") }

            assertTrue(failure.isFailure)
            assertEquals("s-1", db.chatDao().findByStoredId("s-1")?.storedId) // sigue cacheado
        }

    @Test
    fun `deltas se acumulan en memoria y message punto complete persiste la burbuja`() =
        runTest {
            gateway.onResumeSession = { stored ->
                resumeResult(
                    runtimeId = "rt-1",
                    storedId = stored,
                    messages = listOf(TranscriptMessage(role = "user", text = "hola")),
                )
            }
            val repo = newRepository(gateway, db)
            repo.open("s-1")

            gateway.emit(EventTypes.MESSAGE_START, sessionId = "rt-1", seq = 1, payload = EMPTY_PAYLOAD)
            // El handler de message.start suspende en un DAO (hilo real de
            // Room): los deltas que llegan detrás se procesan cuando vuelve —
            // hay que sondear, runCurrent() no basta.
            eventually { repo.liveTurns.value["rt-1"]?.takeIf { it.streaming } }

            gateway.emit(EventTypes.MESSAGE_DELTA, sessionId = "rt-1", seq = 2, payload = textPayload("Hola "))
            gateway.emit(EventTypes.MESSAGE_DELTA, sessionId = "rt-1", seq = 3, payload = textPayload("mamá"))
            val turn = eventually { repo.liveTurns.value["rt-1"]?.takeIf { it.text == "Hola mamá" } }
            assertEquals("Hola mamá", turn.text)
            // Nada persistido aún: sólo el mensaje de usuario del resume.
            assertEquals(listOf("hola"), repo.messages("s-1").first().map { it.text })

            gateway.emit(
                EventTypes.MESSAGE_COMPLETE,
                sessionId = "rt-1",
                seq = 4,
                payload = textPayload("Hola mamá"),
            )
            val messages = eventually { repo.messages("s-1").first().takeIf { it.size == 2 } }
            assertEquals("Hola mamá", messages[1].text)
            assertEquals("assistant", messages[1].role)
            assertEquals(MessageKind.TEXT, messages[1].kind)
            assertNull(repo.liveTurns.value["rt-1"]) // el turno en vuelo se cierra
        }

    @Test
    fun `message punto complete con error persiste burbuja de error`() =
        runTest {
            gateway.onResumeSession = { stored -> resumeResult(runtimeId = "rt-1", storedId = stored) }
            val repo = newRepository(gateway, db)
            repo.open("s-1")

            gateway.emit(EventTypes.MESSAGE_DELTA, sessionId = "rt-1", seq = 1, payload = textPayload("casi"))
            gateway.emit(
                EventTypes.MESSAGE_COMPLETE,
                sessionId = "rt-1",
                seq = 2,
                payload = buildJsonObject { put("error", "modelo caído") },
            )

            val message = eventually { repo.messages("s-1").first().singleOrNull() }
            assertEquals(MessageKind.ERROR, message.kind)
            assertEquals("casi", message.text)
        }

    @Test
    fun `history recrea la fila si desapareció entre el RPC y la escritura`() =
        runTest {
            gateway.onResumeSession = { stored -> resumeResult(runtimeId = "rt-1", storedId = stored) }
            gateway.onSessionHistory = {
                SessionHistoryResult(
                    count = 1,
                    messages = listOf(TranscriptMessage(role = "assistant", text = "guardado")),
                )
            }
            val repo = newRepository(gateway, db)
            repo.open("s-1")
            // La fila desaparece (refresh evictó el chat, borrado en otra capa):
            // la escritura no debe propagar un SQLiteConstraintException.
            db.chatDao().deleteByStoredId("s-1")

            repo.history("s-1")

            val cached = repo.messages("s-1").first()
            assertEquals(listOf("guardado"), cached.map { it.text })
            assertEquals("s-1", db.chatDao().findByStoredId("s-1")?.storedId)
        }

    @Test
    fun `runtimeIdFor devuelve el cacheado o reabre si falta`() =
        runTest {
            var resumeCalls = 0
            gateway.onResumeSession = { stored ->
                resumeCalls += 1
                resumeResult(runtimeId = "rt-1", storedId = stored)
            }
            val repo = newRepository(gateway, db)
            repo.open("s-1")

            assertEquals("rt-1", repo.runtimeIdFor("s-1")) // cacheado: sin resume extra
            assertEquals(1, resumeCalls)

            assertEquals("rt-1", repo.runtimeIdFor("s-2")) // sin mapeo: abre por stored id
            assertEquals(2, resumeCalls)
        }
}
