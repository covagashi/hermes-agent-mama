package ai.hermes.mama.gateway

/**
 * §8: ¿es [host] un destino donde el esquema cleartext (`http`/`ws`) es
 * admisible? — loopback y `10.0.2.2` (así ve el emulador Android al host).
 * El flavor `dev` además fuerza `allowCleartext` en la sesión.
 *
 * El 127/8 exige un IPv4 dotted-quad REAL — `127.evil.com` también "empieza
 * por 127." y no es loopback. Regla única compartida por `BasicAuthSession`
 * (que rechaza una base `http://` a otro host) y por la pantalla Conexión de
 * C2 (que decide `http` vs `https` al completar el esquema que la usuaria no
 * escribió).
 */
fun isCleartextAllowedHost(host: String): Boolean {
    val quad = LOOPBACK_V4_REGEX.matchEntire(host)
    return host == HOST_EMULATOR ||
        host == HOST_LOCALHOST ||
        host.endsWith(LOCALHOST_SUFFIX) ||
        (quad != null && quad.groupValues.drop(1).all { it.toInt() <= IPV4_OCTET_MAX }) ||
        host == HOST_LOOPBACK_V6 ||
        host == HOST_LOOPBACK_V6_BRACKETED ||
        host == HOST_LOOPBACK_V6_FULL
}

/** 127.x.y.z con los 4 octetos presentes; el rango (≤255) se valida aparte. */
private val LOOPBACK_V4_REGEX = Regex("""^127\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
private const val IPV4_OCTET_MAX = 255

private const val HOST_EMULATOR = "10.0.2.2"
private const val HOST_LOCALHOST = "localhost"
private const val LOCALHOST_SUFFIX = ".localhost"
private const val HOST_LOOPBACK_V6 = "::1"
private const val HOST_LOOPBACK_V6_BRACKETED = "[::1]"
private const val HOST_LOOPBACK_V6_FULL = "0:0:0:0:0:0:0:1"
