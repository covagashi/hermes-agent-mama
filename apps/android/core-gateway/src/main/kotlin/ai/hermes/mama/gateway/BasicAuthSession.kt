package ai.hermes.mama.gateway

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * Sesión del proveedor `basic` (ROADMAP §2.1, tarea B3): password-login →
 * cookies → `me` → `ws-ticket` → `wss://…/api/ws?ticket=`.
 *
 * - [login] (`POST {base}/auth/password-login`) autentica y las `Set-Cookie`
 *   caen en [cookieJar] (persistente y cifrada vía [SecureStore]).
 * - [me] (`GET {base}/api/auth/me`) es el "Probar conexión": devuelve la
 *   identidad sellada o [AuthException.SessionExpired] si el 401 dice que las
 *   cookies ya no valen.
 * - [wsTicket] (`POST {base}/api/auth/ws-ticket`) mintea un ticket de un solo
 *   uso (30 s). Si el servidor responde 401 → **un solo** re-login con las
 *   credenciales en memoria y un segundo intento; si también falla →
 *   [AuthException.SessionExpired] (§2.1.5). El re-login va bajo
 *   [reloginMutex]: llamadas concurrentes no provocan dos logins.
 * - [wsUrl] construye la URL del socket: `https`→`wss`; `http`→`ws` sólo si el
 *   host es loopback/`10.0.2.2` o la sesión se creó con [allowCleartext]
 *   (flavor `dev`, §8). Una base `http://` a otro host se rechaza en el
 *   constructor — la contraseña jamás viaja en claro por internet.
 * - [connectParams] es el `onBeforeConnect` del [ConnectionManager]: mintea un
 *   ticket nuevo por intento y devuelve el [ConnectParams] listo.
 *
 * Los errores tipados ([AuthException]) son TERMINALES (extienden
 * [ConnectionFatalException] → `ConnectionState.Failed`): un 4xx no se cura
 * reintentando. Los fallos de transporte (`IOException`, timeouts) suben tal
 * cual y el manager los reintenta.
 *
 * §8: nunca se loguea cookie, ticket ni contraseña (ni la URL con `?ticket=`).
 * Los `toString` de [Credentials]/[WsTicket]/[ConnectParams] ya vienen
 * redactados; los mensajes del [logger] sólo llevan códigos de estado.
 */
class BasicAuthSession(
    val baseUrl: HttpUrl,
    secureStore: SecureStore? = null,
    client: OkHttpClient? = null,
    private val allowCleartext: Boolean = false,
    private val provider: String = DEFAULT_PROVIDER,
    private val logger: (String) -> Unit = {},
) {
    init {
        require(baseUrl.scheme == SCHEME_HTTP || baseUrl.scheme == SCHEME_HTTPS) {
            "auth base url must be http(s), not ${baseUrl.scheme}"
        }
        // §8: una base cleartext sólo vale para loopback/emulador o flavor dev —
        // si no, la contraseña viajaría por internet en claro. Se rechaza aquí,
        // antes de que ningún endpoint pueda usarse.
        if (baseUrl.scheme == SCHEME_HTTP && !allowCleartext && !isLoopbackOrEmulator(baseUrl.host)) {
            throw AuthException.CleartextForbidden()
        }
    }

    /**
     * Jar de sesión compartido por todas las llamadas HTTP y el upgrade WS.
     * Persiste cifrado vía [SecureStore]; las cookies nunca se tocan por nombre.
     */
    val cookieJar: PersistentCookieJar = PersistentCookieJar(secureStore)

    /**
     * Cliente efectivo de la sesión (el [client] recibido, o uno por defecto,
     * + el jar y política de no-redirects). Reutilizable para el transporte WS:
     * `WebSocketTransport.factory(session.httpClient)` ya trae jar + ping TCP.
     *
     * Redirects DESACTIVADOS (§8): un 3xx en `password-login` no debe reemitir
     * la contraseña a otro host; llega como [AuthException.UnexpectedStatus].
     */
    val httpClient: OkHttpClient =
        (client ?: defaultAuthHttpClient())
            .newBuilder()
            .cookieJar(cookieJar)
            .followRedirects(false)
            .followSslRedirects(false)
            .pingInterval(WS_PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
            .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val reloginMutex = Mutex()

    /** Credenciales en memoria para el re-login de §2.1.5 (nunca a disco desde B3). */
    @Volatile
    private var credentials: Credentials? = null

    /**
     * La app restaura aquí las credenciales guardadas (C2 las persiste cifradas)
     * para que el re-login automático funcione sin un [login] previo en este
     * proceso. `null` limpia la copia en memoria.
     */
    fun setCredentials(credentials: Credentials?) {
        this.credentials = credentials
    }

    /** Borra cookies (memoria + blob cifrado) y las credenciales en memoria. */
    fun clear() {
        credentials = null
        cookieJar.clear()
    }

    /**
     * `POST {base}/auth/password-login` (§2.1.1). `200` → las cookies quedan en
     * el jar y las credenciales en memoria para re-login. Errores tipados:
     * `401`→[AuthException.InvalidCredentials], `404`→[AuthException.ProviderMissing],
     * `429`→[AuthException.RateLimited] (con `Retry-After` si vino).
     */
    suspend fun login(
        username: String,
        password: String,
    ) {
        val body =
            buildJsonObject {
                put("provider", provider)
                put("username", username)
                put("password", password)
                put("next", "")
            }.toString()
        val request =
            Request
                .Builder()
                .url(endpoint(LOGIN_PATH))
                .post(body.toRequestBody(JSON_MEDIA))
                .header(HEADER_ACCEPT, MIME_JSON)
                .build()
        httpClient.newCall(request).await().use { res ->
            if (res.code != HTTP_OK) {
                throw loginError(res)
            }
            credentials = Credentials(username, password)
        }
        // §8: jamás cookies/credenciales en el mensaje — sólo el hecho.
        runCatching { logger("password-login ok (session cookies stored in jar)") }
    }

    /**
     * `GET {base}/api/auth/me` (§2.1.2). `401` → [AuthException.SessionExpired]
     * (la app vuelve a login). Otro estado inesperado →
     * [AuthException.UnexpectedStatus]; cuerpo irreconocible →
     * [AuthException.MalformedResponse].
     */
    suspend fun me(): SessionIdentity {
        val request =
            Request
                .Builder()
                .url(endpoint(ME_PATH))
                .get()
                .header(HEADER_ACCEPT, MIME_JSON)
                .build()
        return httpClient.newCall(request).await().use { res ->
            if (res.code != HTTP_OK) {
                throw sessionError(res)
            }
            decode(res, SessionIdentity.serializer())
        }
    }

    /**
     * `POST {base}/api/auth/ws-ticket` (§2.1.3): ticket de un solo uso, 30 s.
     * Un `401` dispara UNA re-autenticación con las credenciales en memoria y un
     * segundo intento; si el re-login o el segundo ticket fallan →
     * [AuthException.SessionExpired] (un [AuthException.RateLimited] del
     * re-login sube tal cual: su `retryAfter` es accionable para la UI).
     */
    suspend fun wsTicket(): WsTicket {
        try {
            return requestTicket()
        } catch (ignored: AuthException.SessionExpired) {
            runCatching { logger("ws-ticket rejected the cookies (401); single re-login follows") }
        }
        return reloginMutex.withLock { ticketAfterRelogin() }
    }

    /**
     * Bajo [reloginMutex] (llamadas concurrentes no provocan dos logins a la
     * vez): UN re-login con las credenciales en memoria y UN segundo intento
     * de ticket (§2.1.5). Cualquier segundo fallo → [AuthException.SessionExpired].
     */
    private suspend fun ticketAfterRelogin(): WsTicket {
        val creds =
            credentials
                ?: throw AuthException.SessionExpired("ws-ticket rejected and no credentials to re-login")
        try {
            login(creds.username, creds.password)
            return requestTicket()
        } catch (e: AuthException) {
            // §2.1.5: si también falla → SessionExpired. Un 429 del re-login sube
            // tal cual (su retryAfter es accionable para la UI) y un segundo 401
            // del ticket ya es SessionExpired.
            throw when (e) {
                is AuthException.RateLimited, is AuthException.SessionExpired -> e
                else -> AuthException.SessionExpired("re-login after a ws-ticket 401 also failed", e)
            }
        }
    }

    /** Un intento de `POST /api/auth/ws-ticket`; `401` → [AuthException.SessionExpired]. */
    private suspend fun requestTicket(): WsTicket {
        val request =
            Request
                .Builder()
                .url(endpoint(WS_TICKET_PATH))
                .post(EMPTY_JSON_BODY)
                .header(HEADER_ACCEPT, MIME_JSON)
                .build()
        return httpClient.newCall(request).await().use { res ->
            if (res.code != HTTP_OK) {
                throw sessionError(res)
            }
            val ticket = decode(res, WsTicket.serializer())
            if (ticket.ticket.isEmpty()) {
                throw AuthException.MalformedResponse()
            }
            ticket
        }
    }

    /**
     * URL del socket con el ticket incrustado (§2.1.4): `https`→`wss`, `http`→`ws`
     * (el cleartext ya se validó en el constructor). Conserva un posible prefijo
     * de path de la base (despliegues tras proxy con `X-Forwarded-Prefix`).
     *
     * §8: el resultado lleva `?ticket=` — NUNCA loguearlo.
     */
    fun wsUrl(ticket: String): String {
        // `HttpUrl.Builder` sólo acepta http(s): la autoridad+path+query las
        // codifica OkHttp y el esquema se reescribe a nivel string.
        val httpUrl =
            baseUrl
                .newBuilder()
                .addPathSegments(WS_PATH)
                .addQueryParameter("ticket", ticket)
                .build()
                .toString()
        val wsScheme = if (baseUrl.scheme == SCHEME_HTTPS) SCHEME_WSS else SCHEME_WS
        return wsScheme + httpUrl.substring(baseUrl.scheme.length)
    }

    /**
     * `onBeforeConnect` del [ConnectionManager]: mintea un ticket NUEVO por
     * intento (un solo uso, 30 s — también en cada reconexión) y devuelve el
     * [ConnectParams]. Los [AuthException] que suben son terminales →
     * `ConnectionState.Failed`; un `IOException` es reintentable.
     */
    suspend fun connectParams(): ConnectParams = ConnectParams(url = wsUrl(wsTicket().ticket))

    // --- internos ---

    /** Endpoint bajo la base (conserva prefijo de path si la base lo trae). */
    private fun endpoint(path: String): HttpUrl = baseUrl.newBuilder().addPathSegments(path).build()

    /** `Retry-After` en segundos si el servidor lo mandó (la forma HTTP-date se ignora). */
    private fun retryAfter(res: Response) =
        res
            .header(HEADER_RETRY_AFTER)
            ?.trim()
            ?.toLongOrNull()
            ?.takeIf { it >= 0 }
            ?.seconds

    /** Mapeo §2.1.1 del `password-login`: 401/404/429 tipados; el resto, UnexpectedStatus. */
    private fun loginError(res: Response): AuthException =
        when (res.code) {
            HTTP_UNAUTHORIZED -> AuthException.InvalidCredentials()
            HTTP_NOT_FOUND -> AuthException.ProviderMissing()
            HTTP_TOO_MANY_REQUESTS -> AuthException.RateLimited(retryAfter(res))
            else -> AuthException.UnexpectedStatus(res.code)
        }

    /** 401 de `me`/`ws-ticket` → SessionExpired; cualquier otro estado inesperado → UnexpectedStatus. */
    private fun sessionError(res: Response): AuthException =
        if (res.code == HTTP_UNAUTHORIZED) {
            AuthException.SessionExpired()
        } else {
            AuthException.UnexpectedStatus(res.code)
        }

    private fun <T> decode(
        res: Response,
        serializer: KSerializer<T>,
    ): T {
        val body = res.body?.string() ?: throw AuthException.MalformedResponse()
        return try {
            json.decodeFromString(serializer, body)
        } catch (e: IllegalArgumentException) {
            // kotlinx.serialization lanza IllegalArgumentException/SerializationException.
            throw AuthException.MalformedResponse(e)
        }
    }

    private companion object {
        const val DEFAULT_PROVIDER = "basic"
        const val SCHEME_HTTP = "http"
        const val SCHEME_HTTPS = "https"
        const val SCHEME_WS = "ws"
        const val SCHEME_WSS = "wss"

        const val LOGIN_PATH = "auth/password-login"
        const val ME_PATH = "api/auth/me"
        const val WS_TICKET_PATH = "api/auth/ws-ticket"
        const val WS_PATH = "api/ws"

        const val HEADER_ACCEPT = "Accept"
        const val HEADER_RETRY_AFTER = "Retry-After"
        const val MIME_JSON = "application/json"

        const val HTTP_OK = 200
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_NOT_FOUND = 404
        const val HTTP_TOO_MANY_REQUESTS = 429

        /** Ping TCP/TLS del upgrade WS (mismo valor que `WebSocketTransport.defaultClient`). */
        const val WS_PING_INTERVAL_SECONDS = 20L

        val JSON_MEDIA = MIME_JSON.toMediaType()
        val EMPTY_JSON_BODY = "{}".toRequestBody(JSON_MEDIA)

        /** §8: cleartext sólo con host loopback/`10.0.2.2` (el flavor `dev` fuerza `allowCleartext`). */
        fun isLoopbackOrEmulator(host: String): Boolean =
            host == HOST_EMULATOR ||
                host == HOST_LOCALHOST ||
                host.endsWith(LOCALHOST_SUFFIX) ||
                host.startsWith(LOOPBACK_V4_PREFIX) ||
                host == HOST_LOOPBACK_V6 ||
                host == HOST_LOOPBACK_V6_BRACKETED ||
                host == HOST_LOOPBACK_V6_FULL

        const val HOST_EMULATOR = "10.0.2.2"
        const val HOST_LOCALHOST = "localhost"
        const val LOCALHOST_SUFFIX = ".localhost"
        const val LOOPBACK_V4_PREFIX = "127."
        const val HOST_LOOPBACK_V6 = "::1"
        const val HOST_LOOPBACK_V6_BRACKETED = "[::1]"
        const val HOST_LOOPBACK_V6_FULL = "0:0:0:0:0:0:0:1"
    }
}
