package ai.hermes.mama.gateway

/**
 * Parámetros de UNA conexión WebSocket (ROADMAP §2.1, tarea B2).
 *
 * El `onBeforeConnect` del [ConnectionManager] produce un ConnectParams nuevo
 * por intento: así el ticket WS de un solo uso (30 s, §2.1 paso 3) se mintea
 * inmediatamente antes de cada socket — también en cada reconexión.
 *
 * §8: [url] lleva el `?ticket=…` incrustado — nunca loguearla. Por eso el
 * `toString()` está redactado: un `println(params)` accidental no puede filtrar
 * ni el ticket ni los headers (pueden llevar credenciales).
 */
data class ConnectParams(
    /** URL completa del socket, p. ej. `wss://hermes.example.invalid/api/ws?ticket=…`. */
    val url: String,
    /** Cabeceras HTTP extra para el upgrade (vacío por defecto). */
    val headers: Map<String, String> = emptyMap(),
) {
    override fun toString(): String = "ConnectParams(url=<redacted>, headers=${headers.size} keys)"
}

/**
 * Abre un [Transport] por intento de conexión (permite falsear la red en tests).
 *
 * Contrato para implementaciones:
 * - DEBE suspender hasta que el socket está usable (upgrade aceptado) o lanzar
 *   la causa real si el handshake falla.
 * - Si la corrutina se cancela a mitad del handshake, DEBE cerrar lo que haya
 *   abierto: el `ConnectionManager` garantiza "nunca dos sockets abiertos"
 *   contando con esa limpieza.
 */
fun interface TransportFactory {
    suspend fun connect(params: ConnectParams): Transport
}
