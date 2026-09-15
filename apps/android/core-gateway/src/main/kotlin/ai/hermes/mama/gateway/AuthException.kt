package ai.hermes.mama.gateway

import kotlin.time.Duration

/**
 * Errores tipados del flujo de autenticación §2.1 (tarea B3).
 *
 * TODOS son **terminales** para el bucle de reconexión: la clase base extiende
 * [ConnectionFatalException], así un fallo de auth dentro del `onBeforeConnect`
 * del [ConnectionManager] publica `ConnectionState.Failed` y la app va a la
 * pantalla de conexión con un mensaje humano (§2.1.5) en vez de reintentar
 * contra un servidor que ya dijo que no (un 4xx no se cura solo, y un 429 pide
 * explícitamente que dejemos de llamar).
 *
 * Los fallos de TRANSPORTE (IOException, timeouts, socket cortado) NO son de
 * esta familia: suben tal cual y el manager los trata como reintentables.
 *
 * §8: ningún `message` lleva cookies, tickets ni la contraseña — sólo el
 * motivo y códigos de estado.
 */
sealed class AuthException(
    message: String,
    cause: Throwable? = null,
) : ConnectionFatalException(message, cause) {
    /** `401` en `password-login`: usuario/contraseña rechazados por el servidor. */
    class InvalidCredentials(
        cause: Throwable? = null,
    ) : AuthException("invalid credentials", cause)

    /** `404` en `password-login`: el servidor no tiene proveedor de contraseña registrado. */
    class ProviderMissing(
        cause: Throwable? = null,
    ) : AuthException("no password provider registered on the server", cause)

    /**
     * `429`: el servidor está limitando los intentos de login.
     * [retryAfter] viene del header `Retry-After` si lo mandó (`null` si no).
     */
    class RateLimited(
        val retryAfter: Duration? = null,
        cause: Throwable? = null,
    ) : AuthException("login attempts rate-limited by the server", cause)

    /**
     * `401` en `me`/`ws-ticket`, o un re-login fallido: la sesión/cookies ya no
     * valen y hay que pedir las credenciales de nuevo.
     */
    class SessionExpired(
        message: String = "session cookies rejected by the server",
        cause: Throwable? = null,
    ) : AuthException(message, cause)

    /**
     * Estado HTTP no mapeado del flujo auth (3xx — los redirects están
     * desactivados para no reenviar la contraseña —, 5xx, …). Terminal como el
     * resto: la usuaria ve el mensaje humano y decide cuándo reintentar.
     */
    class UnexpectedStatus(
        val statusCode: Int,
        cause: Throwable? = null,
    ) : AuthException("unexpected HTTP $statusCode from the auth endpoints", cause)

    /** Respuesta 2xx cuyo cuerpo JSON no tiene la forma esperada del contrato. */
    class MalformedResponse(
        cause: Throwable? = null,
    ) : AuthException("auth response body does not match the expected shape", cause)

    /**
     * Base `http://` (cleartext) para un host que no es loopback/`10.0.2.2` sin
     * estar en flavor `dev` (§8): jamás se manda la contraseña ni se abre un
     * `ws://` por internet en claro.
     */
    class CleartextForbidden(
        cause: Throwable? = null,
    ) : AuthException("cleartext http/ws is only allowed for loopback or emulator hosts", cause)
}
