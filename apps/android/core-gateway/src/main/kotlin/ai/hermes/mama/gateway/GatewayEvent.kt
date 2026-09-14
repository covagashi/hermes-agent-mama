package ai.hermes.mama.gateway

import kotlinx.serialization.json.JsonElement

/**
 * Notificación `{"method":"event","params":{…}}` decodificada (ROADMAP §2.2/§2.4).
 *
 * `payload` queda como [JsonElement]: el canal es tolerante a tipos de evento
 * desconocidos y quien consume (B4, `GatewayClient`) decodifica por [type] con
 * los DTOs generados en `core-contract`.
 */
data class GatewayEvent(
    /** `params.type` — p. ej. `message.delta`, `gateway.ready`, `tool.start`. */
    val type: String,
    /** `params.session_id` — `null` en broadcasts sin sesión (el backend envía `""`; el canal lo normaliza). */
    val sessionId: String? = null,
    /** `params.seq` — contador monótono por sesión (`event_replay`); nulo si no viene. */
    val seq: Long? = null,
    /** `params.payload` — [kotlinx.serialization.json.JsonNull] si el evento no trae payload. */
    val payload: JsonElement,
)
