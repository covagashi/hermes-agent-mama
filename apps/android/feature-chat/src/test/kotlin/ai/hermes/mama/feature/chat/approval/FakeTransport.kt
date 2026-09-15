package ai.hermes.mama.feature.chat.approval

import ai.hermes.mama.gateway.Transport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [Transport] en memoria para los tests de feature-chat (el de core-gateway
 * vive en su test sourceSet y Gradle no lo publica). Mismo modelo que el de
 * B4: [sent] graba cada frame saliente y [emit] inyecta entrantes.
 */
class FakeTransport(
    private val json: Json = Json { ignoreUnknownKeys = true },
) : Transport {
    /** Frames tal cual salieron del canal (orden de `send`). */
    val sent = CopyOnWriteArrayList<String>()

    private val _incoming = Channel<String>(capacity = Channel.UNLIMITED)
    override val incoming: Flow<String> = _incoming.receiveAsFlow()

    @Volatile
    var closed: Boolean = false
        private set

    /** Si no es null, el próximo `send` lanza esta excepción (socket roto). */
    @Volatile
    var failOnSend: Throwable? = null

    override suspend fun send(text: String) {
        failOnSend?.let { throw it }
        check(!closed) { "send sobre un transport cerrado" }
        sent += text
    }

    /** Inyecta un frame entrante (texto tal cual llegaría del socket). */
    suspend fun emit(text: String) {
        _incoming.send(text)
    }

    /** Los frames enviados, ya parseados. */
    fun sentFrames(): List<JsonObject> = sent.map { json.parseToJsonElement(it).jsonObject }

    /**
     * Sólo las llamadas RPC cliente→servidor (tienen `method`): descarta las
     * respuestas a peticiones del servidor y los pings.
     */
    fun sentCalls(method: String? = null): List<JsonObject> =
        sentFrames()
            .filter { it.containsKey("method") }
            .filter { method == null || it.getValue("method").jsonPrimitive.contentOrNull == method }

    /** Respuestas a peticiones servidor→cliente (tienen `id` string + `result`/`error`). */
    fun sentResponses(): List<JsonObject> = sentFrames().filter { !it.containsKey("method") }

    override suspend fun close() {
        closed = true
        _incoming.close()
    }
}
