package ai.hermes.mama.testing

import ai.hermes.mama.contract.RpcMethods
import ai.hermes.mama.gateway.JsonRpcChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration

/** Canal §2.2 del cliente REAL (B1) sobre un WebSocket OkHttp contra el fake. */
internal suspend fun CoroutineScope.channelTo(
    wsUrl: String,
    client: OkHttpClient,
): JsonRpcChannel {
    val transport = OkHttpWsTransport.connect(wsUrl, client)
    return JsonRpcChannel(
        transport = transport,
        scope = CoroutineScope(SupervisorJob() + coroutineContext),
        heartbeatInterval = Duration.INFINITE,
    )
}

/**
 * Login `usuario`/`mama` y mint de un ticket WS: el camino que sella la
 * identidad AUTENTICADA que exige `browser.controller.*` (§2.1/§2.6). Sin
 * CookieJar: la cookie `hermes_session_at` se captura del `set-cookie` del
 * login y se reenvía tal cual al ws-ticket.
 */
internal fun mintTicket(
    gw: FakeGateway,
    client: OkHttpClient,
): String {
    val jsonMedia = "application/json".toMediaType()
    val cookie =
        client
            .newCall(
                Request
                    .Builder()
                    .url("${gw.httpUrl}/auth/password-login")
                    .post(
                        """{"provider":"basic","username":"usuario","password":"mama"}"""
                            .toRequestBody(jsonMedia),
                    ).build(),
            ).execute()
            .use { res ->
                check(res.code == 200) { "login fake falló: ${res.code}" }
                // header() devuelve la ÚLTIMA set-cookie (la del provider):
                // hay que buscar la de sesión entre todas.
                res
                    .headers("set-cookie")
                    .map { it.substringBefore(";") }
                    .firstOrNull { it.startsWith("${FakeAuth.SESSION_COOKIE}=") }
                    ?: error("login fake sin set-cookie de sesión")
            }
    return client
        .newCall(
            Request
                .Builder()
                .url("${gw.httpUrl}/api/auth/ws-ticket")
                .header("Cookie", cookie)
                .post("{}".toRequestBody(jsonMedia))
                .build(),
        ).execute()
        .use { res ->
            check(res.code == 200) { "ws-ticket fake falló: ${res.code}" }
            (Json.parseToJsonElement(res.body?.string().orEmpty()) as JsonObject)
                .getValue("ticket")
                .jsonPrimitive
                .content
        }
}

/** `session.create` con un título de test; devuelve el `result` completo. */
internal suspend fun createFakeSession(channel: JsonRpcChannel): JsonObject =
    channel.call(
        RpcMethods.SESSION_CREATE,
        buildJsonObject { put("title", "Chat de prueba") },
    ) as JsonObject

/** `session_id` runtime de un `result` de `session.create`/`session.resume`. */
internal fun JsonObject.fakeSessionId(): String = getValue("session_id").jsonPrimitive.content
