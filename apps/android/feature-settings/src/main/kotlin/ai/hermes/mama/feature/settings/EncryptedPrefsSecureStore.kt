package ai.hermes.mama.feature.settings

import ai.hermes.mama.gateway.SecureStore
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * [SecureStore] sobre `EncryptedSharedPreferences` (ROADMAP §8, tarea B3):
 * AES256-SIV para las claves y AES256-GCM para los valores, con la master key
 * en el `AndroidKeyStore`. Es el único lugar donde el blob de cookies (y en C2
 * las credenciales) toca disco — nunca en claro.
 *
 * El [SharedPreferences] se inyecta por el constructor interno: `AndroidKeyStore`
 * no existe bajo la JVM de Robolectric, así que los unit tests verifican el
 * contrato del store (round-trip, borrado, reapertura) contra las prefs reales
 * del framework y el cifrado se ejerce en los instrumentados (`androidTest`).
 *
 * Implementación JVM-pura equivalente para tests del núcleo: un
 * `Map<String,String>` detrás de la misma interfaz (ver `FakeSecureStore` en
 * `core-gateway`).
 */
class EncryptedPrefsSecureStore internal constructor(
    private val prefs: SharedPreferences,
) : SecureStore {
    /** Store de producción: prefs cifradas bajo [fileName] con master key en el AndroidKeyStore. */
    constructor(
        context: Context,
        fileName: String = DEFAULT_FILE_NAME,
    ) : this(encryptedPrefs(context, fileName))

    override fun load(key: String): String? = prefs.getString(key, null)

    /** `commit` síncrono (contrato [SecureStore]): al volver, el dato sobrevive un reinicio. */
    override fun store(
        key: String,
        value: String?,
    ) {
        val editor = prefs.edit()
        if (value == null) {
            editor.remove(key)
        } else {
            editor.putString(key, value)
        }
        check(editor.commit()) { "secure store commit failed" }
    }

    companion object {
        /** Fichero de prefs cifradas compartido por auth (cookies B3) y ajustes (C2). */
        const val DEFAULT_FILE_NAME = "hermes_secure_store"

        private fun encryptedPrefs(
            context: Context,
            fileName: String,
        ): SharedPreferences =
            EncryptedSharedPreferences.create(
                context,
                fileName,
                MasterKey
                    .Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
    }
}
