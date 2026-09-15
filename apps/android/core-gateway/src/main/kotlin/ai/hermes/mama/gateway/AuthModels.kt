package ai.hermes.mama.gateway

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Credenciales del proveedor `basic` (§2.1.1). La sesión las retiene EN MEMORIA
 * sólo para el re-login automático de `wsTicket` (§2.1.5); persistirlas es cosa
 * de `ConnectionSettings` (C2, EncryptedSharedPreferences) — B3 no escribe la
 * contraseña a disco.
 *
 * §8: [toString] redacta la contraseña — un `println`/log accidental no la filtra.
 */
data class Credentials(
    val username: String,
    val password: String,
) {
    override fun toString(): String = "Credentials(username=$username, password=<redacted>)"
}

/**
 * Identidad devuelta por `GET /api/auth/me` (§2.1.2). El backend real puede
 * añadir campos (`org_id`, …): se ignoran. `expiresAt` son unix-seconds tal
 * cual los sirve el servidor.
 */
@Serializable
data class SessionIdentity(
    @SerialName("user_id") val userId: String,
    val email: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    val provider: String? = null,
    @SerialName("expires_at") val expiresAt: Double? = null,
)

/**
 * Ticket WS de un solo uso minteado por `POST /api/auth/ws-ticket` (§2.1.3).
 * Caduca a los `ttlSeconds` (30 s en el backend real) y un ticket por socket —
 * se mintea justo antes de abrir la conexión, también en cada reconexión.
 *
 * §8: el valor es un secreto efímero — nunca se loguea ni persiste; [toString]
 * lo redacta.
 */
@Serializable
data class WsTicket(
    val ticket: String,
    @SerialName("ttl_seconds") val ttlSeconds: Int = DEFAULT_TTL_SECONDS,
) {
    override fun toString(): String = "WsTicket(ttlSeconds=$ttlSeconds, ticket=<redacted>)"
}

private const val DEFAULT_TTL_SECONDS = 30
