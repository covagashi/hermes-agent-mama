package ai.hermes.mama.gateway

import kotlin.time.Duration

/**
 * Estado de la conexión WS expuesto por [ConnectionManager.state] (tarea B2).
 *
 * `Connected` sólo se alcanza cuando ha llegado `gateway.ready` (§2.4: es el
 * evento que "marca conectado"); un socket abierto sin `ready` sigue en
 * `Connecting`/`Reconnecting` hasta el `readyTimeout`.
 */
sealed interface ConnectionState {
    /** Sin conexión: estado inicial, o tras [ConnectionManager.disconnect]. */
    data object Disconnected : ConnectionState

    /** Primer intento de conexión en curso (aún no ha habido ningún `Connected`). */
    data object Connecting : ConnectionState

    /**
     * Socket abierto y `gateway.ready` recibido. [channel] es el [JsonRpcChannel]
     * vivo de esta generación (un canal == una generación, ver B1); [replayEpoch]
     * es el `replay_epoch` del ready, por si el consumidor lo quiere.
     */
    data class Connected(
        val channel: JsonRpcChannel,
        val replayEpoch: String? = null,
    ) : ConnectionState

    /**
     * Caída detectada: el reintento [attempt] (1 = primer reintento) se
     * ejecutará en [retryIn] (backoff con jitter ya aplicado). [lastError] es
     * la última causa (la instancia — §8: nunca se loguea su `message`, así la
     * UI puede hacer `is` sobre el tipo real, p. ej. `SessionExpired` en C2).
     */
    data class Reconnecting(
        val attempt: Int,
        val retryIn: Duration,
        val lastError: Throwable? = null,
    ) : ConnectionState

    /**
     * Fallo terminal sin reintentos (p. ej. [ConnectionFatalException] lanzada
     * por `onBeforeConnect` al rechazar el gateway las credenciales — B3): la
     * app lleva a la usuaria a la pantalla de conexión. [ConnectionManager.connect]
     * puede arrancar un bucle nuevo tras este estado.
     */
    data class Failed(
        val cause: Throwable,
    ) : ConnectionState
}

/** Señales de una sola vez del [ConnectionManager] (flujo `events`). */
sealed interface ConnectionEvent {
    /**
     * La generación N ≥ 2 quedó `Connected`: [replayEpoch] es el `replay_epoch`
     * de su `gateway.ready` (§2.4 — si cambió, el consumidor recarga el
     * transcript; F3 re-registra el controlador de navegador).
     */
    data class Reconnected(
        val replayEpoch: String?,
    ) : ConnectionEvent
}

/**
 * Error irrecuperable de conexión (B2): reintentar no tiene sentido — el caso
 * canónico es `onBeforeConnect` lanzándolo cuando el gateway rechaza las
 * credenciales al mintear el ticket (B3: `InvalidCredentials`/`SessionExpired`
 * se mapearán a este tipo). El [ConnectionManager] para el bucle y publica
 * [ConnectionState.Failed]; [ConnectionManager.connect] puede reintentar tras él.
 */
class ConnectionFatalException(
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
