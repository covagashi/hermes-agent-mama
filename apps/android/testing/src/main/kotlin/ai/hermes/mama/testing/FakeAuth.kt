package ai.hermes.mama.testing

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Autenticación del [FakeGateway] (ROADMAP §2.1, proveedor `basic`):
 * `password-login` emite cookies `hermes_session_at`/`hermes_session_provider`,
 * `me` y `ws-ticket` las validan, y `/api/ws?ticket=` consume tickets de un uso
 * (30 s). Todo es sintético: las credenciales vienen del guion.
 */
class FakeAuth(
    private val config: FakeGatewayScript.AuthConfig,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    sealed interface LoginOutcome {
        data class Ok(
            val sessionToken: String,
        ) : LoginOutcome

        data object UnknownProvider : LoginOutcome

        data object InvalidCredentials : LoginOutcome

        /** `auth.rate_limited: true` en el guion → siempre 429 (test de B3). */
        data object RateLimited : LoginOutcome
    }

    private data class Ticket(
        val userId: String,
        val expiresAtMs: Long,
    )

    private val sessions = ConcurrentHashMap<String, FakeIdentity>()
    private val tickets = ConcurrentHashMap<String, Ticket>()
    private val random = SecureRandom()

    fun login(
        provider: String?,
        username: String?,
        password: String?,
    ): LoginOutcome =
        when {
            config.rateLimited -> LoginOutcome.RateLimited
            provider != config.provider -> LoginOutcome.UnknownProvider
            username != config.username || password != config.password ->
                LoginOutcome.InvalidCredentials

            else -> {
                val token = randomToken()
                sessions[token] = FakeIdentity(userId = config.userId, provider = config.provider)
                LoginOutcome.Ok(token)
            }
        }

    /** Identidad desde la cabecera `Cookie` (admite los prefijos `__Host-`/`__Secure-` como el real). */
    fun identityFromCookies(cookieHeader: String?): FakeIdentity? {
        val token = cookieValue(cookieHeader, SESSION_COOKIE) ?: return null
        return sessions[token]
    }

    /** `POST /api/auth/ws-ticket`: un ticket de un solo uso, 30 s (§2.1.3). */
    fun mintTicket(identity: FakeIdentity): String {
        val ticket = randomToken()
        tickets[ticket] = Ticket(userId = identity.userId, expiresAtMs = clockMs() + TICKET_TTL_MS)
        return ticket
    }

    /** Consume el ticket: un solo uso y caducado a los 30 s. `null` = inválido. */
    fun consumeTicket(ticket: String?): FakeIdentity? {
        val found = ticket?.takeUnless { it.isEmpty() }?.let(tickets::remove) ?: return null
        return if (found.expiresAtMs < clockMs()) {
            null
        } else {
            FakeIdentity(userId = found.userId, provider = config.provider)
        }
    }

    /** Cabecera `Set-Cookie` que fija la sesión (bare name: el fake sirve por HTTP). */
    fun sessionSetCookie(token: String): String = "$SESSION_COOKIE=$token; HttpOnly; Path=/; SameSite=Lax"

    fun providerSetCookie(): String = "$PROVIDER_COOKIE=${config.provider}; HttpOnly; Path=/; SameSite=Lax"

    /** JSON del `me` (§2.1.2): unix seconds en `expires_at`, como el backend real. */
    fun mePayload(): JsonObject =
        buildJsonObject {
            put("user_id", config.userId)
            put("email", config.email)
            put("display_name", config.displayName)
            put("org_id", JsonNull)
            put("provider", config.provider)
            put("expires_at", clockMs() / 1000 + SESSION_TTL_S)
        }

    private fun randomToken(): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun cookieValue(
        header: String?,
        name: String,
    ): String? {
        if (header == null) {
            return null
        }
        val names = listOf("__Host-$name", "__Secure-$name", name)
        return header.split(';').firstNotNullOfOrNull { part ->
            val idx = part.indexOf('=')
            val key = if (idx >= 0) part.substring(0, idx).trim() else ""
            if (idx >= 0 && key in names) part.substring(idx + 1).trim() else null
        }
    }

    companion object {
        const val SESSION_COOKIE = "hermes_session_at"
        const val PROVIDER_COOKIE = "hermes_session_provider"
        const val TICKET_TTL_MS = 30_000L
        const val TICKET_TTL_S = 30
        private const val SESSION_TTL_S = 12 * 60 * 60
    }
}
