package ai.hermes.mama.gateway

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * Contrato del [PersistentCookieJar] (tarea B3): match por dominio/path,
 * caducidad, persistencia en [SecureStore] y restauración tras "reinicio".
 */
class PersistentCookieJarTest {
    private val base = "https://hermes.example.invalid".toHttpUrl()

    private fun cookie(
        name: String,
        value: String,
        expiresAt: Long = System.currentTimeMillis() + DEFAULT_TTL_MS,
    ): Cookie =
        Cookie
            .Builder()
            .name(name)
            .value(value)
            .domain(base.host)
            .path("/")
            .expiresAt(expiresAt)
            .build()

    @Test
    fun `las cookies guardadas se sirven por host y se persisten en el store`() {
        val store = FakeSecureStore()
        val jar = PersistentCookieJar(store)

        jar.saveFromResponse(base, listOf(cookie("hermes_session_at", "token-1")))

        assertEquals(
            "token-1",
            jar.loadForRequest(base).single().value,
            "la cookie queda en memoria para las siguientes peticiones",
        )
        assertNotNull(store.load("session_cookies"), "login persiste el blob cifrado (SecureStore)")
    }

    @Test
    fun `una instancia nueva restaura las cookies del blob persistido`() {
        val store = FakeSecureStore()
        PersistentCookieJar(store).saveFromResponse(base, listOf(cookie("hermes_session_at", "token-9")))

        // "Reinicio": jar nuevo sobre el MISMO SecureStore.
        val restored = PersistentCookieJar(store)
        val cookies = restored.loadForRequest(base)
        assertEquals("token-9", cookies.single().value)
    }

    @Test
    fun `una cookie caducada no se sirve ni se restaura`() {
        val store = FakeSecureStore()
        val jar = PersistentCookieJar(store)
        jar.saveFromResponse(
            base,
            listOf(cookie("vieja", "x", expiresAt = System.currentTimeMillis() - 1000)),
        )

        assertTrue(jar.loadForRequest(base).isEmpty(), "caducada no se sirve")
        assertTrue(
            PersistentCookieJar(store).loadForRequest(base).isEmpty(),
            "caducada no se restaura del blob",
        )
    }

    @Test
    fun `una cookie futura se sirve hasta su expiresAt`() {
        var now = System.currentTimeMillis()
        val jar = PersistentCookieJar(store = null, nowMs = { now })
        jar.saveFromResponse(base, listOf(cookie("sesion", "v", expiresAt = now + 1.hours.inWholeMilliseconds)))

        assertEquals("v", jar.loadForRequest(base).single().value)
        now += 2.hours.inWholeMilliseconds
        assertTrue(jar.loadForRequest(base).isEmpty(), "tras expiresAt deja de servirse")
    }

    @Test
    fun `mismo name+domain+path sustituye el valor anterior`() {
        val jar = PersistentCookieJar()
        jar.saveFromResponse(base, listOf(cookie("s", "uno")))
        jar.saveFromResponse(base, listOf(cookie("s", "dos")))
        assertEquals("dos", jar.loadForRequest(base).single().value)
    }

    @Test
    fun `clear borra memoria y el blob persistido`() {
        val store = FakeSecureStore()
        val jar = PersistentCookieJar(store)
        jar.saveFromResponse(base, listOf(cookie("s", "v")))

        jar.clear()

        assertTrue(jar.loadForRequest(base).isEmpty())
        assertNull(store.load("session_cookies"), "el blob queda borrado tras clear()")
    }

    @Test
    fun `un blob corrupto arranca el jar vacío sin lanzar`() {
        val store = FakeSecureStore()
        store.store("session_cookies", "{no es el formato esperado")
        assertTrue(PersistentCookieJar(store).loadForRequest(base).isEmpty())
    }

    private companion object {
        /** 24 h: caducidad finita — el formato `expires=` del blob la conserva. */
        const val DEFAULT_TTL_MS = 24 * 60 * 60 * 1_000L
    }
}
