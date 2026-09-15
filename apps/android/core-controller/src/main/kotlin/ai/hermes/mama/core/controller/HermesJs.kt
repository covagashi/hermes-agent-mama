package ai.hermes.mama.core.controller

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Desenvuelve el doble quoting de `evaluateJavascript` (los strings JS llegan
 * JSON-dentro-de-JSON) y exige objeto.
 *
 * @throws ActionFailedException si el resultado no es un objeto JSON — p. ej.
 *   `"null"` cuando la página navegó y `window.__hermes` ya no existe.
 */
internal fun unwrapToJsonObject(
    raw: String,
    what: String,
): JsonObject {
    val first = parseJson(raw, what)
    val unwrapped =
        if (first is JsonPrimitive && first.isString) {
            parseJson(first.content, what)
        } else {
            first
        }
    return unwrapped as? JsonObject
        ?: throw ActionFailedException("$what returned no result (the page may have navigated)")
}

/** String Kotlin → literal JS seguro (`"…"` con escapes JSON). */
internal fun jsStringLiteral(value: String): String = JsonPrimitive(value).toString()

private fun parseJson(
    text: String,
    what: String,
): JsonElement =
    try {
        Json.parseToJsonElement(text.trim())
    } catch (e: IllegalArgumentException) {
        throw ActionFailedException("$what did not return valid JSON", e)
    }
