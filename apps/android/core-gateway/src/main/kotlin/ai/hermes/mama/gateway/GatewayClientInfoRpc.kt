package ai.hermes.mama.gateway

import ai.hermes.mama.contract.GatewayCapabilitiesResult
import ai.hermes.mama.contract.OkResult
import ai.hermes.mama.contract.RpcMethods

/*
 * Métodos `gateway.*` de §2.3 sobre GatewayClient: heartbeat manual y
 * diagnóstico. El heartbeat automático cada 15 s ya lo hace el canal (B1);
 * `ping` sirve para comprobaciones puntuales.
 */

/**
 * `gateway.ping` (§2.2). Por WebSocket el gateway responde `{"ok":true}` →
 * [OkResult]; `PingResult {pong}` es la forma del `ping` stdio (errata del
 * contrato ya anotada en A3/B1).
 */
suspend fun GatewayClient.ping(): OkResult = rpc(RpcMethods.GATEWAY_PING, OkResult.serializer())

/** `gateway.capabilities` — diagnóstico (sólo la usa el flavor `dev`, §2.3). */
suspend fun GatewayClient.capabilities(): GatewayCapabilitiesResult =
    rpc(RpcMethods.GATEWAY_CAPABILITIES, GatewayCapabilitiesResult.serializer())
