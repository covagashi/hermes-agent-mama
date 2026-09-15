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
 * responde `image.attach_bytes` con el shape real del backend:
 * `{attached:true, name:"upload_<ts>_<n>.<ext>", …}` — `_queue_attached_image`
 * en `prompt_attachments.py` genera el nombre en el servidor (NO devuelve el
 * `filename` del cliente); la extensión la saca del filename enviado, como
 * `_sniff_image_ext`.
 *
 * [failOnSend] simula un socket roto, [rpcErrorOnAttach] una respuesta
 * JSON-RPC de error y [attachedFalse] un `{attached:false}` — los tres deben
 * acabar en [ImageAttachError.SendFailed].
 */
class RecordingTransport(
    private val json: Json = Json { ignoreUnknownKeys = true },
) : Transport {
    val sent = CopyOnWriteArrayList<String>()

    private val _incoming = Channel<String>(capacity = Channel.UNLIMITED)
    override val incoming: Flow<String> = _incoming.receiveAsFlow()

    private var attachCount = 0

    @Volatile
    var failOnSend: Throwable? = null

    @Volatile
    var rpcErrorOnAttach: Boolean = false

    @Volatile
    var attachedFalse: Boolean = false

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
                attachCount++
                // El backend nombra upload_<ts>_<counter>.<ext> y la ext sale
                // del filename del cliente (_sniff_image_ext) — no devuelve
                // el nombre original tal cual.
                val ext =
                    frame["params"]
                        ?.jsonObject
                        ?.get("filename")
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.substringAfterLast('.', "")
                        ?.let { if (it.isBlank()) ".png" else ".$it" }
                        ?: ".png"
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put(
                        "result",
                        buildJsonObject {
                            put("attached", !attachedFalse)
                            put("name", "upload_20260101_000000_$attachCount$ext")
                            if (attachedFalse) {
                                put("message", "could not queue image")
                            }
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
