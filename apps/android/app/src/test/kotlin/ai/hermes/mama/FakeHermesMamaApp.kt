package ai.hermes.mama

import ai.hermes.mama.gateway.SecureStore

/**
 * Application de test (Robolectric): el [SecureStore] real cifra contra
 * `AndroidKeyStore`, que no existe en la JVM — convención de B3: el contrato
 * del store se prueba con una implementación en memoria y el cifrado real se
 * ejerce en `androidTest`. El mapa cumple el mismo contrato
 * (`store(key, null)` borra).
 */
class FakeHermesMamaApp : HermesMamaApp() {
    override val secureStore: SecureStore = MapSecureStore()
}

private class MapSecureStore : SecureStore {
    private val map = mutableMapOf<String, String>()

    override fun load(key: String): String? = map[key]

    override fun store(
        key: String,
        value: String?,
    ) {
        if (value == null) map.remove(key) else map[key] = value
    }
}
