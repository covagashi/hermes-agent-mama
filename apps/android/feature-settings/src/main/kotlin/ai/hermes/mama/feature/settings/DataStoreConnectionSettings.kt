package ai.hermes.mama.feature.settings

import ai.hermes.mama.gateway.SecureStore
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * Preferencias no secretas de Conexión (ROADMAP §1: DataStore para ajustes).
 * El fichero es exclusivo de settings: ningún otro feature lo comparte.
 */
private val Context.connectionSettingsStore: DataStore<Preferences> by preferencesDataStore(
    name = "connection_settings",
)

/**
 * [ConnectionSettings] real (ROADMAP §1 `settings/`, tarea C2):
 *
 * - **Credenciales** en el [SecureStore] cifrado ([EncryptedPrefsSecureStore],
 *   mismo fichero que el blob de cookies de B3 — un solo almacén de secretos).
 * - **"Leer en voz alta"** en `DataStore` (preferencia, no secreto). Se expone
 *   como [StateFlow] caliente para que `SpeechOutput` (D2) la lea de una vez
 *   con `value` y la UI se redibuje sola.
 *
 * [scope] es el ciclo de vida del flow (el `Application` en producción; en
 * tests un `TestScope`): las credenciales en sí no dependen de él.
 */
class DataStoreConnectionSettings(
    private val secureStore: SecureStore,
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
) : ConnectionSettings {
    constructor(
        context: Context,
        scope: CoroutineScope,
    ) : this(
        secureStore = EncryptedPrefsSecureStore(context.applicationContext),
        dataStore = context.applicationContext.connectionSettingsStore,
        scope = scope,
    )

    /**
     * Store de producción con el [secureStore] ya creado por el llamador — la
     * app comparte UNA instancia entre los ajustes y la `BasicAuthSession` del
     * verifier de C2 (mismas cookies cifradas, mismo fichero).
     */
    constructor(
        context: Context,
        scope: CoroutineScope,
        secureStore: SecureStore,
    ) : this(
        secureStore = secureStore,
        dataStore = context.applicationContext.connectionSettingsStore,
        scope = scope,
    )

    override val readAloudEnabled: StateFlow<Boolean> =
        dataStore.data
            .map { prefs -> prefs[KEY_READ_ALOUD] ?: DEFAULT_READ_ALOUD }
            .stateIn(scope, SharingStarted.Eagerly, DEFAULT_READ_ALOUD)

    override suspend fun loadCredentials(): StoredCredentials? =
        // SecureStore hace commit() síncrono + AES: disco, fuera del hilo main.
        withContext(Dispatchers.IO) {
            // Las tres claves se leen juntas: un par de credenciales a medias
            // (falta alguna clave) no vale — se comporta como "sin credenciales".
            val server = secureStore.load(KEY_SERVER_URL)
            val username = secureStore.load(KEY_USERNAME)
            val password = secureStore.load(KEY_PASSWORD)
            if (server == null || username == null || password == null) {
                null
            } else {
                StoredCredentials(serverBaseUrl = server, username = username, password = password)
            }
        }

    /** Las tres claves se escriben juntas: un par de credenciales a medias jamás se carga. */
    override suspend fun saveCredentials(credentials: StoredCredentials) =
        withContext(Dispatchers.IO) {
            secureStore.store(KEY_SERVER_URL, credentials.serverBaseUrl)
            secureStore.store(KEY_USERNAME, credentials.username)
            secureStore.store(KEY_PASSWORD, credentials.password)
        }

    override suspend fun clearCredentials() =
        withContext(Dispatchers.IO) {
            secureStore.store(KEY_SERVER_URL, null)
            secureStore.store(KEY_USERNAME, null)
            secureStore.store(KEY_PASSWORD, null)
        }

    override suspend fun setReadAloud(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_READ_ALOUD] = enabled }
    }

    companion object {
        /** Valor por defecto del toggle (el mockup lo pinta activado: la voz es un pilar de la app). */
        const val DEFAULT_READ_ALOUD = true

        private val KEY_READ_ALOUD = booleanPreferencesKey("read_aloud_enabled")

        // Claves del SecureStore (mismo fichero cifrado que las cookies de B3).
        private const val KEY_SERVER_URL = "connection.server_url"
        private const val KEY_USERNAME = "connection.username"
        private const val KEY_PASSWORD = "connection.password"
    }
}
