package ai.hermes.mama.gateway

import java.util.concurrent.ConcurrentHashMap

/**
 * `SecureStore` en memoria para tests JVM (tarea B3): mismo contrato que la
 * impl Android (`EncryptedPrefsSecureStore`) sin el cifrado. Cuenta escrituras
 * para aserciones del tipo "login persiste el blob".
 */
class FakeSecureStore : SecureStore {
    private val data = ConcurrentHashMap<String, String>()

    /** Nº de llamadas a [store] (persistencias efectuadas). */
    @Volatile
    var writes = 0
        private set

    override fun load(key: String): String? = data[key]

    override fun store(
        key: String,
        value: String?,
    ) {
        writes++
        if (value == null) {
            data.remove(key)
        } else {
            data[key] = value
        }
    }
}
