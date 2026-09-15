package ai.hermes.mama.gateway

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * `CookieJar` de sesión con persistencia cifrada (ROADMAP §2.1.1/§8, tarea B3).
 *
 * - En memoria se comporta como un jar normal (guarda todo el `Set-Cookie` tal
 *   cual, sin parsear nombres — la app no conoce los nombres reales:
 *   `cookies.py::_resolved_name` puede llevar prefijo `__Host-`/`__Secure-`).
 * - En disco sólo vive el blob que escribe [SecureStore]: la impl Android lo
 *   cifra con `EncryptedSharedPreferences`. Con `store == null` es un jar
 *   efímero en memoria (tests, sesiones sin persistencia).
 * - Las cookies se serializan con `Cookie.toString()` + la URL origen (la que
 *   las fijó) y se restauran con `Cookie.parse` — formato estable de OkHttp que
 *   conserva `expires`, `domain`, `path`, `secure`, `httponly`, `samesite` y el
 *   flag `hostOnly`.
 *
 * §8: el blob contiene los VALORES de las cookies — jamás a logs; los mensajes
 * del [logger] sólo cuentan tamaños.
 */
class PersistentCookieJar(
    private val store: SecureStore? = null,
    private val storeKey: String = DEFAULT_STORE_KEY,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val logger: (String) -> Unit = {},
) : CookieJar {
    private val lock = Any()

    /** Entradas vivas (la URL origen sólo hace falta para re-serializar). */
    private val entries = mutableListOf<Entry>()
    private val json = Json { ignoreUnknownKeys = true }

    init {
        restore()
    }

    /**
     * Guarda las cookies de una respuesta: sustituye por `name+domain+path`,
     * descarta las que llegan ya caducadas (`Max-Age=0` borra en el servidor →
     * borra aquí) y persiste el blob resultante.
     */
    override fun saveFromResponse(
        url: HttpUrl,
        cookies: List<Cookie>,
    ) {
        synchronized(lock) {
            cookies.forEach { new ->
                entries.removeAll { sameId(it.cookie, new) }
                if (new.expiresAt > nowMs()) {
                    entries += Entry(origin = url, cookie = new)
                }
            }
            persist()
        }
    }

    /** Cookies que casan con [url]; las caducadas se purgan de paso. */
    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        synchronized(lock) {
            val now = nowMs()
            if (entries.removeAll { it.cookie.expiresAt <= now }) {
                persist()
            }
            entries.filter { it.cookie.matches(url) }.map { it.cookie }
        }

    /** Borra memoria y blob persistido (logout / "borrar credenciales", J2). */
    fun clear() {
        synchronized(lock) {
            entries.clear()
            store?.let { runCatching { it.store(storeKey, null) } }
        }
    }

    // --- persistencia ---

    private fun persist() {
        val target = store ?: return
        val blob =
            json.encodeToString(
                StoredJar.serializer(),
                StoredJar(cookies = entries.map { StoredCookie(it.origin.toString(), it.cookie.toString()) }),
            )
        runCatching { target.store(storeKey, blob) }
            .onFailure { warn("cookie blob persist failed (${it::class.simpleName})") }
    }

    private fun restore() {
        val blob =
            runCatching { store?.load(storeKey) }
                .onFailure { warn("cookie blob load failed (${it::class.simpleName})") }
                .getOrNull()
                ?.takeUnless { it.isEmpty() }
                ?: return
        val jar =
            runCatching { json.decodeFromString(StoredJar.serializer(), blob) }
                .onFailure { warn("cookie blob undecodable; starting empty (${it::class.simpleName})") }
                .getOrNull()
                ?: return
        val now = nowMs()
        val restored =
            jar.cookies.mapNotNull { stored ->
                val origin = runCatching { stored.u.toHttpUrl() }.getOrNull()
                val cookie = origin?.let { runCatching { Cookie.parse(it, stored.c) }.getOrNull() }
                if (origin != null && cookie != null && cookie.expiresAt > now) {
                    Entry(origin, cookie)
                } else {
                    null
                }
            }
        synchronized(lock) {
            entries += restored
        }
    }

    /** Identidad de una cookie a efectos de sustitución (lo que el servidor sobre-escribe). */
    private fun sameId(
        a: Cookie,
        b: Cookie,
    ): Boolean = a.name == b.name && a.domain == b.domain && a.path == b.path

    private fun warn(message: String) = runCatching { logger(message) }

    private data class Entry(
        val origin: HttpUrl,
        val cookie: Cookie,
    )

    @Serializable
    private data class StoredJar(
        val v: Int = 1,
        val cookies: List<StoredCookie> = emptyList(),
    )

    /** `u` = URL origen que fijó la cookie (Cookie.parse la necesita), `c` = Set-Cookie serializado. */
    @Serializable
    private data class StoredCookie(
        val u: String,
        val c: String,
    )

    private companion object {
        const val DEFAULT_STORE_KEY = "session_cookies"
    }
}
