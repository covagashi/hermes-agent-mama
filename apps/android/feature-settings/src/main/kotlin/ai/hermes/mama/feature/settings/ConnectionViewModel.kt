package ai.hermes.mama.feature.settings

import ai.hermes.mama.gateway.AuthException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Estado de la pantalla Conexión (C2). La máquina de estados que ve la
 * usuaria es [banner] + [checking]:
 *
 * - **idle**: `banner = null`, `checking = false` — formulario editable.
 * - **testing**: `checking = true` — campos bloqueados, "Comprobando…".
 * - **success**: `banner = Connected` — "Conectado. Hermes está listo."
 * - **error**: `banner = Failed(reason)` — mensaje humano sin códigos HTTP.
 *
 * "Guardar y empezar" OK no pinta success: emite [ConnectionNavEvent.NavigateToChats]
 * y la pantalla se cierra — el success estable es para "Probar conexión".
 */
data class ConnectionUiState(
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val passwordVisible: Boolean = false,
    val readAloud: Boolean = DataStoreConnectionSettings.DEFAULT_READ_ALOUD,
    val checking: Boolean = false,
    val banner: ConnectionBanner? = null,
) {
    // §8: la contraseña nunca sale en toString — el estado puede acabar en logs.
    override fun toString(): String =
        "ConnectionUiState(server=$server, username=$username, password=•••, " +
            "passwordVisible=$passwordVisible, readAloud=$readAloud, " +
            "checking=$checking, banner=$banner)"
}

/** Banner de estado bajo el formulario (éxito o razón de error humano). */
sealed interface ConnectionBanner {
    /** `me()` respondió: conexión probada. [displayName] puede venir vacío. */
    data class Connected(
        val displayName: String?,
    ) : ConnectionBanner

    data class Failed(
        val reason: ConnectionErrorReason,
    ) : ConnectionBanner
}

/**
 * Razones de error que la UI traduce a strings humanos (sin HTTP ni jerga).
 * Un caso por string — el mapeo vive en `ConnectionScreen` para que el test de
 * ViewModel compare la razón tipada, no el texto.
 */
enum class ConnectionErrorReason {
    MissingFields,
    BadAddress,
    InsecureAddress,
    WrongCredentials,
    ServerUnreachable,
    RateLimited,
    SessionExpired,
    Unexpected,
}

/** Eventos de un disparo hacia la navegación (SharedFlow, no estado). */
sealed interface ConnectionNavEvent {
    /** "Guardar y empezar" validado → Chats (C8 monta la navegación real). */
    data object NavigateToChats : ConnectionNavEvent
}

/**
 * ViewModel de la pantalla Conexión (ROADMAP §5/C2).
 *
 * - [onTest] (`Probar conexión`): `login` + `me` vía [verifier]; resultado sólo
 *   en el banner, nada se guarda.
 * - [onSave] (`Guardar y empezar`): la MISMA verificación y, si `me` responde,
 *   persiste credenciales cifradas + preferencia de voz y emite
 *   [ConnectionNavEvent.NavigateToChats]. Credenciales malas → nunca tocan disco.
 * - Errores: [AuthException] tipadas y fallos de transporte se traducen a
 *   [ConnectionErrorReason]; el texto lo pone la UI.
 *
 * Todo el trabajo va en [viewModelScope] (nada de `runBlocking`); un segundo
 * toque durante una comprobación en curso se ignora y cambiar de pantalla la
 * cancela sola.
 */
class ConnectionViewModel(
    private val settings: ConnectionSettings,
    private val verifier: ConnectionVerifier,
    private val logger: (String) -> Unit = {},
) : ViewModel() {
    private val _uiState = MutableStateFlow(ConnectionUiState())

    /** Estado observable de la pantalla. */
    val uiState: StateFlow<ConnectionUiState> = _uiState.asStateFlow()

    private val _navigation = MutableSharedFlow<ConnectionNavEvent>(extraBufferCapacity = 1)

    /** Eventos de navegación de un disparo. */
    val navigation: SharedFlow<ConnectionNavEvent> = _navigation.asSharedFlow()

    private var checkJob: Job? = null

    init {
        // Preferencia de voz persistida: vive en el StateFlow del settings y se
        // refleja aquí; al abrir con credenciales ya guardadas (acceso por
        // pulsación larga) el formulario llega relleno.
        viewModelScope.launch {
            _uiState.update { it.copy(readAloud = settings.readAloudEnabled.value) }
            settings.loadCredentials()?.let { saved ->
                _uiState.update {
                    it.copy(
                        server = saved.serverBaseUrl,
                        username = saved.username,
                        password = saved.password,
                    )
                }
            }
        }
    }

    fun onServerChange(value: String) = updateForm { it.copy(server = value) }

    fun onUsernameChange(value: String) = updateForm { it.copy(username = value) }

    fun onPasswordChange(value: String) = updateForm { it.copy(password = value) }

    fun onTogglePasswordVisibility() {
        log("toggle password")
        updateForm { it.copy(passwordVisible = !it.passwordVisible) }
    }

    fun onReadAloudChange(enabled: Boolean) {
        log("readaloud -> $enabled")
        _uiState.update { it.copy(readAloud = enabled) }
        viewModelScope.launch { settings.setReadAloud(enabled) }
    }

    /** `Probar conexión`: verifica sin persistir nada. */
    fun onTest() = check(saveAndStart = false)

    /** `Guardar y empezar`: verifica y, si responde `me`, persiste y navega a Chats. */
    fun onSave() = check(saveAndStart = true)

    private fun updateForm(edit: (ConnectionUiState) -> ConnectionUiState) {
        if (_uiState.value.checking) return // formulario bloqueado durante la comprobación
        _uiState.update(edit)
    }

    private fun log(message: String) = runCatching { logger(message) }

    private fun check(saveAndStart: Boolean) {
        val state = _uiState.value
        log(
            "check(save=$saveAndStart) checking=${state.checking} " +
                "fields serverBlank=${state.server.isBlank()} " +
                "userBlank=${state.username.isBlank()} passEmpty=${state.password.isEmpty()}",
        )
        if (state.checking) return
        val serverUrl = normalizeServerUrl(state.server)
        val reason =
            when {
                state.username.isBlank() || state.password.isEmpty() || state.server.isBlank() ->
                    ConnectionErrorReason.MissingFields
                serverUrl == null -> ConnectionErrorReason.BadAddress
                else -> null
            }
        if (reason != null || serverUrl == null) {
            log("check rejected: ${reason ?: ConnectionErrorReason.BadAddress}")
            _uiState.update { it.copy(banner = ConnectionBanner.Failed(reason ?: ConnectionErrorReason.BadAddress)) }
            return
        }
        checkJob?.cancel()
        checkJob =
            viewModelScope.launch {
                log("checkJob running")
                _uiState.update { it.copy(checking = true, banner = null) }
                try {
                    val identity = verifier.verify(serverUrl, state.username.trim(), state.password)
                    log("verify ok")
                    if (saveAndStart) {
                        // §8: sólo se guardan credenciales YA validadas por `me`.
                        settings.saveCredentials(
                            StoredCredentials(
                                serverBaseUrl = serverUrl.toString(),
                                username = state.username.trim(),
                                password = state.password,
                            ),
                        )
                        log("credentials saved")
                        _uiState.update { it.copy(checking = false) }
                        _navigation.emit(ConnectionNavEvent.NavigateToChats)
                        log("nav emitted")
                    } else {
                        _uiState.update {
                            it.copy(checking = false, banner = ConnectionBanner.Connected(identity.displayName))
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    // La usuaria sólo ve el banner humano: cualquier fallo no
                    // tipado (de disco, de red, de la propia UI) es "Unexpected".
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    // §8: el log sólo lleva el TIPO de excepción — los mensajes
                    // pueden arrastrar host/cuerpo; la UI ve la razón humana.
                    runCatching { logger("connection check failed (${e::class.simpleName})") }
                    _uiState.update {
                        it.copy(checking = false, banner = ConnectionBanner.Failed(mapError(e)))
                    }
                }
            }
    }

    private fun mapError(e: Exception): ConnectionErrorReason =
        when (e) {
            is AuthException.InvalidCredentials -> ConnectionErrorReason.WrongCredentials
            is AuthException.RateLimited -> ConnectionErrorReason.RateLimited
            is AuthException.SessionExpired -> ConnectionErrorReason.SessionExpired
            is AuthException.CleartextForbidden -> ConnectionErrorReason.InsecureAddress
            is AuthException, is IOException -> ConnectionErrorReason.ServerUnreachable
            else -> ConnectionErrorReason.Unexpected
        }
}
