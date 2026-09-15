package ai.hermes.mama.feature.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `normalizeServerUrl` (C2): la usuaria escribe "la dirección que le dictaron";
 * el esquema lo completa la app — `https` en internet, `http` sólo para
 * loopback/emulador (§8, misma regla que `BasicAuthSession`).
 */
class ServerUrlTest {
    @Test
    fun `sin esquema en internet completa https`() {
        assertEquals(
            "https://hermes.example.invalid/",
            normalizeServerUrl("hermes.example.invalid")?.toString(),
        )
    }

    @Test
    fun `sin esquema con puerto en internet completa https`() {
        assertEquals(
            "https://hermes.example.invalid:8443/",
            normalizeServerUrl("hermes.example.invalid:8443")?.toString(),
        )
    }

    @Test
    fun `esquema explicito se respeta`() {
        assertEquals(
            "https://hermes.example.invalid/",
            normalizeServerUrl("https://hermes.example.invalid")?.toString(),
        )
    }

    @Test
    fun `prefijo de path se conserva (proxy con X-Forwarded-Prefix)`() {
        assertEquals(
            "https://hermes.example.invalid/hermes/",
            normalizeServerUrl("https://hermes.example.invalid/hermes/")?.toString(),
        )
    }

    @Test
    fun `loopback 10_0_2_2 del emulador completa http`() {
        assertEquals(
            "http://10.0.2.2:8399/",
            normalizeServerUrl("10.0.2.2:8399")?.toString(),
        )
    }

    @Test
    fun `loopback 127 completa http`() {
        assertEquals(
            "http://127.0.0.1:8399/",
            normalizeServerUrl("127.0.0.1:8399")?.toString(),
        )
    }

    @Test
    fun `localhost completa http`() {
        assertEquals(
            "http://localhost:8399/",
            normalizeServerUrl("localhost:8399")?.toString(),
        )
    }

    @Test
    fun `http explicito a internet se acepta aqui (lo rechaza la sesion §8)`() {
        // La política cleartext la aplica BasicAuthSession → CleartextForbidden
        // → "esa dirección no es segura". Normalizar no duplica la regla.
        assertEquals(
            "http://192.0.2.10:8000/",
            normalizeServerUrl("http://192.0.2.10:8000")?.toString(),
        )
    }

    @Test
    fun `127_evil_com no es loopback aunque empiece por 127`() {
        val url = normalizeServerUrl("127.evil.com")
        assertEquals("https", url?.scheme)
        assertEquals("127.evil.com", url?.host)
    }

    @Test
    fun `entrada vacia o espacios devuelve null`() {
        assertNull(normalizeServerUrl(""))
        assertNull(normalizeServerUrl("   "))
        assertNull(normalizeServerUrl(" \t "))
    }

    @Test
    fun `espacios dentro de la direccion devuelven null`() {
        assertNull(normalizeServerUrl("hermes example.invalid"))
        assertNull(normalizeServerUrl("10.0. 2.2:8399"))
    }

    @Test
    fun `esquema que no es http_s devuelve null`() {
        assertNull(normalizeServerUrl("ws://hermes.example.invalid"))
        assertNull(normalizeServerUrl("ftp://hermes.example.invalid"))
    }

    @Test
    fun `espacios alrededor se recortan`() {
        assertEquals(
            "https://hermes.example.invalid/",
            normalizeServerUrl("  hermes.example.invalid  ")?.toString(),
        )
    }

    @Test
    fun `userinfo en la url devuelve null - la contrasena tiene su campo`() {
        assertNull(normalizeServerUrl("https://usuario:secreto@hermes.example.invalid"))
        assertNull(normalizeServerUrl("usuario:secreto@hermes.example.invalid"))
        assertNull(normalizeServerUrl("usuario@hermes.example.invalid"))
    }

    @Test
    fun `la url normalizada sigue siendo una HttpUrl usable`() {
        val url = normalizeServerUrl("10.0.2.2:8399")
        assertTrue(url != null && url.port == 8399 && url.host == "10.0.2.2")
    }
}
