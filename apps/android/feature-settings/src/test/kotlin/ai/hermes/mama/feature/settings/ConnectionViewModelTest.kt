package ai.hermes.mama.feature.settings

import ai.hermes.mama.gateway.AuthException
import ai.hermes.mama.gateway.SessionIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * `ConnectionViewModel` (C2): transiciones idle → testing → success/error y
 * traducción de errores tipados a razones humanas (sin exponer HTTP a la UI).
 *
 * `UnconfinedTestDispatcher` hace de `Main`: cada `launch` del viewModelScope
 * corre eager hasta la primera suspensión — los asserts son síncronos salvo
 * los casos de "en vuelo", que paran al verifier en un `CompletableDeferred`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeEach
    fun setMain() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun resetMain() = Dispatchers.resetMain()

    // ---- estado inicial ----

    @Test
    fun `estado inicial es idle`() =
        harness { vm, _, _ ->
            val s = vm.uiState.value
            assertNull(s.banner)
            assertTrue(!s.checking)
            assertEquals(DataStoreConnectionSettings.DEFAULT_READ_ALOUD, s.readAloud)
        }

    @Test
    fun `credenciales guardadas rellenan el formulario (acceso por pulsacion larga)`() {
        val saved = StoredCredentials("https://hermes.example.invalid/", "usuario", "mama")
        harness(savedCredentials = saved) { vm, _, _ ->
            assertEquals("https://hermes.example.invalid/", vm.uiState.value.server)
            assertEquals("usuario", vm.uiState.value.username)
            assertEquals("mama", vm.uiState.value.password)
        }
    }

    // ---- Probar conexión ----

    @Test
    fun `probar con exito muestra el banner conectado`() =
        harness { vm, _, _ ->
            vm.fill()
            vm.onTest()
            val banner = vm.uiState.value.banner
            assertIs<ConnectionBanner.Connected>(banner)
            assertEquals("Usuario Fake", banner.displayName)
            assertTrue(!vm.uiState.value.checking)
        }

    @Test
    fun `probar marca checking mientras verifica`() {
        val gate = CompletableDeferred<SessionIdentity>()
        val gated = ConnectionVerifier { _, _, _ -> gate.await() }
        val settings = FakeConnectionSettings(savedCredentials = null)
        val vm = ConnectionViewModel(settings, gated)

        vm.fill()
        vm.onTest()
        // La verificación quedó en vuelo: la pantalla muestra "Comprobando…".
        assertTrue(vm.uiState.value.checking)
        assertNull(vm.uiState.value.banner)

        gate.complete(SessionIdentity(userId = "u_fake", displayName = "Usuario Fake"))
        assertTrue(!vm.uiState.value.checking)
        assertIs<ConnectionBanner.Connected>(vm.uiState.value.banner)
    }

    @Test
    fun `probar con credenciales malas muestra WrongCredentials`() =
        harness(verifier = failing(AuthException.InvalidCredentials())) { vm, _, _ ->
            vm.fill()
            vm.onTest()
            assertEquals(
                ConnectionErrorReason.WrongCredentials,
                (vm.uiState.value.banner as? ConnectionBanner.Failed)?.reason,
            )
        }

    @Test
    fun `probar con servidor caido muestra ServerUnreachable`() =
        harness(verifier = failing(IOException("connection refused"))) { vm, _, _ ->
            vm.fill()
            vm.onTest()
            assertEquals(
                ConnectionErrorReason.ServerUnreachable,
                (vm.uiState.value.banner as? ConnectionBanner.Failed)?.reason,
            )
        }

    @Test
    fun `ProviderMissing y UnexpectedStatus tambien son ServerUnreachable`() =
        harness(verifier = failing(AuthException.UnexpectedStatus(500))) { vm, _, _ ->
            vm.fill()
            vm.onTest()
            assertEquals(
                ConnectionErrorReason.ServerUnreachable,
                (vm.uiState.value.banner as? ConnectionBanner.Failed)?.reason,
            )
        }

    @Test
    fun `provider missing es ServerUnreachable`() =
        harness(verifier = failing(AuthException.ProviderMissing())) { vm, _, _ ->
            vm.fill()
            vm.onTest()
            assertEquals(
                ConnectionErrorReason.ServerUnreachable,
                (vm.uiState.value.banner as? ConnectionBanner.Failed)?.reason,
            )
        }

    @Test
    fun `RateLimited muestra su razon`() =
        harness(verifier = failing(AuthException.RateLimited(30.seconds))) { vm, _, _ ->
            vm.fill()
            vm.onTest()
            assertEquals(
                ConnectionErrorReason.RateLimited,
                (vm.uiState.value.banner as? ConnectionBanner.Failed)?.reason,
            )
        }

    @Test
    fun `SessionExpired muestra su razon`() =
        harness(verifier = failing(AuthException.SessionExpired())) { vm, _, _ ->
            vm.fill()
            vm.onTest()
            assertEquals(
                ConnectionErrorReason.SessionExpired,
                (vm.uiState.value.banner as? ConnectionBanner.Failed)?.reason,
            )
        }

    @Test
    fun `CleartextForbidden muestra InsecureAddress`() =
        harness(verifier = failing(AuthException.CleartextForbidden())) { vm, _, _ ->
            vm.fill()
            vm.onTest()
            assertEquals(
                ConnectionErrorReason.InsecureAddress,
                (vm.uiState.value.banner as? ConnectionBanner.Failed)?.reason,
            )
        }

    @Test
    fun `MalformedResponse se trata como servidor no encontrado`() =
        harness(verifier = failing(AuthException.MalformedResponse())) { vm, _, _ ->
            vm.fill()
            vm.onTest()
            assertEquals(
                ConnectionErrorReason.ServerUnreachable,
                (vm.uiState.value.banner as? ConnectionBanner.Failed)?.reason,
            )
        }

    @Test
    fun `error inesperado muestra Unexpected`() =
        harness(verifier = failing(IllegalStateException("boom"))) { vm, _, _ ->
            vm.fill()
            vm.onTest()
            assertEquals(
                ConnectionErrorReason.Unexpected,
                (vm.uiState.value.banner as? ConnectionBanner.Failed)?.reason,
            )
        }

    // ---- validación previa ----

    @Test
    fun `campos vacios no llaman al servidor`() {
        val verifier = CountingVerifier()
        harness(verifier = verifier) { vm, _, _ ->
            vm.onTest()
            assertEquals(0, verifier.calls)
            assertEquals(
                ConnectionErrorReason.MissingFields,
                (vm.uiState.value.banner as? ConnectionBanner.Failed)?.reason,
            )
        }
    }

    @Test
    fun `direccion mala no llama al servidor`() {
        val verifier = CountingVerifier()
        harness(verifier = verifier) { vm, _, _ ->
            vm.onServerChange("no es una dirección")
            vm.onUsernameChange("usuario")
            vm.onPasswordChange("mama")
            vm.onTest()
            assertEquals(0, verifier.calls)
            assertEquals(
                ConnectionErrorReason.BadAddress,
                (vm.uiState.value.banner as? ConnectionBanner.Failed)?.reason,
            )
        }
    }

    @Test
    fun `sin esquema se normaliza a https antes de verificar`() {
        val verifier = CountingVerifier()
        harness(verifier = verifier) { vm, _, _ ->
            vm.fill(server = "hermes.example.invalid")
            vm.onTest()
            assertEquals("https://hermes.example.invalid/", verifier.lastBaseUrl?.toString())
        }
    }

    // ---- Guardar y empezar ----

    @Test
    fun `guardar con exito persiste credenciales y navega a Chats`() =
        harness { vm, settings, _ ->
            val received = mutableListOf<ConnectionNavEvent>()
            val collector = vm.navigation.onEach { received += it }.launchIn(testScope())
            vm.fill()
            vm.onSave()
            collector.cancel()
            assertEquals(listOf<ConnectionNavEvent>(ConnectionNavEvent.NavigateToChats), received)
            assertEquals(
                StoredCredentials("https://hermes.example.invalid/", "usuario", "mama"),
                settings.credentials,
            )
        }

    @Test
    fun `guardar con credenciales malas no persiste ni navega`() =
        harness(verifier = failing(AuthException.InvalidCredentials())) { vm, settings, _ ->
            val received = mutableListOf<ConnectionNavEvent>()
            val collector = vm.navigation.onEach { received += it }.launchIn(testScope())
            vm.fill()
            vm.onSave()
            collector.cancel()
            assertTrue(received.isEmpty())
            assertNull(settings.credentials)
        }

    // ---- preferencia de voz ----

    @Test
    fun `el toggle de voz se persiste en los ajustes`() =
        harness { vm, settings, _ ->
            vm.onReadAloudChange(false)
            assertTrue(!settings.readAloudEnabled.value)
            assertTrue(!vm.uiState.value.readAloud)
        }

    // ---- helpers ----

    /** Colector eager para el SharedFlow de navegación (Unconfined → entrega inmediata). */
    private fun testScope() = CoroutineScope(dispatcher)

    private fun ConnectionViewModel.fill(
        server: String = "https://hermes.example.invalid",
        username: String = "usuario",
        password: String = "mama",
    ) {
        onServerChange(server)
        onUsernameChange(username)
        onPasswordChange(password)
    }

    private fun failing(error: Exception): ConnectionVerifier = ConnectionVerifier { _, _, _ -> throw error }

    private fun harness(
        verifier: ConnectionVerifier = okVerifier(),
        savedCredentials: StoredCredentials? = null,
        block: (ConnectionViewModel, FakeConnectionSettings, ConnectionVerifier) -> Unit,
    ) {
        val settings = FakeConnectionSettings(savedCredentials)
        val vm = ConnectionViewModel(settings, verifier)
        block(vm, settings, verifier)
    }

    private fun okVerifier(): ConnectionVerifier =
        ConnectionVerifier { _, _, _ ->
            SessionIdentity(userId = "u_fake", displayName = "Usuario Fake")
        }

    private class CountingVerifier : ConnectionVerifier {
        var calls = 0
            private set
        var lastBaseUrl: okhttp3.HttpUrl? = null
            private set

        override suspend fun verify(
            baseUrl: okhttp3.HttpUrl,
            username: String,
            password: String,
        ): SessionIdentity {
            calls++
            lastBaseUrl = baseUrl
            return SessionIdentity(userId = "u_fake", displayName = "Usuario Fake")
        }
    }

    /** ConnectionSettings en memoria (los tests de disco viven aparte). */
    private class FakeConnectionSettings(
        private val savedCredentials: StoredCredentials?,
    ) : ConnectionSettings {
        private val _readAloudEnabled = MutableStateFlow(DataStoreConnectionSettings.DEFAULT_READ_ALOUD)
        override val readAloudEnabled: StateFlow<Boolean> = _readAloudEnabled

        var credentials: StoredCredentials? = savedCredentials
            private set

        override suspend fun loadCredentials(): StoredCredentials? = credentials

        override suspend fun saveCredentials(credentials: StoredCredentials) {
            this.credentials = credentials
        }

        override suspend fun clearCredentials() {
            credentials = null
        }

        override suspend fun setReadAloud(enabled: Boolean) {
            _readAloudEnabled.value = enabled
        }
    }
}
