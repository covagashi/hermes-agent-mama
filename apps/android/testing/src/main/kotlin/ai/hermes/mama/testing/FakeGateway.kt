package ai.hermes.mama.testing

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * `FakeGateway` — servidor WebSocket + HTTP de pruebas (ROADMAP §5, tarea B5).
 *
 * Lado SERVIDOR del contrato §2 (la app habla JSON-RPC con él como si fuera un
 * `hermes serve` real). Sirve en UN puerto:
 *
 * - `POST /auth/password-login` → `{"ok":true,"next":"/"}` + `Set-Cookie`
 *   (`hermes_session_at`, `hermes_session_provider`); 401/404/429 según el guion.
 * - `GET /api/auth/me` → identidad §2.1.2 o 401.
 * - `POST /api/auth/ws-ticket` → `{"ticket","ttl_seconds":30}` de un solo uso.
 * - `GET /api/ws?ticket=` → JSON-RPC §2.2: `gateway.ready`, respuestas
 *   correlacionadas, eventos §2.4 y peticiones `srq-*` §2.5 que el fake espera.
 *
 * El comportamiento de cada `prompt.submit` lo dicta el guion
 * ([FakeGatewayScript]): `message.start`, N `message.delta`, `message.complete`,
 * `approval`/`clarify`, comandos `browser.controller.*`, etc.
 *
 * Uso en tests (puerto efímero):
 *
 * ```kotlin
 * val gateway = FakeGateway(FakeGatewayScript.load("hola_mundo")).start()
 * … // wsUrl / httpUrl / port
 * gateway.close()
 * ```
 *
 * Uso como proceso: `./gradlew :testing:run --args="--script hola_mundo"`
 * escucha en `0.0.0.0:8399` (el emulador lo alcanza vía `10.0.2.2`).
 */
class FakeGateway(
    val script: FakeGatewayScript,
    private val host: String = "127.0.0.1",
    private val requestedPort: Int = 0,
    private val logger: (String) -> Unit = {},
) : AutoCloseable {
    val store = FakeSessionStore()
    val auth = FakeAuth(script.auth)
    internal val json = Json { ignoreUnknownKeys = true }

    /** Conexiones WS vivas (para broadcasts `sessions.changed` y tests). */
    internal val connections = CopyOnWriteArrayList<WsConnection>()

    /** Respuestas del cliente a peticiones `srq-*` (aserción de tests). */
    val answeredRequests = CopyOnWriteArrayList<AnsweredRequest>()

    /** Resultados `browser.controller.result` recibidos (aserción de tests). */
    val browserCommandResults = CopyOnWriteArrayList<AnsweredRequest>()

    /** Toda llamada cliente→servidor recibida `{method, params}` (aserción de tests). */
    val receivedCalls = CopyOnWriteArrayList<JsonObject>()

    @Volatile
    private var boundPort = -1

    /** Error de bind explícito (puerto efímero no resuelto): lo lanza [start], nunca un 0 silencioso. */
    @Volatile
    private var bindFailure: String? = null
    private val boundLatch = CountDownLatch(1)
    private val stopLatch = CountDownLatch(1)
    private var server: EmbeddedServer<*, *>? = null

    internal val dispatcher = RpcDispatcher(this)

    /** Puerto real tras [start] (con `port = 0`, el que asignó el SO). */
    val port: Int
        get() = boundPort

    /** Host al que deben conectarse los clientes (`127.0.0.1` si el bind es `0.0.0.0`). */
    val clientHost: String
        get() = if (host == "0.0.0.0" || host == "::") "127.0.0.1" else host

    val httpUrl: String
        get() = "http://$clientHost:$port"

    val wsUrl: String
        get() = "ws://$clientHost:$port/api/ws"

    /** Arranca el servidor y espera (hasta `timeoutMs`) a que el puerto esté bound. */
    fun start(timeoutMs: Long = START_TIMEOUT_MS): FakeGateway {
        check(server == null) { "FakeGateway ya arrancado" }
        seedSessions()
        val newServer =
            embeddedServer(CIO, port = requestedPort, host = host) {
                install(WebSockets)
                monitor.subscribe(ApplicationStarted) { app ->
                    app.launch { resolveBoundPort() }
                }
                monitor.subscribe(ApplicationStopped) {
                    stopLatch.countDown()
                }
                routing {
                    post("/auth/password-login") { call.guardedHttp { handlePasswordLogin() } }
                    get("/api/auth/me") { call.guardedHttp { handleMe() } }
                    post("/api/auth/ws-ticket") { call.guardedHttp { handleWsTicket() } }
                    webSocket("/api/ws") { serveWs() }
                }
            }
        server = newServer
        newServer.start(wait = false)
        if (!boundLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            newServer.stop(0, STOP_GRACE_MS)
            error("FakeGateway no llegó a bind en ${timeoutMs}ms")
        }
        bindFailure?.let { failure ->
            newServer.stop(0, STOP_GRACE_MS)
            error(failure)
        }
        logger("FakeGateway[${script.name}] escuchando en $httpUrl (ws $wsUrl)")
        return this
    }

    /** Bloquea el hilo hasta [close] (proceso standalone). */
    fun awaitTermination() {
        stopLatch.await()
    }

    override fun close() {
        val srv = server ?: return
        server = null
        srv.stop(STOP_GRACE_MS, STOP_TIMEOUT_MS)
        stopLatch.countDown()
    }

    // --- arranque interno ---

    private fun seedSessions() {
        script.sessions.forEachIndexed { index, seed ->
            // Seed sin `runtime_id` = stored-only (borrable): NO entra al
            // registro vivo hasta que session.resume la suba, como el real.
            store.add(
                FakeSession(
                    storedId = seed.storedId,
                    runtimeId = seed.runtimeId ?: "sess_seed_$index",
                    title = seed.title,
                    preview = seed.preview,
                    startedAt = seed.startedAt,
                    source = seed.source,
                    hidden = seed.hidden,
                    initialMessages = seed.messages,
                ),
                live = seed.runtimeId != null,
            )
        }
    }

    private suspend fun resolveBoundPort() {
        val srv = server ?: return
        // resolvedConnectors() suspende hasta que el engine publica los conectores;
        // el retry cubre engines que devuelven la lista vacía durante el arranque.
        var attempts = 0
        while (boundPort < 0 && attempts < BOUND_PORT_ATTEMPTS) {
            attempts += 1
            boundPort =
                runCatching {
                    srv.engine
                        .resolvedConnectors()
                        .firstOrNull()
                        ?.port ?: -1
                }.getOrDefault(-1)
            if (boundPort < 0) {
                delay(BOUND_PORT_RETRY_MS)
            }
        }
        if (boundPort < 0) {
            if (requestedPort > 0) {
                // Puerto explícito: el bind ya lo hizo el engine; resolvedConnectors
                // vacío es sólo una laguna del monitor.
                boundPort = requestedPort
            } else {
                // port=0 sin conector resuelto: error explícito, jamás un 0 silencioso.
                bindFailure = "FakeGateway no resolvió el puerto efímero tras $BOUND_PORT_ATTEMPTS intentos"
            }
        }
        boundLatch.countDown()
    }

    // --- HTTP de autenticación (§2.1) ---

    /**
     * Envuelve un handler HTTP: un fallo por culpa del cliente (JSON/tipos) → 400;
     * cualquier otro → 500. Sin el wrapper una excepción tumba la request con el
     * página de error de Ktor y el test no ve el código real.
     */
    private suspend fun io.ktor.server.application.ApplicationCall.guardedHttp(
        body: suspend io.ktor.server.application.ApplicationCall.() -> Unit,
    ) {
        try {
            body()
        } catch (e: IllegalArgumentException) {
            respondText(
                "bad request: ${e.message}",
                ContentType.Text.Plain,
                HttpStatusCode.BadRequest,
            )
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            log("HTTP handler falló (${e::class.simpleName}): ${e.message}")
            respondText("internal error", ContentType.Text.Plain, HttpStatusCode.InternalServerError)
        }
    }

    private suspend fun io.ktor.server.application.ApplicationCall.handlePasswordLogin() {
        val body =
            try {
                json.parseToJsonElement(receiveText())
            } catch (ignored: SerializationException) {
                respondText("invalid json", ContentType.Text.Plain, HttpStatusCode.BadRequest)
                return
            }
        val obj = (body as? JsonObject) ?: JsonObject(emptyMap())
        when (val outcome = auth.login(obj.str("provider"), obj.str("username"), obj.str("password"))) {
            is FakeAuth.LoginOutcome.Ok -> {
                response.header(HttpHeaders.SetCookie, auth.sessionSetCookie(outcome.sessionToken))
                response.header(HttpHeaders.SetCookie, auth.providerSetCookie())
                respondText("""{"ok":true,"next":"/"}""", ContentType.Application.Json)
            }

            FakeAuth.LoginOutcome.UnknownProvider ->
                respondText("Unknown provider", ContentType.Text.Plain, HttpStatusCode.NotFound)

            FakeAuth.LoginOutcome.InvalidCredentials ->
                respondText("Invalid credentials", ContentType.Text.Plain, HttpStatusCode.Unauthorized)

            FakeAuth.LoginOutcome.RateLimited -> {
                response.header(HttpHeaders.RetryAfter, "7")
                respondText(
                    "Too many login attempts. Try again shortly.",
                    ContentType.Text.Plain,
                    HttpStatusCode.TooManyRequests,
                )
            }
        }
    }

    private suspend fun io.ktor.server.application.ApplicationCall.handleMe() {
        val identity = auth.identityFromCookies(request.headers[HttpHeaders.Cookie])
        if (identity == null) {
            respond(HttpStatusCode.Unauthorized)
            return
        }
        respondText(auth.mePayload().toString(), ContentType.Application.Json)
    }

    private suspend fun io.ktor.server.application.ApplicationCall.handleWsTicket() {
        val identity = auth.identityFromCookies(request.headers[HttpHeaders.Cookie])
        if (identity == null) {
            respond(HttpStatusCode.Unauthorized)
            return
        }
        val ticket = auth.mintTicket(identity)
        respondText(
            """{"ticket":"$ticket","ttl_seconds":${FakeAuth.TICKET_TTL_S}}""",
            ContentType.Application.Json,
        )
    }

    // --- WebSocket (§2.2) ---

    /**
     * Credencial del upgrade `/api/ws` — NO rechaza antes del 101: como el real
     * (`web_routers/chat_ws.py`), el upgrade se acepta y la conexión se cierra
     * con el code 4401. Devuelve la identidad sellada (ticket) o `null` si el
     * socket debe cerrarse; sin ticket en modo dev → identidad NO autenticada.
     */
    private fun io.ktor.server.application.ApplicationCall.wsIdentityOrRejection(): Pair<FakeIdentity?, String?> {
        if (request.path() != "/api/ws") {
            return null to null
        }
        val ticket = request.queryParameters["ticket"]
        return when {
            ticket != null ->
                auth
                    .consumeTicket(ticket)
                    ?.let { it to null }
                    ?: (null to "ticket_invalid")

            script.requireWsTicket -> null to "no_credential"

            // Modo dev (require_ws_ticket=false): entra sin identidad sellada,
            // igual que un transport legacy del real — browser.controller.* → 4403.
            else -> FakeIdentity(script.auth.userId, script.auth.provider, authenticated = false) to null
        }
    }

    private suspend fun DefaultWebSocketServerSession.serveWs() {
        val (identity, rejection) = call.wsIdentityOrRejection()
        if (identity == null) {
            // El real acepta el upgrade y cierra con 4401 (auth: <motivo>).
            close(CloseReason(WS_CLOSE_UNAUTHORIZED, "auth: ${rejection ?: "no_credential"}"))
            return
        }
        val conn = WsConnection(this, identity, this@FakeGateway)
        connections.add(conn)
        try {
            // gateway.ready va SÓLO a esta conexión y sin session_id/seq (ws.py:
            // `params: {"type": "gateway.ready", "payload": {…}}`).
            conn.send(
                buildJsonObject {
                    put("method", "event")
                    put(
                        "params",
                        buildJsonObject {
                            put("type", "gateway.ready")
                            put("payload", readyPayload())
                        },
                    )
                },
            )
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    conn.onText(frame.readText())
                }
            }
        } finally {
            connections.remove(conn)
            conn.onDisconnected()
        }
    }

    private fun readyPayload(): JsonObject =
        buildJsonObject {
            put("skin", script.skin)
            put("change_events", true)
            put("replay_epoch", script.replayEpoch)
            put("heartbeat", true)
        }

    // --- emisión de eventos (§2.4) ---

    /**
     * Emite `{"method":"event","params":{type, session_id, seq, payload}}`.
     * Con [session] nul o `broadcast` → `session_id` "" a TODAS las conexiones
     * (sessions.changed y avisos globales); si no, sólo a `conn` (los eventos de
     * un turno los ve quien envió el prompt, como en el real). Los eventos con
     * sesión llevan `seq` y se guardan en su log para `session.events.since`.
     */
    internal suspend fun emitEvent(
        conn: WsConnection?,
        type: String,
        session: FakeSession?,
        payload: JsonObject,
        broadcast: Boolean = false,
    ) {
        val seq = session?.seq?.incrementAndGet()
        val sessionId = if (broadcast || session == null) "" else session.runtimeId
        val params =
            buildJsonObject {
                put("type", type)
                put("session_id", sessionId)
                if (seq != null) {
                    put("seq", seq)
                }
                put("payload", payload)
            }
        if (session != null) {
            session.appendEvent(params)
        }
        val frame =
            buildJsonObject {
                put("method", "event")
                put("params", params)
            }
        if (broadcast || session == null) {
            connections.forEach { it.sendQuietly(frame) }
        } else {
            conn?.sendQuietly(frame)
        }
    }

    internal suspend fun broadcastSessionsChanged() {
        connections.forEach { conn ->
            conn.sendQuietly(
                buildJsonObject {
                    put("method", "event")
                    put(
                        "params",
                        buildJsonObject {
                            put("type", "sessions.changed")
                            put("session_id", "")
                            put("payload", JsonObject(emptyMap()))
                        },
                    )
                },
            )
        }
    }

    internal fun log(message: String) {
        logger("[fake:${script.name}] $message")
    }

    companion object {
        internal const val START_TIMEOUT_MS = 10_000L
        private const val STOP_GRACE_MS = 200L
        private const val STOP_TIMEOUT_MS = 1_000L
        private const val BOUND_PORT_ATTEMPTS = 50
        private const val BOUND_PORT_RETRY_MS = 50L

        /** Close code con el que el backend real cierra `/api/ws` con credencial mala (chat_ws.py). */
        private const val WS_CLOSE_UNAUTHORIZED: Short = 4401

        internal fun JsonObject.str(key: String): String? =
            this[key]?.jsonPrimitive?.takeIf { it.isString }?.contentOrNull
    }
}
