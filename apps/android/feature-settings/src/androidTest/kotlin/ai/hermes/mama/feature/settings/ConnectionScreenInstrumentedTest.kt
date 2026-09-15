package ai.hermes.mama.feature.settings

import ai.hermes.mama.core.ui.theme.MamaTheme
import ai.hermes.mama.testing.FakeGateway
import ai.hermes.mama.testing.FakeGatewayScript
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * C2 instrumentado (ROADMAP §5): la pantalla Conexión real
 * (`ConnectionScreen` + `ConnectionViewModel` + `BasicAuthConnectionVerifier`
 * → `BasicAuthSession` → HTTP) contra el **FakeGateway empotrado** en el
 * propio dispositivo, en puerto efímero de loopback. Hermético: no depende del
 * host ni de `10.0.2.2` — el bind es `127.0.0.1` del emulador.
 *
 * Los tres casos del roadmap:
 *  1. credenciales correctas → "Guardar y empezar" navega a Chats y persiste;
 *  2. contraseña incorrecta → "Usuario o contraseña incorrectos";
 *  3. servidor caído → "No encuentro a Hermes. ¿Está encendido el servidor?".
 */
@RunWith(AndroidJUnit4::class)
class ConnectionScreenInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var gateway: FakeGateway? = null

    @Volatile
    private var navigatedToChats = false

    @Before
    fun reset() {
        navigatedToChats = false
    }

    @After
    fun tearDown() {
        gateway?.close()
        gateway = null
    }

    // ---- casos del roadmap ----

    @Test
    fun credenciales_correctas_guardar_navega_a_Chats_y_persiste() {
        val gateway = startGateway()
        val settings = freshSettings()

        val vm = setScreen(settings)
        fillFields(server = gateway.httpUrl, username = "usuario", password = "mama")
        clickButton("Guardar y empezar")

        try {
            composeRule.waitUntil(timeoutMillis = 15_000) { navigatedToChats }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError(
                "no navegó: banner=${vm.uiState.value.banner} " +
                    "checking=${vm.uiState.value.checking} " +
                    "server='${vm.uiState.value.server}' user='${vm.uiState.value.username}'",
                e,
            )
        }
        val saved = runBlocking { settings.loadCredentials() }
        assertEquals(gateway.httpUrl + "/", saved?.serverBaseUrl)
        assertEquals("usuario", saved?.username)
        assertEquals("mama", saved?.password)
    }

    @Test
    fun contrasena_incorrecta_muestra_el_mensaje_humano() {
        val gateway = startGateway()
        setScreen(freshSettings())
        fillFields(server = gateway.httpUrl, username = "usuario", password = "mala")
        clickButton("Probar conexión")

        waitForText("Usuario o contraseña incorrectos")
        assertFalse(navigatedToChats)
    }

    @Test
    fun servidor_caido_muestra_el_mensaje_humano() {
        // Puerto 1 del propio loopback: refused inmediato, sin timeout largo.
        setScreen(freshSettings())
        fillFields(server = "http://127.0.0.1:1", username = "usuario", password = "mama")
        clickButton("Probar conexión")

        waitForText("No encuentro a Hermes. ¿Está encendido el servidor?")
        assertFalse(navigatedToChats)
    }

    @Test
    fun probar_con_credenciales_correctas_muestra_el_banner_conectado() {
        val gateway = startGateway()
        setScreen(freshSettings())
        fillFields(server = gateway.httpUrl, username = "usuario", password = "mama")
        clickButton("Probar conexión")

        // display_name del guion hola_mundo (auth.display_name → me()).
        waitForText("Conectado como Usuario de Prueba.")
        assertFalse(navigatedToChats) // "Probar" nunca navega ni guarda
    }

    @Test
    fun el_ojo_de_la_contrasena_alterna_visibilidad() {
        setScreen(freshSettings())
        composeRule
            .onNodeWithContentDescription("Mostrar contraseña")
            .assertExists()
            .performClick()
        composeRule.onNodeWithContentDescription("Ocultar contraseña").assertExists()
    }

    @Test
    fun el_toggle_de_voz_se_persiste_en_los_ajustes() {
        val settings = freshSettings()
        setScreen(settings)
        composeRule.onNodeWithText("Leer las respuestas en voz alta").assertExists()
        // El switch del mockup es un nodo `toggleable`: empieza activado (defecto §mockup).
        composeRule.onNode(isToggleable()).assertExists().performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 15_000) { !settings.readAloudEnabled.value }
    }

    // ---- helpers ----

    private fun startGateway(): FakeGateway {
        val g = FakeGateway(FakeGatewayScript.load("hola_mundo")).start()
        gateway = g
        return g
    }

    /** Settings reales de la app (SecureStore cifrado + DataStore) en ficheros únicos por test. */
    private fun freshSettings(): DataStoreConnectionSettings {
        // El contador se captura eager (el produceFile del DataStore se evalúa
        // perezosamente) y vive en companion: JUnit4 instancia la clase por
        // test, así que un contador de instancia reiniciaría los nombres y el
        // store del test anterior sigue activo en `scope` ("multiple
        // DataStores active for the same file").
        val n = ++testCounter
        val store =
            PreferenceDataStoreFactory.create(scope = scope) {
                File(context.cacheDir, "c2_test_$n.preferences_pb")
            }
        return DataStoreConnectionSettings(
            secureStore = EncryptedPrefsSecureStore(context, fileName = "c2_test_$n.secure"),
            dataStore = store,
            scope = scope,
        )
    }

    private fun setScreen(settings: ConnectionSettings): ConnectionViewModel {
        val vm =
            ConnectionViewModel(
                settings = settings,
                verifier = BasicAuthConnectionVerifier(logger = { Log.w(TAG, it) }),
                logger = { Log.w(TAG, it) },
            )
        composeRule.setContent {
            MamaTheme {
                ConnectionScreen(
                    viewModel = vm,
                    onNavigateToChats = { navigatedToChats = true },
                )
            }
        }
        return vm
    }

    private fun fillFields(
        server: String,
        username: String,
        password: String,
    ) {
        composeRule
            .onNodeWithContentDescription("Dirección del servidor")
            .performTextInput(server)
        composeRule
            .onNodeWithContentDescription("Usuario")
            .performTextInput(username)
        composeRule
            .onNodeWithContentDescription("Contraseña")
            .performTextInput(password)
    }

    private fun clickButton(text: String) {
        composeRule.onNodeWithText(text).performClick()
        composeRule.waitForIdle()
    }

    /** Espera activa a que aparezca un texto en el árbol de semántica. */
    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule
                .onAllNodesWithText(text)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private companion object {
        const val TAG = "C2ConnTest"
        var testCounter = 0
    }
}
