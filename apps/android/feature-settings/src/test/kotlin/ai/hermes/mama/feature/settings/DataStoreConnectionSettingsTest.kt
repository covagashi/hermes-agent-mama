package ai.hermes.mama.feature.settings

import ai.hermes.mama.gateway.SecureStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `DataStoreConnectionSettings` (C2): credenciales por el SecureStore cifrado
 * (en tests, el fake en memoria — el cifrado real se cubre en instrumentado) y
 * la preferencia "Leer en voz alta" en un DataStore real sobre fichero temporal.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreConnectionSettingsTest {
    @TempDir
    lateinit var dir: File

    private val scope = CoroutineScope(UnconfinedTestDispatcher() + Job())

    private fun settings(): DataStoreConnectionSettings {
        val store =
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { File(dir, "test_${System.nanoTime()}.preferences_pb") },
            )
        return DataStoreConnectionSettings(
            secureStore = MapSecureStore(),
            dataStore = store,
            scope = scope,
        )
    }

    @Test
    fun `sin credenciales devuelve null`() =
        runTest {
            assertNull(settings().loadCredentials())
            assertFalse(settings().hasCredentials())
        }

    @Test
    fun `guardar y cargar credenciales (round-trip)`() =
        runTest {
            val s = settings()
            val creds =
                StoredCredentials(
                    serverBaseUrl = "https://hermes.example.invalid/",
                    username = "usuario",
                    password = "mama",
                )
            s.saveCredentials(creds)
            assertTrue(s.hasCredentials())
            assertEquals(creds, s.loadCredentials())
        }

    @Test
    fun `clearCredentials deja el store vacio`() =
        runTest {
            val s = settings()
            s.saveCredentials(StoredCredentials("https://h/", "u", "p"))
            s.clearCredentials()
            assertNull(s.loadCredentials())
            assertFalse(s.hasCredentials())
        }

    @Test
    fun `credenciales parciales no se cargan`() =
        runTest {
            // Si falta cualquiera de las tres claves, no hay credenciales —
            // un formulario a medias jamás pasa el gating de mama.
            val store = MapSecureStore()
            store.store("connection.server_url", "https://h/")
            val s =
                DataStoreConnectionSettings(
                    secureStore = store,
                    dataStore =
                        PreferenceDataStoreFactory.create(scope = scope) {
                            File(dir, "partial_${System.nanoTime()}.preferences_pb")
                        },
                    scope = scope,
                )
            assertNull(s.loadCredentials())
        }

    @Test
    fun `leer en voz alta por defecto es true y se persiste`() =
        runTest {
            val s = settings()
            assertTrue(s.readAloudEnabled.value)
            s.setReadAloud(false)
            assertFalse(s.readAloudEnabled.value)
            s.setReadAloud(true)
            assertTrue(s.readAloudEnabled.value)
        }

    /** SecureStore en memoria — el contrato es el mismo que el cifrado (B3). */
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
}
