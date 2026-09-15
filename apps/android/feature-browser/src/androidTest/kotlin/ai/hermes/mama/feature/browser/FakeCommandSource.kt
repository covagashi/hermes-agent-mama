package ai.hermes.mama.feature.browser

import ai.hermes.mama.contract.BrowserControllerCommandPayload
import ai.hermes.mama.core.controller.BrowserCommand
import ai.hermes.mama.core.controller.BrowserCommandOutcome
import ai.hermes.mama.core.controller.WebViewController
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fuente de comandos de mentira para los tests instrumentados de F2.
 *
 * Juega el papel que tendrá `FakeGateway` (B5) + `ControllerSession` (F3):
 * construye payloads `browser.controller.command` con la misma forma que el
 * frame del broker (ver `testing/fixtures/events/BrowserControllerCommandPayload.json`),
 * los convierte a [BrowserCommand] y recoge el [BrowserCommandOutcome] que
 * producción mandaría por `browser.controller.result`.
 */
class FakeCommandSource(
    private val controller: WebViewController,
) {
    data class Recorded(
        val commandId: String,
        val action: String,
        val outcome: BrowserCommandOutcome,
    )

    private val nextId = AtomicInteger(1)
    val recorded = mutableListOf<Recorded>()

    /** Envía un comando por la vía real: payload wire → tipado → execute → outcome §2.6. */
    suspend fun send(payload: BrowserControllerCommandPayload): BrowserCommandOutcome {
        val outcome = controller.execute(BrowserCommand.from(payload))
        recorded += Recorded(payload.commandId, payload.action, outcome)
        return outcome
    }

    suspend fun send(
        action: String,
        args: JsonObject = EMPTY_ARGS,
    ): BrowserCommandOutcome = send(command(action, args))

    fun command(
        action: String,
        args: JsonObject = EMPTY_ARGS,
    ): BrowserControllerCommandPayload =
        BrowserControllerCommandPayload(
            commandId = "cmd-${nextId.getAndIncrement()}",
            action = action,
            arguments = args,
            controllerId = "android-test",
            browserProfileId = "mama-webview",
            toolCallId = "tool-test",
        )

    fun args(vararg pairs: Pair<String, Any?>): JsonObject =
        buildJsonObject {
            for ((k, v) in pairs) {
                when (v) {
                    is String -> put(k, v)
                    is Boolean -> put(k, v)
                    is Number -> put(k, v)
                    null -> put(k, kotlinx.serialization.json.JsonNull)
                    else -> error("tipo de arg no soportado en FakeCommandSource")
                }
            }
        }

    companion object {
        private val EMPTY_ARGS = buildJsonObject {}
    }
}
