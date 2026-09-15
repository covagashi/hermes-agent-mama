package ai.hermes.mama.feature.settings

import ai.hermes.mama.gateway.isCleartextAllowedHost
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Normalización del campo "Dirección del servidor" (C2). La usuaria escribe lo
 * que le dictaron — `hermes.example.invalid`, `10.0.2.2:8399` — casi nunca el
 * esquema. Regla:
 *
 * - **Sin esquema** → `https://` por defecto (§8: nunca asumir cleartext en
 *   internet). Excepción documentada: host loopback/`10.0.2.2`
 *   ([isCleartextAllowedHost]) → `http://`, porque el FakeGateway del
 *   flavor `dev` y un `hermes serve` casero hablan claro.
 * - **Con esquema** (`http://…`, `https://…`) → se respeta tal cual; la
 *   política §8 la aplica `BasicAuthSession` (`http` fuera de loopback sin
 *   `allowCleartext` → `CleartextForbidden` → "dirección no segura").
 *
 * Devuelve la [HttpUrl] base lista para `BasicAuthSession`, o `null` si la
 * entrada no parece una dirección (el ViewModel pinta "revisa la dirección").
 * Userinfo (`user:pass@host`) se rechaza: la contraseña tiene su propio campo
 * y un `serverBaseUrl` con secreto se persistiría/prellenaría en claro (§8).
 */
fun normalizeServerUrl(input: String): HttpUrl? {
    val trimmed = input.trim()
    return when {
        trimmed.isEmpty() || trimmed.any { it.isWhitespace() } -> null
        "://" in trimmed ->
            trimmed
                .toHttpUrlOrNull()
                ?.takeIf { it.scheme == SCHEME_HTTP || it.scheme == SCHEME_HTTPS }
                ?.takeIf(::sinUserinfo)
        else -> {
            val host = trimmed.substringBefore('/').substringBefore(':')
            if (host.isEmpty()) {
                null
            } else {
                val scheme = if (isCleartextAllowedHost(host)) SCHEME_HTTP else SCHEME_HTTPS
                "$scheme://$trimmed".toHttpUrlOrNull()?.takeIf(::sinUserinfo)
            }
        }
    }
}

private fun sinUserinfo(url: HttpUrl): Boolean = url.encodedUsername.isEmpty() && url.encodedPassword.isEmpty()

private const val SCHEME_HTTP = "http"
private const val SCHEME_HTTPS = "https"
