package ai.hermes.mama.gateway

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Petición servidor→cliente entrante (ROADMAP §2.2/§2.5):
 * `{"id":"srq-…","method":"approval","params":{…}}`.
 *
 * El `id` es **string** (los ids numéricos son del cliente) y viaja de vuelta tal
 * cual en la respuesta. [respond]/[fail] son idempotentes: la primera gana y las
 * siguientes son no-op — tras una reconexión el backend puede re-entregar la
 * misma petición con el mismo `id` y responder dos veces no debe emitir un
 * segundo frame.
 */
class ServerRequest internal constructor(
    /** `id` string tal cual vino (p. ej. `srq-…`). */
    val id: String,
    /** `method` — p. ej. `approval`, `clarify`, `sudo` (ver `ServerRequests` en core-contract). */
    val method: String,
    /** `params` tal cual vinieron; `{}` si la petición no los traía o no eran objeto. */
    val params: JsonObject,
    /** `true` si llegó re-entregada dentro de `open_requests` de un result (reconexión). */
    val replayed: Boolean,
    private val sendResponseFrame: (JsonObject) -> Unit,
) {
    private val answered = AtomicBoolean(false)

    /** `true` una vez que [respond] o [fail] han aceptado una respuesta. */
    val isAnswered: Boolean
        get() = answered.get()

    /**
     * Responde `{"jsonrpc":"2.0","id":…,"result":result}`. No es `suspend`: el envío
     * corre en el scope del canal. Devuelve `false` si ya estaba respondida (no-op).
     */
    fun respond(result: JsonElement): Boolean = sendOnce { put("result", result) }

    /**
     * Responde `{"jsonrpc":"2.0","id":…,"error":{code,message}}`. Devuelve `false`
     * si ya estaba respondida (no-op).
     */
    fun fail(
        code: Int,
        message: String,
    ): Boolean =
        sendOnce {
            put(
                "error",
                buildJsonObject {
                    put("code", code)
                    put("message", message)
                },
            )
        }

    private fun sendOnce(body: JsonObjectBuilder.() -> Unit): Boolean {
        if (!answered.compareAndSet(false, true)) {
            return false
        }
        sendResponseFrame(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                body()
            },
        )
        return true
    }
}

/**
 * Reclama peticiones servidor→cliente antes de la respuesta automática `-32601`.
 * Devuelve `true` si se hace cargo de la petición (la responderá después con
 * [ServerRequest.respond]/[ServerRequest.fail]); `false` para pasar al siguiente.
 */
fun interface ServerRequestHandler {
    fun accepts(request: ServerRequest): Boolean
}
