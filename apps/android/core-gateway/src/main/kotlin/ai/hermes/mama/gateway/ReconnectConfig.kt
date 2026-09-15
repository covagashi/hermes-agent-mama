package ai.hermes.mama.gateway

import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Backoff de reconexión del [ConnectionManager] (ROADMAP §2.2, tarea B2):
 * `1 s, 2 s, 4 s…` hasta un máx. de `30 s`, con jitter ±20 %.
 *
 * [random] es inyectable para tests deterministas (`jitterFraction = 0.0` o
 * semilla fija).
 */
data class ReconnectConfig(
    /** Espera del primer reintento tras una caída (§2.2: 1 s). */
    val initialDelay: Duration = DEFAULT_INITIAL_DELAY,
    /** Techo del backoff (§2.2: 30 s). */
    val maxDelay: Duration = DEFAULT_MAX_DELAY,
    /** Factor exponencial por intento (§2.2: ×2). */
    val multiplier: Double = DEFAULT_MULTIPLIER,
    /** Jitter relativo ± aplicado al resultado (§2.2: 0.2 = ±20 %). */
    val jitterFraction: Double = DEFAULT_JITTER_FRACTION,
    /** Espera máxima al evento `gateway.ready` tras abrir el socket (§2.4: marca "conectado"). */
    val readyTimeout: Duration = DEFAULT_READY_TIMEOUT,
    /** Techo de un intento completo (ticket + handshake + ready): cubre fábricas que no acoten. */
    val attemptTimeout: Duration = DEFAULT_ATTEMPT_TIMEOUT,
    val random: Random = Random,
) {
    init {
        require(initialDelay > Duration.ZERO) { "initialDelay debe ser > 0" }
        require(maxDelay >= initialDelay) { "maxDelay debe ser >= initialDelay" }
        require(multiplier >= 1.0) { "multiplier debe ser >= 1" }
        require(jitterFraction in 0.0..1.0) { "jitterFraction debe estar en [0, 1]" }
    }

    /**
     * Espera antes del reintento [attempt] (empieza en 1):
     * `min(maxDelay, initialDelay · multiplier^(attempt−1))` con jitter uniforme
     * en `[1−jitterFraction, 1+jitterFraction]`.
     */
    fun backoff(attempt: Int): Duration {
        val base = initialDelay * multiplier.pow((attempt - 1).coerceAtLeast(0))
        val capped = minOf(base, maxDelay)
        // OJO: Random.nextDouble(0.0, 0.0) lanza — jitterFraction = 0 (tests) es válido.
        val jitter = if (jitterFraction <= 0.0) 1.0 else 1.0 + random.nextDouble(-jitterFraction, jitterFraction)
        return (capped * jitter).coerceAtLeast(Duration.ZERO)
    }

    companion object {
        val DEFAULT_INITIAL_DELAY: Duration = 1.seconds
        val DEFAULT_MAX_DELAY: Duration = 30.seconds
        const val DEFAULT_MULTIPLIER: Double = 2.0
        const val DEFAULT_JITTER_FRACTION: Double = 0.2
        val DEFAULT_READY_TIMEOUT: Duration = 30.seconds
        val DEFAULT_ATTEMPT_TIMEOUT: Duration = 60.seconds
    }
}
