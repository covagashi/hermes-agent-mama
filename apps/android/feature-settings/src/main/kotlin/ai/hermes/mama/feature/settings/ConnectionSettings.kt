package ai.hermes.mama.feature.settings

import ai.hermes.mama.feature.voice.ReadAloudSetting

/**
 * Credenciales del servidor `hermes serve` tal y como las guarda la pantalla
 * Conexión (ROADMAP §5/C2). [serverBaseUrl] ya viene NORMALIZADA por
 * [ServerUrl] (esquema incluido: `https://hermes.example.invalid`,
 * `http://10.0.2.2:8399` en dev), lista para `toHttpUrl()`.
 *
 * §8: la contraseña viaja en memoria sólo el tiempo imprescindible; en disco
 * vive cifrada (SecureStore). [toString] la redacta como hace `Credentials`.
 */
data class StoredCredentials(
    val serverBaseUrl: String,
    val username: String,
    val password: String,
) {
    override fun toString(): String =
        "StoredCredentials(serverBaseUrl=$serverBaseUrl, username=$username, password=<redacted>)"
}

/**
 * Ajustes persistidos de la app (ROADMAP §1 `data/settings`, tarea C2).
 *
 * - **Credenciales** (`loadCredentials`/`saveCredentials`/`clearCredentials`):
 *   el SecureStore cifrado las guarda para que C8 construya la
 *   `BasicAuthSession` de la app sin volver a preguntar. `null` = primera vez
 *   → en flavor `mama` es la señal para abrir Conexión.
 * - **[readAloudEnabled]**: la preferencia "Leer las respuestas en voz alta"
 *   que consumen D2 (`SpeechOutput`) y C8. La interfaz [ReadAloudSetting] viene
 *   de `feature-voice`, que ya esperaba esta implementación.
 *
 * La implementación Android es [DataStoreConnectionSettings]; los tests JVM
 * usan un fake en memoria.
 */
interface ConnectionSettings : ReadAloudSetting {
    /** Credenciales guardadas, o `null` si nunca se completó la Conexión. */
    suspend fun loadCredentials(): StoredCredentials?

    /** Persiste las credenciales (cifradas). Sustituye a las anteriores. */
    suspend fun saveCredentials(credentials: StoredCredentials)

    /** Borra credenciales (y con ellas la sesión: las cookies van en el mismo store). */
    suspend fun clearCredentials()

    /** `true` cuando hay credenciales guardadas — el gating del flavor `mama` (C8). */
    suspend fun hasCredentials(): Boolean = loadCredentials() != null

    /** Persiste la preferencia "Leer en voz alta". */
    suspend fun setReadAloud(enabled: Boolean)
}
