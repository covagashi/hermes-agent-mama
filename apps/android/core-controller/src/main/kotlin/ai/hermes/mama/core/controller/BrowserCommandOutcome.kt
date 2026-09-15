package ai.hermes.mama.core.controller

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Resultado de un [BrowserCommand] listo para `browser.controller.result` (§2.6).
 *
 * - [ok]`=true` → [resultJson] viaja en `params.result` **tal cual** (string JSON
 *   con la forma de las herramientas locales; el broker lo entrega verbatim).
 * - [ok]`=false` → [resultJson] viaja en `params.error` con la forma
 *   `{"success":false,"error":"<motivo humano en inglés>"}` — el broker lo
 *   envuelve en `ControllerRejected`, así el modelo sigue viendo el motivo.
 */
public data class BrowserCommandOutcome(
    val ok: Boolean,
    val resultJson: String,
) {
    public companion object {
        public fun ok(resultJson: String): BrowserCommandOutcome =
            BrowserCommandOutcome(ok = true, resultJson = resultJson)

        public fun failure(error: String): BrowserCommandOutcome =
            BrowserCommandOutcome(ok = false, resultJson = CommandResults.failure(error))
    }
}

/** Constructores de los strings JSON de §2.6 (misma forma que `tools/browser_tool.py`). */
public object CommandResults {
    /** `{"success":true, …}` con los campos extra de la acción. */
    public fun success(extra: JsonObjectBuilder.() -> Unit = {}): String =
        buildJsonObject {
            put("success", true)
            extra()
        }.toString()

    /** `{"success":false,"error":"<motivo humano en inglés>"}`. */
    public fun failure(error: String): String =
        buildJsonObject {
            put("success", false)
            put("error", error)
        }.toString()
}
