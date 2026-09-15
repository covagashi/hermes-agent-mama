package ai.hermes.mama.gateway

import ai.hermes.mama.contract.BrowserControllerDetachResult
import ai.hermes.mama.contract.BrowserControllerParams
import ai.hermes.mama.contract.BrowserControllerRegisterParams
import ai.hermes.mama.contract.BrowserControllerRegisterResult
import ai.hermes.mama.contract.BrowserControllerResultParams
import ai.hermes.mama.contract.BrowserControllerResultResult
import ai.hermes.mama.contract.OkResult
import ai.hermes.mama.contract.RpcMethods
import kotlinx.serialization.json.JsonPrimitive

/*
 * Métodos `browser.controller.*` de §2.3/§2.6 sobre GatewayClient (navegador
 * visible, hito M5): registro con `protocol_version` fija, heartbeat mientras la
 * pantalla esté activa, resultado de comandos y detach al salir.
 */

/** `browser.controller.register` — una vez por sesión de chat (§2.6). */
suspend fun GatewayClient.registerBrowserController(
    params: BrowserControllerRegisterParams,
): BrowserControllerRegisterResult =
    rpc(
        RpcMethods.BROWSER_CONTROLLER_REGISTER,
        params,
        BrowserControllerRegisterParams.serializer(),
        BrowserControllerRegisterResult.serializer(),
    )

/** Registro con la forma de la app: `protocol_version` = [BROWSER_CONTROLLER_PROTOCOL_VERSION] (§2.6). */
suspend fun GatewayClient.registerBrowserController(
    sessionId: String,
    controllerId: String,
    browserProfileId: String,
    capabilities: List<String>,
): BrowserControllerRegisterResult =
    registerBrowserController(
        BrowserControllerRegisterParams(
            sessionId = sessionId,
            controllerId = controllerId,
            browserProfileId = browserProfileId,
            capabilities = capabilities,
            protocolVersion = JsonPrimitive(BROWSER_CONTROLLER_PROTOCOL_VERSION),
        ),
    )

/** `browser.controller.result` — entrega el resultado de un `browser.controller.command` (§2.6). */
suspend fun GatewayClient.sendBrowserControllerResult(
    params: BrowserControllerResultParams,
): BrowserControllerResultResult =
    rpc(
        RpcMethods.BROWSER_CONTROLLER_RESULT,
        params,
        BrowserControllerResultParams.serializer(),
        BrowserControllerResultResult.serializer(),
    )

/**
 * Resultado de un comando (§2.6): `resultJson` es el **string** JSON con la
 * forma de las herramientas locales (no un JsonElement — el wire lo lleva como
 * string) y `error` el motivo humano cuando `ok` es `false`.
 */
suspend fun GatewayClient.sendBrowserControllerResult(
    sessionId: String,
    commandId: String,
    ok: Boolean,
    resultJson: String? = null,
    error: String? = null,
): BrowserControllerResultResult =
    sendBrowserControllerResult(
        BrowserControllerResultParams(
            sessionId = sessionId,
            commandId = commandId,
            ok = JsonPrimitive(ok),
            result = resultJson?.let(::JsonPrimitive),
            error = error?.let(::JsonPrimitive),
        ),
    )

/** `browser.controller.heartbeat` — cada 20 s mientras la pantalla Navegador esté activa (§2.6). */
suspend fun GatewayClient.browserControllerHeartbeat(params: BrowserControllerParams): OkResult =
    rpc(RpcMethods.BROWSER_CONTROLLER_HEARTBEAT, params, BrowserControllerParams.serializer(), OkResult.serializer())

/** `browser.controller.heartbeat` por runtime `session_id`. */
suspend fun GatewayClient.browserControllerHeartbeat(sessionId: String): OkResult =
    browserControllerHeartbeat(BrowserControllerParams(sessionId = sessionId))

/** `browser.controller.detach` — al cerrar la pantalla Navegador (§2.6). */
suspend fun GatewayClient.detachBrowserController(params: BrowserControllerParams): BrowserControllerDetachResult =
    rpc(
        RpcMethods.BROWSER_CONTROLLER_DETACH,
        params,
        BrowserControllerParams.serializer(),
        BrowserControllerDetachResult.serializer(),
    )

/** `browser.controller.detach` por runtime `session_id`. */
suspend fun GatewayClient.detachBrowserController(sessionId: String): BrowserControllerDetachResult =
    detachBrowserController(BrowserControllerParams(sessionId = sessionId))

private const val BROWSER_CONTROLLER_PROTOCOL_VERSION = 1
