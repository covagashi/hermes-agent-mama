package ai.hermes.mama.feature.voice

// SPI de la entrada de voz (D1): lo que SpeechInput necesita del sistema y lo que
// los tests falsean en JVM (FakeSpeechBackend/FakeSpeechEngine), sin Robolectric.
//
// No hay tipos de Android en estas interfaces a propósito: SpeechConfig sustituye
// al Intent de RecognizerIntent y los errores viajan como códigos ERROR_*
// (constantes `static final int` incrustadas en compilación, seguras en JVM).

/** Configuración de una sesión de dictado (la traducción a `Intent` la hace el engine). */
data class SpeechConfig(
    /** Etiqueta BCP-47 del idioma, p. ej. `es-ES`. */
    val languageTag: String = SpeechInput.DEFAULT_LANGUAGE_TAG,
    /** Pedir resultados parciales en vivo (`EXTRA_PARTIAL_RESULTS`). */
    val partialResults: Boolean = true,
    /** Pedir reconocimiento on-device (`EXTRA_PREFER_OFFLINE`) cuando hay modelo. */
    val preferOffline: Boolean = false,
)

/** Callbacks de una sesión de dictado, espejo de `RecognitionListener`. */
interface SpeechEngineListener {
    /** El servicio está listo para oír (onReadyForSpeech). */
    fun onReady()

    /** Transcripción parcial en vivo (onPartialResults, mejor hipótesis). */
    fun onPartial(text: String)

    /** Transcripción final (onResults, mejor hipótesis; puede venir vacía). */
    fun onResult(text: String)

    /** Error: código `SpeechRecognizer.ERROR_*` (onError). */
    fun onError(errorCode: Int)
}

/** Un reconocedor en curso. Ciclo: [start] → [stop]/[cancel] → [destroy]. */
interface SpeechEngine {
    /** Empieza a escuchar con esta configuración. */
    fun start(config: SpeechConfig)

    /** Deja de escuchar; el resultado final sigue llegando por el listener. */
    fun stop()

    /** Aborta sin resultado; callbacks tardíos pueden seguir llegando. */
    fun cancel()

    /** Libera recursos; la instancia no se reutiliza. */
    fun destroy()
}

/** Lo que el dispositivo puede ofrecer; la implementación real usa `SpeechRecognizer`. */
interface SpeechBackend {
    /** `SpeechRecognizer.isRecognitionAvailable` — false → fallback RecognizerIntent. */
    fun isRecognitionAvailable(): Boolean

    /** ¿Hay reconocimiento on-device (API ≥ 31)? Entonces pedimos `EXTRA_PREFER_OFFLINE`. */
    fun supportsOnDeviceRecognition(): Boolean

    /** Crea un engine nuevo por sesión (SpeechRecognizer no se reutiliza tras destroy). */
    fun createEngine(listener: SpeechEngineListener): SpeechEngine
}

/** ¿Tenemos RECORD_AUDIO concedido? Se falsea con un lambda en tests. */
fun interface AudioPermissionChecker {
    fun isGranted(): Boolean
}
