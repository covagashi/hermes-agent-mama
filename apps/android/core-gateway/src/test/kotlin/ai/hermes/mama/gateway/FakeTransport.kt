package ai.hermes.mama.gateway

import ai.hermes.mama.contract.RpcMethods
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [Transport] en memoria para tests JVM: graba cada frame enviado y deja inyectar
 * frames entrantes con [emit]. Un [Channel] ilimitado modela el socket real: los
 * frames esperan al lector en vez de descartarse si aún no hay suscriptor.
 *
 * Con `autoPong` responde a `gateway.ping` con `{"ok": true}` — que es lo que
 * devuelve `tui_gateway/ws.py` por WS (§2.2; `PingResult {pong}` es del `ping`
 * stdio, errata conocida del contrato anotada en el PR de A3).
 */
class FakeTransport(
    private val json: Json = Json,
    var autoPong: Boolean = false,
) : Transport {
    /** Frames tal cual salieron del canal (orden de `send`). */
    val sent = CopyOnWriteArrayList<String>()

    private val _incoming = Channel<String>(capacity = Channel.UNLIMITED)
    override val incoming: Flow<String> = _incoming.receiveAsFlow()

    @Volatile
    var closed: Boolean = false
        private set

    /** Si no es null, el próximo `send` lanza esta excepción (simula socket roto). */
    @Volatile
    var failOnSend: Throwable? = null

    override suspend fun send(text: String) {
        failOnSend?.let { throw it }
        check(!closed) { "send sobre un transport cerrado" }
        sent += text
        if (autoPong) {
            val frame = json.parseToJsonElement(text).jsonObject
            if (frame["method"]?.jsonPrimitive?.contentOrNull == RpcMethods.GATEWAY_PING) {
                val id = frame.getValue("id").jsonPrimitive.long
                emit("""{"jsonrpc":"2.0","id":$id,"result":{"ok":true}}""")
            }
        }
    }

    /** Inyecta un frame entrante (texto tal cual llegaría del socket). */
    suspend fun emit(text: String) {
        _incoming.send(text)
    }

    /** Falla el flujo entrante como un socket roto (IOException de OkHttp, por ejemplo). */
    fun failIncoming(cause: Throwable) {
        _incoming.close(cause)
    }

    /** Los frames enviados, ya parseados. */
    fun sentFrames(): List<JsonObject> = sent.map { json.parseToJsonElement(it).jsonObject }

    override suspend fun close() {
        closed = true
        _incoming.close()
    }
}
