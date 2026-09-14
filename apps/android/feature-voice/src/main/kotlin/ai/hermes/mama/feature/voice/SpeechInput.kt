package ai.hermes.mama.feature.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.speech.SpeechRecognizer
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Estado del dictado por voz — lo observa el composer del chat (C5) como [StateFlow].
 *
 * Ciclo "mantener para hablar": Idle → Listening (con parciales en vivo) → Done(text)
 * o Error(kind). Cancelar o soltar sin hablar devuelve a Idle / Error.
 */
sealed interface SpeechState {
    /** No se está dictando. */
    data object Idle : SpeechState

    /** Micrófono abierto; [partial] es la mejor transcripción parcial ("" al empezar). */
    data class Listening(
        val partial: String = "",
    ) : SpeechState

    /** Dictado terminado con texto final. */
    data class Done(
        val text: String,
    ) : SpeechState

    /** Falló el dictado; [kind] decide el mensaje humano y si se ofrece "Escribir". */
    data class Error(
        val kind: SpeechErrorKind,
    ) : SpeechState
}

/**
 * Tipos de fallo del dictado, pensados para mensajes sin tecnicismos.
 *
 * [fallsBackToTyping] indica que la UI debe dejar a la usuaria en modo "Escribir"
 * (teclado): el micrófono no va a funcionar en este intento.
 */
enum class SpeechErrorKind(
    val fallsBackToTyping: Boolean = false,
) {
    /** Falta el permiso RECORD_AUDIO (negado o nunca pedido). */
    PermissionDenied(fallsBackToTyping = true),

    /** El dispositivo no tiene servicio de reconocimiento → usar [RecognizerIntentFallback]. */
    NotAvailable(fallsBackToTyping = true),

    /** No se entendió nada o hubo silencio (NO_MATCH / SPEECH_TIMEOUT). */
    NoMatch,

    /** Problema grabando audio (micro ocupado por hardware, error de lectura…). */
    Audio,

    /** Fallo de red con el servicio de reconocimiento online. */
    Network,

    /** El servicio de reconocimiento respondió con error o se desconectó. */
    Server,

    /** El reconocedor está ocupado o saturado de peticiones. */
    Busy,

    /** Este dispositivo no puede dictar en el idioma pedido (es-ES). */
    LanguageUnavailable(fallsBackToTyping = true),

    /** Cualquier otro fallo. */
    Unknown(fallsBackToTyping = true),
    ;

    companion object {
        /**
         * Traduce un código `SpeechRecognizer.ERROR_*` a [SpeechErrorKind].
         *
         * Las constantes `ERROR_*` son `static final int`: el compilador las incrusta
         * y esta función también corre en tests JVM puros (sin Robolectric).
         */
        fun fromRecognizerCode(code: Int): SpeechErrorKind =
            when (code) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> PermissionDenied
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                -> NoMatch
                SpeechRecognizer.ERROR_AUDIO -> Audio
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
                -> Network
                SpeechRecognizer.ERROR_SERVER -> Server
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
                -> Busy
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                -> LanguageUnavailable
                else -> Unknown
            }
    }
}

/**
 * D1 · Entrada de voz: wrapper de [SpeechRecognizer] pensado para "mantener para hablar".
 *
 * - `es-ES`, resultados parciales en vivo y `EXTRA_PREFER_OFFLINE` cuando el
 *   dispositivo tiene reconocimiento on-device (API ≥ 31).
 * - Si el usuario niega RECORD_AUDIO → [SpeechErrorKind.PermissionDenied]; si el móvil
 *   no tiene servicio de reconocimiento → [SpeechErrorKind.NotAvailable] y el composer
 *   puede ofrecer el fallback de actividad ([RecognizerIntentFallback]).
 * - Sin lógica de UI: sólo expone [state]. La pantalla (C5) decide cómo pintarlo.
 *
 * Usar desde el hilo principal (requisito de [SpeechRecognizer]). Liberar con
 * [release] (p. ej. `onCleared` del ViewModel).
 */
class SpeechInput(
    private val backend: SpeechBackend,
    private val audioPermission: AudioPermissionChecker,
    private val languageTag: String = DEFAULT_LANGUAGE_TAG,
) {
    private val _state = MutableStateFlow<SpeechState>(SpeechState.Idle)
    val state: StateFlow<SpeechState> = _state.asStateFlow()

    /** Motor de la sesión en curso; null cuando no se está dictando. */
    private var engine: SpeechEngine? = null

    /** ¿El móvil tiene servicio de reconocimiento? Si no, ver [RecognizerIntentFallback]. */
    fun isRecognitionAvailable(): Boolean = backend.isRecognitionAvailable()

    /** ¿Tenemos ya el permiso de micrófono? */
    fun hasAudioPermission(): Boolean = audioPermission.isGranted()

    /**
     * La usuaria aprieta el micrófono: abre una sesión de dictado.
     *
     * Emite [SpeechState.Listening] de inmediato; los parciales llegan en vivo y el
     * texto final llega como [SpeechState.Done] al soltar ([stopListening]) o cuando
     * el servicio cierra la escucha. Los fallos llegan como [SpeechState.Error].
     */
    @MainThread
    fun startListening() {
        val rejection =
            when {
                !audioPermission.isGranted() -> SpeechErrorKind.PermissionDenied
                !backend.isRecognitionAvailable() -> SpeechErrorKind.NotAvailable
                else -> null
            }
        if (rejection != null) {
            _state.value = SpeechState.Error(rejection)
            return
        }
        releaseEngine(cancel = true)
        val listener = SessionListener()
        // createSpeechRecognizer puede lanzar (servicio roto, hilo no-main, SecurityException
        // en algunos OEM) aunque isRecognitionAvailable haya dicho que sí: nunca se crashea.
        val session =
            runCatching { backend.createEngine(listener) }
                .getOrElse { e ->
                    Timber.w(e, "No se pudo crear el reconocedor")
                    fail(SpeechErrorKind.Unknown)
                    return
                }
        listener.session = session
        engine = session
        _state.value = SpeechState.Listening()
        runCatching {
            session.start(
                SpeechConfig(
                    languageTag = languageTag,
                    partialResults = true,
                    preferOffline = backend.supportsOnDeviceRecognition(),
                ),
            )
        }.onFailure { e ->
            // Nunca se crashea por un fallo del servicio de voz: la usuaria puede escribir.
            Timber.w(e, "No se pudo iniciar el dictado")
            fail(SpeechErrorKind.Unknown)
        }
    }

    /**
     * La usuaria suelta el micrófono: el servicio deja de escuchar y el resultado
     * final llega por el listener como [SpeechState.Done] / [SpeechState.Error].
     */
    @MainThread
    fun stopListening() {
        runCatching { engine?.stop() }
            .onFailure { e -> Timber.w(e, "No se pudo cerrar la escucha") }
    }

    /** Aborta la sesión en curso sin emitir resultado y vuelve a [SpeechState.Idle]. */
    @MainThread
    fun cancel() {
        releaseEngine(cancel = true)
        _state.value = SpeechState.Idle
    }

    /** Libera el reconocedor por completo (onCleared del ViewModel / onDestroy). */
    @MainThread
    fun release() = cancel()

    private fun fail(kind: SpeechErrorKind) {
        _state.value = SpeechState.Error(kind)
        releaseEngine(cancel = false)
    }

    private fun releaseEngine(cancel: Boolean) {
        val current = engine ?: return
        engine = null
        if (cancel) {
            runCatching { current.cancel() }
        }
        runCatching { current.destroy() }
    }

    /**
     * Callbacks de UNA sesión: se ignoran si la sesión ya no es la activa
     * (callbacks tardíos tras cancel() o tras un release() + startListening() nuevo).
     */
    private inner class SessionListener : SpeechEngineListener {
        var session: SpeechEngine? = null

        private val isActive: Boolean
            get() = session != null && engine === session

        override fun onReady() {
            if (isActive) _state.value = SpeechState.Listening()
        }

        override fun onPartial(text: String) {
            if (isActive) _state.value = SpeechState.Listening(text)
        }

        override fun onResult(text: String) {
            if (!isActive) return
            val clean = text.trim()
            if (clean.isEmpty()) {
                fail(SpeechErrorKind.NoMatch)
            } else {
                _state.value = SpeechState.Done(clean)
                releaseEngine(cancel = false)
            }
        }

        override fun onError(errorCode: Int) {
            if (!isActive) return
            fail(SpeechErrorKind.fromRecognizerCode(errorCode))
        }
    }

    companion object {
        /** Idioma del dictado (español de España, ROADMAP §0: UI en español). */
        const val DEFAULT_LANGUAGE_TAG = "es-ES"

        /**
         * Construye el [SpeechInput] real sobre [SpeechRecognizer] del sistema.
         * Llamar en el hilo principal.
         */
        @MainThread
        fun create(context: Context): SpeechInput =
            SpeechInput(
                backend = SpeechRecognizerBackend(context.applicationContext),
                audioPermission =
                    AudioPermissionChecker {
                        ContextCompat.checkSelfPermission(
                            context.applicationContext,
                            Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                    },
            )
    }
}
