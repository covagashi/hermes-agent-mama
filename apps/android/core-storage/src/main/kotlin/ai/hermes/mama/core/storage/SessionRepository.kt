package ai.hermes.mama.core.storage

import ai.hermes.mama.contract.TranscriptMessage
import ai.hermes.mama.gateway.JsonRpcException
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Repositorio de sesiones (ROADMAP §5/B6): caché Room + red para la lista de
 * chats y sus transcripts.
 *
 * - **Offline primero**: [chats] y [messages] leen sólo Room — abrir la app sin
 *   red muestra la última lista y los transcripts cacheados (aceptación §5).
 *   Toda escritura de Room la hace este repositorio (o el
 *   [SessionEventReducer] desde los eventos §2.4).
 * - **Dos identidades por chat** (§2.3): [ChatEntity.storedId] es estable y la
 *   usan `session.list`/`session.resume`/`session.delete`; el
 *   [ChatEntity.runtimeId] lo devuelven `resume`/`create`, lo exigen los
 *   métodos session-scoped (`history`, `title`) y caduca si el gateway se
 *   reinicia — [withRuntimeId] lo re-aprende reabriendo ante un `4001`.
 * - **Streaming**: los `message.*` no escriben transcript hasta
 *   `message.complete`; el texto en vuelo vive en [liveTurns] (memoria).
 * - `events` es un `SharedFlow` con `replay = 0` (B1): el colector arranca
 *   `UNDISPATCHED` al construir el repositorio, antes de que llegue tráfico.
 *
 * Ciclo de vida: el [scope] gobierna el colector de eventos y el refresh
 * conflado; [close] los cancela (cancelar el scope también).
 */
class SessionRepository(
    private val gateway: SessionGateway,
    private val db: MamaDatabase,
    scope: CoroutineScope,
    private val nowEpochSeconds: () -> Double = { System.currentTimeMillis() / MILLIS_PER_SECOND },
    private val logger: (message: String) -> Unit = {},
) {
    private val chatDao = db.chatDao()
    private val messageDao = db.messageDao()
    private val json = Json { ignoreUnknownKeys = true }

    /** Lista de chats cacheada (más reciente primero): la fuente de verdad de la pantalla Chats. */
    val chats: Flow<List<ChatEntity>> = chatDao.observeChats()

    private val _liveTurns = MutableStateFlow<Map<String, LiveTurn>>(emptyMap())

    /** Turnos del asistente en vuelo por *runtime* session id (memoria; el transcript va a Room). */
    val liveTurns: StateFlow<Map<String, LiveTurn>> = _liveTurns.asStateFlow()

    // Rafagas de sessions.changed → a lo sumo un refresh en vuelo y uno encolado.
    private val refreshRequests =
        MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    private val reducer =
        SessionEventReducer(
            chatDao = chatDao,
            messageDao = messageDao,
            liveTurns = _liveTurns,
            json = json,
            nowEpochSeconds = nowEpochSeconds,
            requestListRefresh = { refreshRequests.tryEmit(Unit) },
            logger = logger,
        )

    private val eventJob =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            gateway.events.collect { event -> reducer.onEvent(event) }
        }

    // El catch (Exception) es deliberado: un refresh que falla (sin red,
    // canal caído) deja la caché intacta y NO puede matar al colector — el
    // siguiente sessions.changed reintentará.
    @Suppress("TooGenericExceptionCaught")
    private val refreshJob =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            refreshRequests.collect {
                try {
                    refreshList()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    warn("refresh tras sessions.changed falló (${e::class.simpleName})")
                }
            }
        }

    /** Transcript cacheado de un chat (por *stored* id), en orden: visible sin red. */
    fun messages(storedId: String): Flow<List<MessageEntity>> = messageDao.observeMessages(storedId)

    /** Turno en vuelo de una sesión (*runtime* id), o `null` si no hay streaming. */
    fun liveTurn(runtimeId: String): Flow<LiveTurn?> =
        liveTurns
            .map { turns -> turns[runtimeId] }
            .distinctUntilChanged()

    /**
     * `session.list` → upsert de la lista. Conserva el `runtimeId` cacheado (la
     * lista no lo trae) y borra los chats que el servidor ya no devuelve — la
     * lista remota es la fuente de verdad (sus mensajes caen por CASCADE).
     */
    suspend fun refreshList() {
        val result = gateway.listSessions()
        db.withTransaction {
            val existing = chatDao.chats().associateBy { it.storedId }
            chatDao.upsertAll(
                result.sessions.map { row ->
                    ChatEntity(
                        storedId = row.id,
                        runtimeId = existing[row.id]?.runtimeId,
                        title = row.title,
                        preview = row.preview,
                        startedAt = row.startedAt,
                        messageCount = row.messageCount,
                    )
                },
            )
            val keep = result.sessions.mapTo(HashSet()) { it.id }
            for (stale in existing.keys - keep) {
                chatDao.deleteByStoredId(stale)
            }
        }
    }

    /**
     * Abre un chat: `session.resume` por *stored* id → guarda el *runtime* id
     * devuelto y reemplaza el transcript cacheado por el del result.
     *
     * Si el backend resuelve otra `stored_session_id` (linaje de compresión),
     * la fila local bajo el id pedido se migra al id canónico.
     */
    suspend fun open(storedId: String): OpenedChat {
        val result = gateway.resumeSession(storedId)
        val canonical = result.storedSessionId?.takeIf { it.isNotBlank() } ?: storedId
        db.withTransaction {
            if (canonical != storedId) {
                chatDao.deleteByStoredId(storedId)
            }
            val previous = chatDao.findByStoredId(canonical)
            chatDao.upsert(
                (previous ?: ChatEntity(storedId = canonical))
                    .copy(runtimeId = result.sessionId, messageCount = result.messageCount),
            )
            replaceMessages(canonical, result.messages)
        }
        return OpenedChat(storedId = canonical, runtimeId = result.sessionId)
    }

    /**
     * `session.history` → reemplaza el transcript cacheado. Exige sesión viva:
     * sin `runtimeId` cacheado abre el chat primero (el resume ya trae
     * mensajes). Devuelve el `count` del result.
     */
    suspend fun history(storedId: String): Long =
        withRuntimeId(storedId) { runtimeId ->
            val result = gateway.sessionHistory(runtimeId)
            db.withTransaction {
                replaceMessages(storedId, result.messages)
                chatDao.findByStoredId(storedId)?.let { chat ->
                    chatDao.upsert(chat.copy(messageCount = result.count))
                }
            }
            result.count
        }

    /** `session.create` → inserta el chat con ambos ids y su transcript inicial (si lo hay). */
    suspend fun create(title: String?): OpenedChat {
        val result = gateway.createSession(title)
        db.withTransaction {
            chatDao.upsert(
                ChatEntity(
                    storedId = result.storedSessionId,
                    runtimeId = result.sessionId,
                    title = title.orEmpty(),
                    startedAt = nowEpochSeconds(),
                    messageCount = result.messageCount,
                ),
            )
            replaceMessages(result.storedSessionId, result.messages)
        }
        return OpenedChat(storedId = result.storedSessionId, runtimeId = result.sessionId)
    }

    /**
     * `session.delete` remoto por *stored* id y, si responde OK, borrado local
     * (CASCADE a mensajes). Si el remoto falla — p. ej. `4023` porque la sesión
     * sigue viva en el gateway — la caché no se toca y el error sube.
     */
    suspend fun delete(storedId: String) {
        val runtimeId = chatDao.findByStoredId(storedId)?.runtimeId
        gateway.deleteSession(storedId)
        db.withTransaction {
            chatDao.deleteByStoredId(storedId)
        }
        if (runtimeId != null) {
            _liveTurns.update { turns -> turns - runtimeId }
        }
    }

    /** `session.title` por *runtime* id (session-scoped: reabre si no hay o caducó); aplica el título devuelto. */
    suspend fun rename(
        storedId: String,
        title: String,
    ) {
        val applied =
            withRuntimeId(storedId) { runtimeId ->
                gateway.renameSession(runtimeId, title).title
            }
        chatDao.setTitleByStoredId(storedId, applied)
    }

    /** Cancela el colector de eventos y el refresh conflado (idempotente). */
    fun close() {
        eventJob.cancel()
        refreshJob.cancel()
    }

    /**
     * Resuelve el *runtime* id vivo de [storedId]: el cacheado, o `open` si no
     * hay. Ante un `4001` (runtime id obsoleto: el gateway reiniciado ya no la
     * tiene en memoria) reabre una vez por stored id y reintenta.
     */
    private suspend fun <T> withRuntimeId(
        storedId: String,
        block: suspend (runtimeId: String) -> T,
    ): T {
        val runtimeId =
            chatDao.findByStoredId(storedId)?.runtimeId
                ?: return block(open(storedId).runtimeId)
        return try {
            block(runtimeId)
        } catch (e: JsonRpcException) {
            if (e.code != SESSION_NOT_FOUND_CODE) {
                throw e
            }
            block(open(storedId).runtimeId)
        }
    }

    /** Reemplazo atómico del transcript de un chat (lo invoca el caller dentro de `withTransaction`). */
    private suspend fun replaceMessages(
        storedId: String,
        transcript: List<TranscriptMessage>,
    ) {
        messageDao.deleteForChat(storedId)
        messageDao.insertAll(transcript.map { message -> message.toEntity(storedId) })
    }

    /** Fila Room de un [TranscriptMessage]: `kind` hereda `display_kind` del wire. */
    private fun TranscriptMessage.toEntity(storedId: String): MessageEntity =
        MessageEntity(
            chatId = storedId,
            role = role,
            text = text.orEmpty(),
            ts = timestamp ?: 0.0,
            kind = displayKind ?: MessageKind.TEXT,
            remoteRowId = rowId,
        )

    /** §8: el logger viene de fuera — un logger que lanza no puede tumbar el repositorio. */
    private fun warn(message: String) {
        runCatching { logger(message) }
    }

    private companion object {
        /** `session not found` del backend (`_sess_nowait`): el runtime id ya no está vivo. */
        const val SESSION_NOT_FOUND_CODE = 4001

        const val MILLIS_PER_SECOND = 1000.0
    }
}
