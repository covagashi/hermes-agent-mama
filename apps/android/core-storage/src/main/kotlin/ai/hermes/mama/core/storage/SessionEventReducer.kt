package ai.hermes.mama.core.storage

import ai.hermes.mama.contract.EventTypes
import ai.hermes.mama.contract.MessageCompletePayload
import ai.hermes.mama.contract.SessionLiveInfo
import ai.hermes.mama.contract.SessionTitlePayload
import ai.hermes.mama.contract.StreamDeltaPayload
import ai.hermes.mama.gateway.GatewayEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Reductor de eventos del gateway para [SessionRepository] (§2.4, B6):
 * traduce cada [GatewayEvent] relevante en escrituras de Room o del mapa de
 * turnos en curso. Vive en fichero hermano al repositorio (layout facade +
 * siblings del área): la clase es `internal` y la crea el propio repositorio.
 *
 * - `sessions.changed` (broadcast, `session_id == null`) no se filtra fuera:
 *   pide un `session.list` conflado vía [requestListRefresh].
 * - `session.info` reaprende el *runtime* id aunque el chat se haya abierto en
 *   otro cliente o el gateway se haya reiniciado (su payload trae
 *   `stored_session_id`).
 * - `message.start`/`message.delta` sólo tocan memoria ([liveTurns]);
 *   `message.complete` es quien persiste la burbuja del asistente (§5/B6:
 *   "escritura incremental de deltas en memoria y persistencia al
 *   `message.complete`").
 * - Eventos `message.*` cuyo runtime id no casa con ningún chat cacheado se
 *   descartan con aviso: sin el mapeo runtime→stored no hay dónde persistirlos
 *   (el transcript queda recuperable vía `open`/`history`).
 */
internal class SessionEventReducer(
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val liveTurns: MutableStateFlow<Map<String, LiveTurn>>,
    private val json: Json,
    private val nowEpochSeconds: () -> Double,
    private val requestListRefresh: () -> Unit,
    private val logger: (message: String) -> Unit,
) {
    suspend fun onEvent(event: GatewayEvent) {
        when (event.type) {
            EventTypes.SESSIONS_CHANGED -> requestListRefresh()
            EventTypes.SESSION_INFO -> onSessionInfo(event)
            EventTypes.SESSION_TITLE -> onSessionTitle(event)
            EventTypes.MESSAGE_START -> onMessageStart(event)
            EventTypes.MESSAGE_DELTA -> onMessageDelta(event)
            EventTypes.MESSAGE_COMPLETE -> onMessageComplete(event)
        }
    }

    /** `session.info {running, title, stored_session_id…}`: refresca el mapeo runtime→stored, el título y `running`. */
    private suspend fun onSessionInfo(event: GatewayEvent) {
        val runtimeId = event.sessionId ?: return
        val info = decode(event, SessionLiveInfo.serializer()) ?: return
        val chat =
            info.storedSessionId
                .takeIf { it.isNotBlank() }
                ?.let { storedId -> chatDao.findByStoredId(storedId) }
        if (chat != null) {
            chatDao.upsert(
                chat.copy(
                    runtimeId = runtimeId,
                    title = info.title.takeIf { it.isNotBlank() } ?: chat.title,
                    running = info.running,
                ),
            )
        }
    }

    /**
     * `session.title {session_id, title}`: el id del payload es la *stored*
     * key (contracts/events.py: "``session_id`` is the stored key"); se prueba
     * el runtime id por si acaso (emisiones fuera del hook de auto-titling).
     */
    private suspend fun onSessionTitle(event: GatewayEvent) {
        val payload = decode(event, SessionTitlePayload.serializer()) ?: return
        val updated = chatDao.setTitleByStoredId(payload.sessionId, payload.title)
        if (updated == 0) {
            chatDao.setTitleByRuntimeId(payload.sessionId, payload.title)
        }
    }

    private suspend fun onMessageStart(event: GatewayEvent) {
        val runtimeId = event.sessionId ?: return
        liveTurns.update { turns -> turns + (runtimeId to LiveTurn(streaming = true)) }
        chatDao.findByRuntimeId(runtimeId)?.let { chat ->
            chatDao.upsert(chat.copy(running = true))
        }
    }

    /** Un delta sin `message.start` previo también abre el turno (el reducer es tolerante). */
    private fun onMessageDelta(event: GatewayEvent) {
        val runtimeId = event.sessionId ?: return
        val delta = decode(event, StreamDeltaPayload.serializer()) ?: return
        liveTurns.update { turns ->
            val current = turns[runtimeId] ?: LiveTurn(streaming = true)
            turns + (runtimeId to current.copy(text = current.text + delta.text, streaming = true))
        }
    }

    private suspend fun onMessageComplete(event: GatewayEvent) {
        val runtimeId = event.sessionId ?: return
        val payload = decode(event, MessageCompletePayload.serializer())
        val accumulated = liveTurns.value[runtimeId]?.text.orEmpty()
        liveTurns.update { turns -> turns - runtimeId }

        val errorText = payload?.error ?: payload?.failureReason
        val body = payload?.finalText() ?: accumulated.ifEmpty { errorText.orEmpty() }
        val chat = chatDao.findByRuntimeId(runtimeId) ?: chatDao.findByStoredId(runtimeId)
        when {
            chat == null -> warn("message.complete de una sesión no cacheada descartado")
            // complete sin contenido (p. ej. turno sólo de herramientas): no hay
            // burbuja que pintar pero el turno cerró igualmente.
            body.isEmpty() && errorText == null -> chatDao.upsert(chat.copy(running = false))
            else -> {
                messageDao.insert(
                    MessageEntity(
                        chatId = chat.storedId,
                        role = ROLE_ASSISTANT,
                        text = body,
                        ts = nowEpochSeconds(),
                        kind = if (errorText == null) MessageKind.TEXT else MessageKind.ERROR,
                    ),
                )
                // El turno cierra y el chat sube al tope: es la actividad más
                // reciente (el servidor haría lo mismo vía effective_last_active).
                chatDao.upsert(
                    chat.copy(
                        running = false,
                        lastActive = chatDao.maxLastActive() + RANK_STEP,
                        messageCount = chat.messageCount + 1,
                    ),
                )
            }
        }
    }

    /** `text` del payload es `JsonElement` (puede ser string o estructura); sólo se usa si es string no vacío. */
    private fun MessageCompletePayload.finalText(): String? =
        (text as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

    /** Payload tolerante: lo que no decodifica deja aviso (§8: tipo, nunca contenido) y se ignora. */
    private fun <T> decode(
        event: GatewayEvent,
        deserializer: DeserializationStrategy<T>,
    ): T? =
        try {
            json.decodeFromJsonElement(deserializer, event.payload)
        } catch (e: SerializationException) {
            warn("payload de '${event.type.take(MAX_WIRE_TAG_CHARS)}' no decodifica (${e::class.simpleName})")
            null
        }

    private fun warn(message: String) {
        runCatching { logger(message) }
    }

    private companion object {
        const val ROLE_ASSISTANT = "assistant"

        /** Paso del rank [ChatEntity.lastActive] al colocar el chat en el tope tras `message.complete`. */
        const val RANK_STEP = 1.0

        /** §8: `type` viene del wire sin cota — se trunca antes de loguear (misma regla que GatewayClient). */
        const val MAX_WIRE_TAG_CHARS = 64
    }
}
