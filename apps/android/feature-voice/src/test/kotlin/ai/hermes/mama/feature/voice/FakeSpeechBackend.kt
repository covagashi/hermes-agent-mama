package ai.hermes.mama.feature.voice

/**
 * Backend + engine falsos para tests JVM de [SpeechInput] (D1).
 *
 * Sin Robolectric: el SPI no tiene tipos Android y los códigos `ERROR_*` son
 * constantes inlined. El test emite los callbacks a mano ([FakeSpeechEngine.emit*]),
 * como haría `RecognitionListener` en el dispositivo.
 */
class FakeSpeechBackend(
    var recognitionAvailable: Boolean = true,
    var onDeviceRecognition: Boolean = false,
) : SpeechBackend {
    val engines = mutableListOf<FakeSpeechEngine>()

    override fun isRecognitionAvailable(): Boolean = recognitionAvailable

    override fun supportsOnDeviceRecognition(): Boolean = onDeviceRecognition

    override fun createEngine(listener: SpeechEngineListener): SpeechEngine =
        FakeSpeechEngine(listener).also(engines::add)
}

class FakeSpeechEngine(
    private val listener: SpeechEngineListener,
) : SpeechEngine {
    var lastConfig: SpeechConfig? = null
        private set
    var started = false
        private set
    var stopped = false
        private set
    var cancelled = false
        private set
    var destroyed = false
        private set

    override fun start(config: SpeechConfig) {
        started = true
        lastConfig = config
    }

    override fun stop() {
        stopped = true
    }

    override fun cancel() {
        cancelled = true
    }

    override fun destroy() {
        destroyed = true
    }

    // Simulan los callbacks del servicio de reconocimiento (RecognitionListener).
    fun emitReady() = listener.onReady()

    fun emitPartial(text: String) = listener.onPartial(text)

    fun emitResult(text: String) = listener.onResult(text)

    /** [code] = `SpeechRecognizer.ERROR_*` (constantes inlined: valen en JVM). */
    fun emitError(code: Int) = listener.onError(code)
}
