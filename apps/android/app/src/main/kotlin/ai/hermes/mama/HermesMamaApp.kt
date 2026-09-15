package ai.hermes.mama

import ai.hermes.mama.feature.settings.EncryptedPrefsSecureStore
import ai.hermes.mama.gateway.SecureStore
import android.app.Application
import timber.log.Timber

open class HermesMamaApp : Application() {
    /**
     * El SecureStore cifrado único de la app (§8): las credenciales de Conexión
     * (C2) y el blob de cookies de la `BasicAuthSession` (B3) viven en el mismo
     * fichero — un solo almacén de secretos.
     *
     * `open` para que los tests JVM lo sustituyan por uno en memoria:
     * `AndroidKeyStore` no existe bajo Robolectric (convención de B3 — el
     * cifrado real se ejerce en instrumentado).
     */
    open val secureStore: SecureStore by lazy { EncryptedPrefsSecureStore(this) }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        // En el flavor mama (release) sólo se registra WARN+ y redactando
        // cookies/tickets/cuerpos; ese árbol llega con el endurecimiento de J2.
    }
}
