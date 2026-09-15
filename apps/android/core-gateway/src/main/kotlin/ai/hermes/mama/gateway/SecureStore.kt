package ai.hermes.mama.gateway

/**
 * Almacén clave→valor para secretos pequeños (ROADMAP §8, tarea B3).
 *
 * El `CookieJar` persistente serializa las cookies de sesión aquí; la
 * implementación de Android (`EncryptedPrefsSecureStore`, en
 * `feature-settings`) los cifra con `EncryptedSharedPreferences`. La interfaz
 * vive en este módulo JVM puro para que los tests la falseen en memoria —
 * nunca se escribe un blob en claro a disco.
 *
 * Contrato:
 * - Valores `String` opacos para el store: el contenido y el cifrado son cosa
 *   del llamador y de la impl, respectivamente.
 * - Las llamadas pueden venir de cualquier hilo (OkHttp invoca el jar desde su
 *   dispatcher): la impl debe ser thread-safe.
 * - [store] debe ser síncrona (`commit`): cuando vuelve, el valor sobrevive un
 *   reinicio del proceso.
 */
interface SecureStore {
    /** Blob guardado bajo [key], o `null` si no hay nada. */
    fun load(key: String): String?

    /** Persiste [value] bajo [key]; `null` borra la entrada. */
    fun store(
        key: String,
        value: String?,
    )
}
