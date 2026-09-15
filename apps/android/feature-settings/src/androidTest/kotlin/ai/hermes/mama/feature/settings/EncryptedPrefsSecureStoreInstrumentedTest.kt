package ai.hermes.mama.feature.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El camino cifrado REAL del [SecureStore] (tarea B3): `EncryptedSharedPreferences`
 * + master key en el `AndroidKeyStore` — sólo existe en dispositivo/emulador,
 * por eso vive en `androidTest` y no en la suite JVM (Robolectric no provee
 * `AndroidKeyStore`). Fichero único por ejecución para no tocar el store real.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedPrefsSecureStoreInstrumentedTest {
    @Test
    fun encryptedStoreRoundTripsAndDeletes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fileName = "it_secure_store_${System.currentTimeMillis()}"
        val store = EncryptedPrefsSecureStore(context, fileName)

        assertNull(store.load("session_cookies"))
        store.store("session_cookies", "blob-cifrado-it")
        assertEquals("blob-cifrado-it", store.load("session_cookies"))

        // Reapertura: el blob cifrado sobrevive a una instancia nueva.
        val reopened = EncryptedPrefsSecureStore(context, fileName)
        assertEquals("blob-cifrado-it", reopened.load("session_cookies"))

        reopened.store("session_cookies", null)
        assertNull(reopened.load("session_cookies"))
    }
}
