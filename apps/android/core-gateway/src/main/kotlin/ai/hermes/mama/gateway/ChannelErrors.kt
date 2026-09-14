package ai.hermes.mama.gateway

import kotlinx.serialization.json.JsonElement
import kotlin.time.Duration

/** Base de los fallos del canal JSON-RPC (ROADMAP §2.2). */
sealed class ChannelException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Respuesta `{"id":N,"error":{code,message,data}}` del gateway; `data` se preserva tal cual. */
class JsonRpcException(
    val code: Int?,
    message: String,
    val data: JsonElement? = null,
) : ChannelException(message)

/** Una [JsonRpcChannel.call] superó su timeout (120 s por defecto, §2.2) sin respuesta. */
class JsonRpcTimeoutException(
    val method: String,
    val timeout: Duration,
    cause: Throwable? = null,
) : ChannelException("request timed out after ${timeout.inWholeSeconds}s: $method", cause)

/** El canal está cerrado/muerto: toda llamada pendiente o nueva falla con esto (o con la causa real). */
open class ChannelClosedException(
    message: String = "JSON-RPC channel is closed",
    cause: Throwable? = null,
) : ChannelException(message, cause)

/** Heartbeat §2.2: ninguna respuesta (pong o result/error correlacionado) dentro del `deadline`. */
class HeartbeatTimeoutException(
    val deadline: Duration,
    cause: Throwable? = null,
) : ChannelClosedException("no gateway response within ${deadline.inWholeSeconds}s heartbeat deadline", cause)
