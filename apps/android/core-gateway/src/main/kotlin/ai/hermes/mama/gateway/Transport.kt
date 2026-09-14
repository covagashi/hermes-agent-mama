package ai.hermes.mama.gateway

import kotlinx.coroutines.flow.Flow

/**
 * Un frame JSON-RPC de texto por mensaje (ROADMAP §2.2). La implementación real
 * es WebSocket sobre OkHttp (B2); los tests usan un fake en memoria.
 *
 * `send` puede invocarse desde varias corrutinas a la vez: la implementación debe
 * ser thread-safe (OkHttp WebSocket ya lo es) o serializar sus envíos. El canal
 * garantiza que cada `send` lleva exactamente un frame completo.
 */
interface Transport {
    /** Envía un frame de texto (UTF-8 estricto: los contenidos llevan emoji/plano astral). */
    suspend fun send(text: String)

    /** Flujo de frames entrantes; al completar o fallar, el canal se da por muerto. */
    val incoming: Flow<String>

    /** Cierra el transporte. Idempotente. */
    suspend fun close()
}
