package ai.hermes.mama.core.storage

import ai.hermes.mama.contract.SessionResumeResult
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 *   métodos session-scoped (`history`, `title`, `close`) y caduca si el
 *   gateway se reinicia — [withRuntimeId] lo re-aprende reabriendo ante un
 *   `4001`.
 * - **Drafts locales** ([ChatEntity.localOnly]): `session.create` no persiste
 *   fila en `state.db` hasta el primer `prompt.submit`, así que `session.list`
 *   no lo devuelve y un refresh ingenuo lo evictaría (con su runtimeId, y el
 *   `message.complete` del primer turno caería descartado). Las filas
 *   `localOnly` sobreviven al refresh hasta que el servidor las lista.
 * - **Streaming**: los `message.*` no escriben transcript hasta
 *   `message.complete`; el texto en vuelo vive en [liveTurns] (memoria).
 * - `events` es un `SharedFlow` con `replay = 0` (B1): el colector arranca
 *   `UNDISPATCHED` al construir el repositorio, antes de que llegue tráfico.
 * - **Burbuja del usuario**: el mensaje que la usuaria envía NO se persiste
 *   aquí — llega al transcript vía `open`/`history`. La escritura optimista
 *   del envío es responsabilidad de C4 (`recordUserMessage` o la UI) y C5.
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

    /** Lista de chats cacheada (orden del servidor, más reciente primero): la fuente de verdad de Chats. */
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

    // open() concurrente sobre el mismo storedId dispararía dos session.resume
    // y escribiría dos runtimeId en carrera: un Mutex por chat los serializa.
    private val openMutexes = Mutex()
    private val openMutexByStoredId = mutableMapOf<String, Mutex>()

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

    // El catch (Exception) es deliberado: un evento que rompe (SQLiteException
    // por una carrera find→insert vs refresh concurrente, un DAO sobre la db
    // cerrada) NO puede matar al colector — el siguiente evento seguiría
    // perdiéndose en silencio. Mismo contrato que refreshJob.
    @Suppress("TooGenericExceptionCaught")
    private val eventJob =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            gateway.events.collect { event ->
                try {
                    reducer.onEvent(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    warn("evento '${event.type.take(MAX_WIRE_TAG_CHARS)}' descartado (${e::class.simpleName})")
                }
            }
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
     * `session.list` → upsert de la lista. Conserva `runtimeId`/`running`
     * (la lista no los trae), graba la posición como rank [ChatEntity.lastActive]
     * y borra los chats que el servidor ya no devuelve — **excepto los
     * [ChatEntity.localOnly]** (drafts aún sin fila en `state.db`: el servidor
     * no los lista pero siguen vivos). Sus mensajes caen por CASCADE y sus
     * [liveTurns] se limpian. Un storedId que aparece en la lista deja de ser
     * `localOnly`.
     */
    suspend fun refreshList() {
        val result = gateway.listSessions()
        val evictedRuntimeIds = mutableListOf<String>()
        db.withTransaction {
            val existing = chatDao.chats().associateBy { it.storedId }
            val size = result.sessions.size
            chatDao.upsertAll(
                result.sessions.mapIndexed { index, row ->
                    ChatEntity(
                        storedId = row.id,
                        runtimeId = existing[row.id]?.runtimeId,
                        title = row.title,
                        preview = row.preview,
                        startedAt = row.startedAt,
                        // La lista llega ya ordenada (effective_last_active
                        // DESC); la posición es el único orden que el wire da.
                        lastActive = (size - index).toDouble(),
                        messageCount = row.messageCount,
                        running = existing[row.id]?.running ?: false,
                        // localOnly = false: aparecer en session.list lo sincroniza.
                    )
                },
            )
            val keep = result.sessions.mapTo(HashSet()) { it.id }
            for (stale in existing.values) {
                if (stale.storedId !in keep && !stale.localOnly) {
                    chatDao.deleteByStoredId(stale.storedId)
                    stale.runtimeId?.let(evictedRuntimeIds::add)
                }
            }
        }
        if (evictedRuntimeIds.isNotEmpty()) {
            _liveTurns.update { turns -> turns - evictedRuntimeIds.toSet() }
        }
    }

    /**
     * Abre un chat: `session.resume` por *stored* id → guarda el *runtime* id
     * devuelto y reemplaza el transcript cacheado por el del result.
     *
     * - El stored canónico lo resuelve `stored_session_id` o, en su defecto,
     *   `session_key` (la punta del linaje de compresión que devuelven los
     *   caminos reales de resume). Si difiere del pedido, la fila local bajo
     *   el id pedido se migra al id canónico.
     * - Un `4007` reintenta una vez: el código cubre «session not found» y el
     *   transitorio «session no longer live; retry resume» de
     *   `_reattach_refusal` (la sesión se fue entre el locate y el attach).
     * - Un [Mutex] por storedId serializa aperturas concurrentes (dos resume
     *   simultáneos escribirían runtimeIds en carrera).
     */
    suspend fun open(storedId: String): OpenedChat {
        val mutex = openMutexes.withLock { openMutexByStoredId.getOrPut(storedId) { Mutex() } }
        return mutex.withLock { openResume(storedId) }
    }

    private suspend fun openResume(storedId: String): OpenedChat {
        val result = resumeWithTransientRetry(storedId)
        val canonical =
            result.storedSessionId?.takeIf { it.isNotBlank() }
                ?: result.sessionKey?.takeIf { it.isNotBlank() && it != result.sessionId }
                ?: storedId
        db.withTransaction {
            if (canonical != storedId) {
                chatDao.deleteByStoredId(storedId)
            }
            val previous = chatDao.findByStoredId(canonical)
            chatDao.upsert(
                (previous ?: ChatEntity(storedId = canonical)).copy(
                    runtimeId = result.sessionId,
                    // Un chat nunca listado (lookup por título, etc.) sube al
                    // tope: abrirlo es la actividad local más reciente.
                    lastActive =
                        previous?.lastActive
                            ?: (chatDao.maxLastActive() + RANK_STEP),
                    messageCount = result.messageCount,
                    running = result.running ?: (result.status == STATUS_STREAMING),
                ),
            )
            replaceMessages(canonical, result.messages)
        }
        return OpenedChat(storedId = canonical, runtimeId = result.sessionId)
    }

    /** `session.resume` con un único reintento ante el `4007` transitorio de `_reattach_refusal`. */
    private suspend fun resumeWithTransientRetry(storedId: String): SessionResumeResult =
        try {
            gateway.resumeSession(storedId)
        } catch (e: JsonRpcException) {
            if (e.code != SESSION_MISSING_CODE) {
                throw e
            }
            gateway.resumeSession(storedId)
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
                // La fila pudo ser evictada entre el RPC y la escritura:
                // re-crear el shell dentro de la transacción evita propagar un
                // SQLiteConstraintException crudo por la FK de messages.
                val chat = chatDao.findByStoredId(storedId) ?: ChatEntity(storedId = storedId)
                chatDao.upsert(chat.copy(messageCount = result.count))
                replaceMessages(storedId, result.messages)
            }
            result.count
        }

    /**
     * `session.create` → inserta el chat con ambos ids y su transcript inicial
     * (si lo hay). La fila nace [ChatEntity.localOnly]: el backend no la
     * persiste hasta el primer `prompt.submit`, así que `session.list` aún no
     * la devuelve y hay que protegerla del refresh.
     */
    suspend fun create(title: String?): OpenedChat {
        val result = gateway.createSession(title)
        db.withTransaction {
            val previous = chatDao.findByStoredId(result.storedSessionId)
            chatDao.upsert(
                ChatEntity(
                    storedId = result.storedSessionId,
                    runtimeId = result.sessionId,
                    title = title.orEmpty(),
                    preview = previous?.preview.orEmpty(),
                    startedAt = previous?.startedAt ?: nowEpochSeconds(),
                    lastActive = chatDao.maxLastActive() + RANK_STEP,
                    messageCount = result.messageCount,
                    running = previous?.running ?: false,
                    localOnly = true,
                ),
            )
            replaceMessages(result.storedSessionId, result.messages)
        }
        return OpenedChat(storedId = result.storedSessionId, runtimeId = result.sessionId)
    }

    /**
     * Borra un chat: si su sesión sigue viva la cierra antes (`session.close`
     * por *runtime* id — el backend responde `4023 cannot delete an active
     * session` mientras un registro vivo tenga `session_key == storedId`) y
     * luego `session.delete` remoto + borrado local (CASCADE a mensajes).
     *
     * Un [ChatEntity.localOnly] no tiene fila en `state.db`: se cierra su
     * runtime vivo y se borra local sin llamar a `session.delete` (daría
     * `4007`). Si el remoto falla la caché no se toca y el error sube.
     * `session.close` es *best-effort*: si falla (canal caído, sesión ya
     * cerrada) el `session.delete` remoto decide igualmente — `4023` o
     * éxito, la respuesta manda.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun delete(storedId: String) {
        val chat = chatDao.findByStoredId(storedId)
        val runtimeId = chat?.runtimeId
        if (runtimeId != null) {
            try {
                gateway.closeSession(runtimeId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warn("session.close previo a delete falló (${e::class.simpleName})")
            }
        }
        if (chat?.localOnly != true) {
            gateway.deleteSession(storedId)
        }
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

    /**
     * Runtime id vivo de un chat (C4 lo necesita para `prompt.submit`):
     * el cacheado, o el de un `open` si no hay/si el servidor respondió `4001`
     * (en ese caso el [OpenedChat] guardado antes quedó obsoleto — por eso la
     * API pide el id cada vez en vez de reusar el de la apertura).
     */
    suspend fun runtimeIdFor(storedId: String): String = withRuntimeId(storedId) { it }

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
            if (e.code != SESSION_STALE_CODE) {
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
        /** `session not found` de los RPC session-scoped (`_sess_nowait`): el runtime id ya no está vivo. */
        const val SESSION_STALE_CODE = 4001

        /**
         * `session not found` de los lookups por *stored* id (`session.resume`
         * en `_resume_locate`) **y** el transitorio «session no longer live;
         * retry resume» de `_reattach_refusal` — un reintento cubre el segundo.
         */
        const val SESSION_MISSING_CODE = 4007

        /** `status` del resume payload cuando la sesión tiene un turno en curso (`_session_live_status`). */
        const val STATUS_STREAMING = "streaming"

        /** Paso del rank [ChatEntity.lastActive] al colocar actividad local encima del tope. */
        const val RANK_STEP = 1.0

        /** §8: `type` viene del wire sin cota — se trunca antes de loguear (misma regla que GatewayClient). */
        const val MAX_WIRE_TAG_CHARS = 64

        const val MILLIS_PER_SECOND = 1000.0
    }
}
