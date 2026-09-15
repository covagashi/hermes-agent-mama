package ai.hermes.mama.gateway

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Contrato de [BasicAuthSession] (ROADMAP §2.1, tarea B3) contra `MockWebServer`
 * en localhost: flujo completo password-login → cookies → me → ws-ticket → wsUrl.
 */
@Timeout(30)
class BasicAuthSessionTest {
    private val servers = mutableListOf<MockWebServer>()
    private val sessions = mutableListOf<BasicAuthSession>()

    @AfterEach
    fun tearDown() {
        servers.forEach { it.shutdown() }
        servers.clear()
        // El dispatcher de OkHttp retiene hilos: apagar el pool de cada sesión.
        sessions.forEach { session ->
            val client = session.httpClient
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
        sessions.clear()
    }

    private fun newServer(): MockWebServer =
        MockWebServer().also {
            it.start()
            servers += it
        }

    private fun newSession(
        server: MockWebServer,
        secureStore: SecureStore? = null,
        allowCleartext: Boolean = false,
        logger: (String) -> Unit = {},
    ): BasicAuthSession =
        BasicAuthSession(
            baseUrl = server.url("/"),
            secureStore = secureStore,
            allowCleartext = allowCleartext,
            logger = logger,
        ).also { sessions += it }

    private fun json(
        code: Int,
        body: String,
    ): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    private fun loginOk(): MockResponse =
        json(200, """{"ok":true,"next":"/"}""")
            .addHeader("Set-Cookie", "hermes_session_at=tok-abc; HttpOnly; Path=/; Max-Age=43200")
            .addHeader("Set-Cookie", "hermes_session_provider=basic; HttpOnly; Path=/; SameSite=Lax")

    private fun ticketOk(ticket: String = "ticket-1"): MockResponse =
        json(200, """{"ticket":"$ticket","ttl_seconds":30}""")

    // --- login (§2.1.1) ---

    @Test
    fun `login ok guarda las cookies en el jar y las persiste en el SecureStore`() =
        runTest {
            val server = newServer()
            server.enqueue(loginOk())
            val store = FakeSecureStore()
            val session = newSession(server, secureStore = store)

            session.login("usuario", "mama")

            val cookies = session.cookieJar.loadForRequest(server.url("/api/auth/me"))
            assertTrue(
                cookies.any { it.name == "hermes_session_at" },
                "el jar guarda el Set-Cookie de sesión sin parsear nombres",
            )
            assertNotNull(store.load("session_cookies"), "el blob se persiste cifrado vía SecureStore")

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/auth/password-login", request.path)
            assertTrue(
                request.getHeader("Content-Type")?.startsWith("application/json") == true,
                "el cuerpo del login es JSON",
            )
            assertNull(request.getHeader("Cookie"), "password-login no lleva cookies aún")
            val body = request.body.readUtf8()
            assertTrue("\"provider\":\"basic\"" in body)
            assertTrue("\"username\":\"usuario\"" in body)
            assertTrue("\"next\":\"\"" in body)
        }

    @Test
    fun `login 401 lanza InvalidCredentials y es terminal`() =
        runTest {
            val server = newServer()
            server.enqueue(json(401, "Invalid credentials"))
            val error =
                assertFailsWith<AuthException.InvalidCredentials> {
                    newSession(server).login("usuario", "mal")
                }
            assertIs<ConnectionFatalException>(error, "los errores de auth no se reintentan (§2.1.5)")
        }

    @Test
    fun `login 404 lanza ProviderMissing`() =
        runTest {
            val server = newServer()
            server.enqueue(json(404, "Unknown provider"))
            assertFailsWith<AuthException.ProviderMissing> {
                newSession(server).login("usuario", "mama")
            }
        }

    @Test
    fun `login 429 lanza RateLimited con el Retry-After del servidor`() =
        runTest {
            val server = newServer()
            server.enqueue(json(429, "Too many").addHeader("Retry-After", "7"))
            val error =
                assertFailsWith<AuthException.RateLimited> {
                    newSession(server).login("usuario", "mama")
                }
            assertEquals(7.seconds, error.retryAfter)
        }

    @Test
    fun `login 429 sin Retry-After lanza RateLimited con retryAfter null`() =
        runTest {
            val server = newServer()
            server.enqueue(json(429, "Too many"))
            assertNull(
                assertFailsWith<AuthException.RateLimited> {
                    newSession(server).login("usuario", "mama")
                }.retryAfter,
            )
        }

    @Test
    fun `un redirect en login NO se sigue y la contraseña no se reenvía a otro host`() =
        runTest {
            val server = newServer()
            server.enqueue(json(302, "").addHeader("Location", server.url("/login").toString()))
            assertFailsWith<AuthException.UnexpectedStatus> {
                newSession(server).login("usuario", "mama")
            }
            assertEquals(1, server.requestCount, "sin followRedirects la contraseña no se reemite")
        }

    // --- me (§2.1.2) ---

    @Test
    fun `me con cookie devuelve la identidad y sin cookie lanza SessionExpired`() =
        runTest {
            val server = newServer()
            server.enqueue(json(401, ""))
            server.enqueue(loginOk())
            server.enqueue(
                json(
                    200,
                    """{"user_id":"u_1","email":"usuario@hermes.example.invalid",
                        "display_name":"Usuario","provider":"basic","expires_at":1760000000}""",
                ),
            )
            val session = newSession(server)

            assertFailsWith<AuthException.SessionExpired> { session.me() }
            session.login("usuario", "mama")
            val identity = session.me()
            assertEquals("u_1", identity.userId)
            assertEquals("basic", identity.provider)
            assertEquals("usuario@hermes.example.invalid", identity.email)
            assertEquals(1760000000.0, identity.expiresAt)

            // La cookie de sesión viaja en la petición (la captura la jar).
            val meRequest = server.takeRequestSequence().last()
            assertNotNull(meRequest.getHeader("Cookie"), "me lleva las cookies del jar")
        }

    // --- wsTicket + wsUrl (§2.1.3–2.1.5) ---

    @Test
    fun `wsTicket devuelve ticket y ttl del servidor`() =
        runTest {
            val server = newServer()
            server.enqueue(loginOk())
            server.enqueue(ticketOk("tk-123"))
            val session = newSession(server)
            session.login("usuario", "mama")

            val ticket = session.wsTicket()
            assertEquals("tk-123", ticket.ticket)
            assertEquals(30, ticket.ttlSeconds)
        }

    @Test
    fun `wsTicket con 401 reintenta tras re-login una vez y devuelve el ticket`() =
        runTest {
            val server = newServer()
            server.enqueue(loginOk()) // login inicial
            server.enqueue(json(401, "")) // 1er ws-ticket: cookies caducadas
            server.enqueue(json(401, "")) // reintento bajo lock: siguen muertas
            server.enqueue(loginOk()) // re-login automático
            server.enqueue(ticketOk("tk-2")) // último ws-ticket
            val session = newSession(server)
            session.login("usuario", "mama")

            val ticket = session.wsTicket()

            assertEquals("tk-2", ticket.ticket)
            assertEquals(5, server.requestCount, "un solo re-login antes del último intento")
            val paths = server.takeRequestSequence().map(RecordedRequest::path)
            assertEquals(
                listOf(
                    "/auth/password-login",
                    "/api/auth/ws-ticket",
                    "/api/auth/ws-ticket",
                    "/auth/password-login",
                    "/api/auth/ws-ticket",
                ),
                paths,
            )
        }

    @Test
    fun `wsTicket con 401 y re-login fallido lanza SessionExpired`() =
        runTest {
            val server = newServer()
            server.enqueue(loginOk())
            server.enqueue(json(401, "")) // ws-ticket 401
            server.enqueue(json(401, "")) // reintento bajo lock: 401
            server.enqueue(json(401, "")) // re-login 401
            val session = newSession(server)
            session.login("usuario", "mama")

            assertFailsWith<AuthException.SessionExpired> { session.wsTicket() }
            assertEquals(4, server.requestCount, "re-login UNA vez — no más")
        }

    @Test
    fun `wsTicket con segundo 401 tras re-login lanza SessionExpired`() =
        runTest {
            val server = newServer()
            server.enqueue(loginOk())
            server.enqueue(json(401, "")) // ws-ticket 401
            server.enqueue(json(401, "")) // reintento bajo lock: 401
            server.enqueue(loginOk()) // re-login ok
            server.enqueue(json(401, "")) // ws-ticket sigue en 401
            val session = newSession(server)
            session.login("usuario", "mama")

            assertFailsWith<AuthException.SessionExpired> { session.wsTicket() }
        }

    @Test
    fun `wsTicket con 401 y sin credenciales guardadas lanza SessionExpired sin re-login`() =
        runTest {
            val server = newServer()
            server.enqueue(json(401, ""))
            val session = newSession(server)

            assertFailsWith<AuthException.SessionExpired> { session.wsTicket() }
            assertEquals(1, server.requestCount, "sin credenciales no hay re-login")
        }

    @Test
    fun `wsTicket reintenta con credenciales restauradas por setCredentials`() =
        runTest {
            val server = newServer()
            server.enqueue(json(401, "")) // ws-ticket 401 (app recién arrancada, cookies viejas)
            server.enqueue(json(401, "")) // reintento bajo lock: siguen muertas
            server.enqueue(loginOk()) // re-login con creds restauradas
            server.enqueue(ticketOk("tk-9"))
            val session = newSession(server)
            session.setCredentials(Credentials("usuario", "mama"))

            assertEquals("tk-9", session.wsTicket().ticket)
            val loginRequest = server.takeRequestSequence()[2]
            assertTrue("\"username\":\"usuario\"" in loginRequest.body.readUtf8())
        }

    @Test
    fun `dos wsTicket concurrentes con cookies muertas provocan un solo re-login`() =
        runTest {
            val server = newServer()
            // Todo ticket da 401 hasta que llega un password-login (cookies
            // frescas): determinista en cualquier interleaving — el perdedor
            // del lock reintenta el ticket con el jar ya refrescado y no
            // necesita re-loguear.
            val loggedIn = AtomicBoolean(false)
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        when {
                            request.path == "/auth/password-login" -> {
                                loggedIn.set(true)
                                loginOk()
                            }
                            request.path == "/api/auth/ws-ticket" && !loggedIn.get() -> json(401, "")
                            request.path == "/api/auth/ws-ticket" -> ticketOk("tk-conc")
                            else -> json(404, "")
                        }
                }
            val session = newSession(server)
            session.setCredentials(Credentials("usuario", "mama"))

            val tickets =
                coroutineScope {
                    listOf(
                        async { session.wsTicket() },
                        async { session.wsTicket() },
                    ).map { it.await().ticket }
                }

            assertEquals(listOf("tk-conc", "tk-conc"), tickets)
            val logins =
                server.takeRequestSequence().count { it.path == "/auth/password-login" }
            assertEquals(1, logins, "el segundo wsTicket reutiliza el jar del primer re-login")
        }

    // --- wsUrl / connectParams (§2.1.4, §8) ---

    @Test
    fun `wsUrl convierte https a wss y conserva el prefijo de path`() {
        val session =
            BasicAuthSession("https://hermes.example.invalid".toHttpUrl())
        val url = session.wsUrl("tk-1")
        assertTrue(url.startsWith("wss://hermes.example.invalid/api/ws?ticket="), "https → wss")
        assertTrue("ticket=tk-1" in url)

        val prefixed = BasicAuthSession("https://hermes.example.invalid/p/".toHttpUrl())
        assertTrue(
            prefixed.wsUrl("tk-1").startsWith("wss://hermes.example.invalid/p/api/ws?ticket="),
            "un base con prefijo lo conserva (X-Forwarded-Prefix)",
        )
    }

    @Test
    fun `wsUrl convierte http a ws solo en hosts loopback o emulador`() {
        listOf("127.0.0.1", "localhost", "10.0.2.2").forEach { host ->
            val session = BasicAuthSession("http://$host:8399".toHttpUrl())
            assertTrue(
                session.wsUrl("t").startsWith("ws://$host:8399/api/ws?ticket="),
                "$host es loopback/emulador → ws permitido",
            )
        }
        assertFailsWith<AuthException.CleartextForbidden> {
            BasicAuthSession("http://hermes.example.invalid".toHttpUrl())
        }
        // "127." como prefijo de un dominio NO es loopback: exige dotted-quad real.
        assertFailsWith<AuthException.CleartextForbidden> {
            BasicAuthSession("http://127.evil.com".toHttpUrl())
        }
        assertFailsWith<AuthException.CleartextForbidden> {
            BasicAuthSession("http://127.0.0.1.evil.com".toHttpUrl())
        }
        assertFailsWith<AuthException.CleartextForbidden> {
            BasicAuthSession("http://127.999.0.1".toHttpUrl())
        }
        // Flavor dev: cleartext a cualquier host.
        val dev = BasicAuthSession("http://hermes.example.invalid".toHttpUrl(), allowCleartext = true)
        assertTrue(dev.wsUrl("t").startsWith("ws://hermes.example.invalid/api/ws"))
    }

    @Test
    fun `connectParams mintea un ticket nuevo por intento y compone el ConnectParams`() =
        runTest {
            val server = newServer()
            server.enqueue(loginOk())
            server.enqueue(ticketOk("tk-uno"))
            server.enqueue(ticketOk("tk-dos"))
            val session = newSession(server)
            session.login("usuario", "mama")

            val first = session.connectParams()
            val second = session.connectParams()

            assertTrue(first.url.startsWith("ws://"), "MockWebServer es http → ws")
            assertTrue("ticket=tk-uno" in first.url)
            assertTrue("ticket=tk-dos" in second.url, "cada intento lleva ticket nuevo (un solo uso)")
        }

    @Test
    fun `MalformedResponse no filtra el cuerpo en su cause ni su mensaje`() =
        runTest {
            val server = newServer()
            // JSON truncado: la SerializationException incrusta el fragmento.
            server.enqueue(json(200, """{"ticket":"SECRETO-EN-BODY-7"""))
            val session = newSession(server)

            val error =
                assertFailsWith<AuthException.MalformedResponse> {
                    session.wsTicket()
                }
            assertTrue("SECRETO-EN-BODY-7" !in (error.message ?: ""), "message limpio (§8)")
            assertTrue(
                "SECRETO-EN-BODY-7" !in (error.cause?.message ?: ""),
                "§8: la cause va saneada — sólo el tipo de la excepción",
            )
        }

    // --- §8: nada de secretos en logs ni en toString ---

    @Test
    fun `los logs y toString nunca llevan password cookie ni ticket`() =
        runTest {
            val server = newServer()
            server.enqueue(
                json(200, """{"ok":true,"next":"/"}""")
                    .addHeader("Set-Cookie", "hermes_session_at=COOKIE-UNICA-99; HttpOnly; Path=/"),
            )
            server.enqueue(ticketOk("TICKET-UNICO-77"))
            val messages = mutableListOf<String>()
            val session = newSession(server, logger = { messages += it })
            session.login("usuario", "PASS-UNICA-55")
            val ticket = session.wsTicket()
            val params = ConnectParams(url = session.wsUrl(ticket.ticket))

            val leakables = listOf("PASS-UNICA-55", "COOKIE-UNICA-99", "TICKET-UNICO-77")
            messages.forEach { msg ->
                leakables.forEach { secret -> assertTrue(secret !in msg, "§8: '$secret' jamás en logs") }
            }
            leakables.forEach { secret ->
                assertTrue(secret !in ticket.toString(), "§8: '$secret' jamás en toString")
                assertTrue(secret !in Credentials("u", secret).toString())
                assertTrue(secret !in params.toString())
            }
        }

    // --- MockWebServer helper: drena la cola de peticiones grabadas ---

    private fun MockWebServer.takeRequestSequence(): List<RecordedRequest> =
        generateSequence {
            takeRequest(REQUEST_DRAIN_MS, TimeUnit.MILLISECONDS)
        }.toList()

    private companion object {
        const val REQUEST_DRAIN_MS = 500L
    }
}
