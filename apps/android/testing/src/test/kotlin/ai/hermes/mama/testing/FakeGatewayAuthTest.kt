package ai.hermes.mama.testing

import ai.hermes.mama.gateway.JsonRpcChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Auth HTTP del [FakeGateway] (ROADMAP §2.1): `password-login` → cookies →
 * `me`/`ws-ticket` → `?ticket=` de un solo uso en `/api/ws`.
 */
class FakeGatewayAuthTest {
    private var gateway: FakeGateway? = null

    @AfterEach
    fun tearDown() {
        gateway?.close()
        gateway = null
    }

    private fun startGateway(scriptName: String = "hola_mundo"): FakeGateway =
        FakeGateway(FakeGatewayScript.load(scriptName), logger = {}).start().also { gateway = it }

    /** CookieJar mínimo en memoria: guarda todo y devuelve las cookies que casan con el host. */
    private class MemoryCookieJar : CookieJar {
        private val store = java.util.concurrent.CopyOnWriteArrayList<Cookie>()

        override fun saveFromResponse(
            url: HttpUrl,
            cookies: List<Cookie>,
        ) {
            store.addAll(cookies)
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> = store.filter { it.matches(url) }
    }

    private fun newClient(): OkHttpClient =
        OkHttpClient
            .Builder()
            .cookieJar(MemoryCookieJar())
            .build()

    private fun OkHttpClient.post(
        url: String,
        body: String,
    ): Response =
        newCall(
            Request
                .Builder()
                .url(url)
                .post(body.toRequestBody(JSON_MEDIA))
                .build(),
        ).execute()

    private fun OkHttpClient.get(url: String): Response =
        newCall(
            Request
                .Builder()
                .url(url)
                .get()
                .build(),
        ).execute()

    private fun loginBody(
        user: String = "usuario",
        pass: String = "mama",
    ): String = """{"provider":"basic","username":"$user","password":"$pass"}"""

    @Test
    fun `login con credenciales malas da 401`() {
        val gw = startGateway()
        val client = newClient()
        client.post("${gw.httpUrl}/auth/password-login", loginBody(pass = "mal")).use { res ->
            assertEquals(401, res.code)
        }
    }

    @Test
    fun `proveedor desconocido da 404`() {
        val gw = startGateway()
        val client = newClient()
        client
            .post(
                "${gw.httpUrl}/auth/password-login",
                """{"provider":"sso_inventado","username":"usuario","password":"mama"}""",
            ).use { res ->
                assertEquals(404, res.code)
            }
    }

    @Test
    fun `me sin cookie da 401 y con cookie devuelve la identidad`() {
        val gw = startGateway()
        val client = newClient()
        client.get("${gw.httpUrl}/api/auth/me").use { res ->
            assertEquals(401, res.code)
        }
        client.post("${gw.httpUrl}/auth/password-login", loginBody()).use { res ->
            assertEquals(200, res.code)
            assertNotNull(res.header("Set-Cookie"), "password-login debe emitir Set-Cookie")
        }
        client.get("${gw.httpUrl}/api/auth/me").use { res ->
            assertEquals(200, res.code)
            val me = Json.parseToJsonElement(res.body?.string().orEmpty()) as JsonObject
            assertEquals("u_fake_usuario", me["user_id"]?.jsonPrimitive?.contentOrNull)
            assertEquals("basic", me["provider"]?.jsonPrimitive?.contentOrNull)
        }
    }

    @Test
    fun `ws-ticket requiere cookie y el ticket abre el websocket una sola vez`() =
        runBlocking {
            val gw = startGateway()
            val client = newClient()
            client.post("${gw.httpUrl}/api/auth/ws-ticket", "{}").use { res ->
                assertEquals(401, res.code, "ws-ticket sin cookie debe dar 401")
            }
            client.post("${gw.httpUrl}/auth/password-login", loginBody()).use { res ->
                assertEquals(200, res.code)
            }
            val ticket =
                client.post("${gw.httpUrl}/api/auth/ws-ticket", "{}").use { res ->
                    assertEquals(200, res.code)
                    val body = Json.parseToJsonElement(res.body?.string().orEmpty()) as JsonObject
                    body.getValue("ticket").jsonPrimitive.content
                }
            assertTrue(ticket.isNotEmpty())

            // Primer uso: conecta y recibe gateway.ready.
            val transport = OkHttpWsTransport.connect("${gw.wsUrl}?ticket=$ticket", client)
            val channel =
                JsonRpcChannel(
                    transport = transport,
                    scope = CoroutineScope(SupervisorJob() + coroutineContext),
                    heartbeatInterval = Duration.INFINITE,
                )
            try {
                val ready =
                    withTimeout(READY_TIMEOUT_MS) {
                        channel.events.first()
                    }
                assertEquals("gateway.ready", ready.type)
            } finally {
                channel.close()
            }

            // Segundo uso del MISMO ticket: el servidor rechaza el upgrade con 401.
            assertFailsWith<IOException> {
                OkHttpWsTransport.connect("${gw.wsUrl}?ticket=$ticket", client)
            }
        }

    private companion object {
        const val READY_TIMEOUT_MS = 15_000L
        val JSON_MEDIA = "application/json".toMediaType()
    }
}
