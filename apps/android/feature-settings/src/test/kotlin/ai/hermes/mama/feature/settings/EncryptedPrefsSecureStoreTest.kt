package ai.hermes.mama.feature.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Contrato del [SecureStore] de Android (tarea B3) bajo Robolectric: se inyecta
 * un [android.content.SharedPreferences] real del framework porque
 * `AndroidKeyStore` no existe en JVM — el camino cifrado
 * (`EncryptedSharedPreferences`) se cubre en los instrumentados. Cada test usa
 * un fichero propio para no compartir estado.
 */
@RunWith(RobolectricTestRunner::class)
class EncryptedPrefsSecureStoreTest {
    private var counter = 0

    private fun newStore(fileName: String = "test_secure_store_${counter++}"): EncryptedPrefsSecureStore =
        EncryptedPrefsSecureStore(
            ApplicationProvider
                .getApplicationContext<Context>()
                .getSharedPreferences(fileName, Context.MODE_PRIVATE),
        )

    @Test
    fun `store y load hacen round-trip y null borra la entrada`() {
        val store = newStore()
        assertNull(store.load("session_cookies"), "un store nuevo empieza vacío")

        store.store("session_cookies", "blob-cifrado-1")
        assertEquals("blob-cifrado-1", store.load("session_cookies"))

        store.store("session_cookies", null)
        assertNull(store.load("session_cookies"), "store(key, null) borra la entrada")
    }

    @Test
    fun `una instancia nueva sobre el mismo fichero lee lo persistido`() {
        val fileName = "test_secure_store_reopen"
        newStore(fileName).store("k", "persistido")
        assertEquals("persistido", newStore(fileName).load("k"))
    }
}
