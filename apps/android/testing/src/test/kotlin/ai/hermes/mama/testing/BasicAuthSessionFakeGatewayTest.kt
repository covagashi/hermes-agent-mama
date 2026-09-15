package ai.hermes.mama.testing

import ai.hermes.mama.gateway.AuthException
import ai.hermes.mama.gateway.BasicAuthSession
import ai.hermes.mama.gateway.WebSocketTransport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * [BasicAuthSession] (tarea B3) contra el [FakeGateway] REAL (B5): el flujo
 * §2.1 completo — password-login → cookies → me → ws-ticket → `?ticket=` en
 * `/api/ws` — incluido el ticket de un solo uso que el fake consume de verdad.
 */
@Timeout(30)
class BasicAuthSessionFakeGatewayTest {
    private var gateway: FakeGateway? = null
    private val clients = java.util.concurrent.CopyOnWriteArrayList<OkHttpClient>()

    @AfterEach
    fun tearDown() {
        gateway?.close()
        gateway = null
        clients.forEach {
            it.dispatcher.executorService.shutdown()
            it.connectionPool.evictAll()
        }
        clients.clear()
    }

    private fun startGateway(scriptName: String): FakeGateway =
        FakeGateway(FakeGatewayScript.load(scriptName), logger = {}).start().also { gateway = it }

    private fun newSession(gw: FakeGateway): BasicAuthSession =
        BasicAuthSession(baseUrl = gw.httpUrl.toHttpUrl(), client = trackClient())

    private fun trackClient(): OkHttpClient = OkHttpClient().also(clients::add)

    @Test
    fun `flujo completo §2·1 — login, me, ws-ticket y el ticket abre el websocket una sola vez`() =
        runBlocking {
            val gw = startGateway("ticket_requerido") // require_ws_ticket=true
            val session = newSession(gw)

            session.login("usuario", "mama")
            assertEquals("u_fake_usuario", session.me().userId)

            val ticket = session.wsTicket()
            assertEquals(FakeAuth.TICKET_TTL_S, ticket.ttlSeconds)
            val wsUrl = session.wsUrl(ticket.ticket)
            assertTrue(wsUrl.startsWith("ws://${gw.clientHost}:${gw.port}/api/ws?ticket="))

            // Primer uso: upgrade + gateway.ready (transporte REAL de B2).
            val first = WebSocketTransport(wsUrl, okHttpClient = session.httpClient)
            first.awaitOpen()
            val ready =
                Json.parseToJsonElement(
                    withTimeout(READY_TIMEOUT_MS) { first.incoming.first() },
                ) as JsonObject
            assertEquals("event", ready["method"]?.jsonPrimitive?.contentOrNull)
            assertEquals(
                "gateway.ready",
                (ready["params"] as JsonObject)["type"]?.jsonPrimitive?.contentOrNull,
            )
            first.close()

            // Segundo uso del MISMO ticket: el fake lo consumió → cierre 4401
            // (auth: ticket_invalid), igual que el backend real.
            val second = OkHttpWsTransport.connect(wsUrl, trackClient())
            assertEquals(
                WS_CLOSE_AUTH,
                withTimeout(READY_TIMEOUT_MS) { second.closedCode.await() },
                "el ticket de un solo uso no admite reutilización",
            )
        }

    @Test
    fun `login con password malo da InvalidCredentials`() =
        runBlocking {
            val gw = startGateway("hola_mundo")
            assertFailsWith<AuthException.InvalidCredentials> {
                newSession(gw).login("usuario", "mal")
            }
        }

    @Test
    fun `login con guion rate_limited da RateLimited con retryAfter`() =
        runBlocking {
            val gw = startGateway("rate_limited")
            val error =
                assertFailsWith<AuthException.RateLimited> {
                    newSession(gw).login("usuario", "mama")
                }
            assertEquals(7.seconds, error.retryAfter, "el fake manda Retry-After: 7")
        }

    private companion object {
        const val READY_TIMEOUT_MS = 15_000L

        /** Close code del backend real para credencial mala en /api/ws. */
        const val WS_CLOSE_AUTH = 4401
    }
}
