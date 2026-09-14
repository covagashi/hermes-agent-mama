package ai.hermes.mama.core.controller

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Resultado tipado de `window.__hermes.snapshot(full)` (ROADMAP §2.6, tarea F1).
 *
 * El JS devuelve un string JSON `{"snapshot":…,"element_count":…,"ref_count":…,
 * "pending_dialogs":[…]}`; a través de `WebView.evaluateJavascript` llega con un
 * nivel extra de quoting que [parse] desenvuelve.
 *
 * @property text snapshot en formato agent-browser (`- role "name" [ref=eN]`).
 * @property refCount refs `eN` emitidos en la página hasta ahora (`ref_count`,
 *   con `element_count` como valor de reserva).
 */
public data class SnapshotResult(
    val text: String,
    val refCount: Int,
) {
    public companion object {
        /** Límite de caracteres de un snapshot (§2.6). */
        public const val MAX_SNAPSHOT_CHARS: Int = 15_000

        /** Marcador de truncado, en su propia línea final. */
        public const val TRUNCATION_MARKER: String = "… [truncated]"

        /**
         * Parsea lo que devuelve `evaluateJavascript` (string JSON doblemente
         * codificado) o el JSON directo del objeto resultado.
         *
         * @throws IllegalArgumentException si no es un objeto con `snapshot` de
         *   tipo string o `success` es `false`.
         */
        public fun parse(raw: String): SnapshotResult {
            val root = unwrapToObject(raw)
            val success = root["success"]?.jsonPrimitive?.content
            if (success == "false") {
                val error = root["error"]?.jsonPrimitive?.content.orEmpty()
                throw IllegalArgumentException("snapshot devolvió success=false: $error")
            }
            val text =
                root["snapshot"]?.jsonPrimitive?.content
                    ?: throw IllegalArgumentException("resultado sin campo \"snapshot\" string")
            val refCount =
                root["ref_count"]?.jsonPrimitive?.intOrNull
                    ?: root["element_count"]?.jsonPrimitive?.intOrNull
                    ?: 0
            return SnapshotResult(text = text, refCount = refCount)
        }

        private fun unwrapToObject(raw: String): JsonObject {
            val element = parseJson(raw, "resultado")
            // evaluateJavascript envuelve el string devuelto en otra capa JSON.
            val unwrapped =
                if (element is JsonPrimitive && element.isString) {
                    parseJson(element.content, "resultado interno")
                } else {
                    element
                }
            return unwrapped as? JsonObject
                ?: throw IllegalArgumentException("resultado no es un objeto JSON")
        }

        private fun parseJson(
            text: String,
            what: String,
        ) = try {
            Json.parseToJsonElement(text.trim())
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("$what no es JSON válido", e)
        }

        /**
         * Trunca por líneas (nunca a media línea) a [maxChars] incluyendo el
         * marcador final `… [truncated]`. Misma regla que `hermes_snapshot.js`:
         * es la red de seguridad del lado Kotlin por si un frame llega grande.
         */
        public fun truncate(
            text: String,
            maxChars: Int = MAX_SNAPSHOT_CHARS,
        ): String {
            if (text.length <= maxChars) {
                return text
            }
            val budget = maxChars - (TRUNCATION_MARKER.length + 1)
            val kept = StringBuilder()
            for (line in text.split('\n')) {
                val added = line.length + (if (kept.isEmpty()) 0 else 1)
                if (kept.length + added > budget) {
                    break
                }
                if (kept.isNotEmpty()) {
                    kept.append('\n')
                }
                kept.append(line)
            }
            return if (kept.isEmpty()) TRUNCATION_MARKER else "$kept\n$TRUNCATION_MARKER"
        }
    }
}
