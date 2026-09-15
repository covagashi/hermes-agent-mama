package ai.hermes.mama.core.storage

/**
 * Resultado de abrir un chat ([SessionRepository.open]/[SessionRepository.create]):
 * la pareja de identificadores de §2.3 — el *stored* id (persistencia, lista,
 * deep link) y el *runtime* id de la sesión viva (eventos, `session.history`,
 * `session.title`, `prompt.submit`).
 */
data class OpenedChat(
    val storedId: String,
    val runtimeId: String,
)

/**
 * Turno del asistente en curso **en memoria** (§5/B6): el texto se concatena
 * con cada `message.delta` y se descarta al persistir en `message.complete`.
 * Vive indexado por *runtime* session id en [SessionRepository.liveTurns] y no
 * sobrevive a un reinicio de la app — el transcript cacheado es
 * [SessionRepository.messages].
 */
data class LiveTurn(
    /** Texto acumulado de los `message.delta` del turno. */
    val text: String = "",
    /** `true` entre `message.start` (o primer delta) y `message.complete`. */
    val streaming: Boolean = false,
)
