package ai.hermes.mama.core.controller

/**
 * Fallo con motivo humano ya en inglés: `humanError` lo deja pasar tal cual al
 * `error` de §2.6 (el mensaje lo ve el modelo; debe ser legible y acotado).
 */
internal class ActionFailedException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
