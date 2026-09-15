package ai.hermes.mama.feature.chat.attachments

import ai.hermes.mama.contract.RpcMethods
import ai.hermes.mama.gateway.Transport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [Transport] en memoria para los tests de [ImageAttacher]: graba cada frame y
 * responde `image.attach_bytes` con `{attached:true, name:<filename>}` — eco del
 * param enviado, como hace `_attached_image_result` en `methods_prompt.py`.
 *
 * [failOnSend] simula un socket roto y [rpcErrorOnAttach] una respuesta
 * JSON-RPC de error: ambos deben acabar en [ImageAttachError.SendFailed].
 */
class RecordingTransport(
    private val json: Json = Json { ignoreUnknownKeys = true },
) : Transport {
    val sent = CopyOnWriteArrayList<String>()

    private val _incoming = Channel<String>(capacity = Channel.UNLIMITED)
    override val incoming: Flow<String> = _incoming.receiveAsFlow()

    @Volatile
    var failOnSend: Throwable? = null

    @Volatile
    var rpcErrorOnAttach: Boolean = false

    override suspend fun send(text: String) {
        failOnSend?.let { throw it }
        sent += text
        val frame = json.parseToJsonElement(text).jsonObject
        if (frame["method"]?.jsonPrimitive?.contentOrNull == RpcMethods.IMAGE_ATTACH_BYTES) {
            respondAttach(frame)
        }
    }

    private suspend fun respondAttach(frame: JsonObject) {
        val id = frame.getValue("id").jsonPrimitive.long
        val response =
            if (rpcErrorOnAttach) {
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put(
                        "error",
                        buildJsonObject {
                            put("code", -32000)
                            put("message", "attach failed")
                        },
                    )
                }
            } else {
                val filename =
                    frame["params"]
                        ?.jsonObject
                        ?.get("filename")
                        ?.jsonPrimitive
                        ?.contentOrNull
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put(
                        "result",
                        buildJsonObject {
                            put("attached", true)
                            put("name", filename)
                        },
                    )
                }
            }
        _incoming.send(response.toString())
    }

    fun sentFrames(): List<JsonObject> = sent.map { json.parseToJsonElement(it).jsonObject }

    override suspend fun close() {
        _incoming.close()
    }
}
