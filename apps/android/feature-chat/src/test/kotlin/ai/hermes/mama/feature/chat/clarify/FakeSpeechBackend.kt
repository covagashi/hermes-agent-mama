package ai.hermes.mama.feature.chat.clarify

import ai.hermes.mama.feature.voice.SpeechBackend
import ai.hermes.mama.feature.voice.SpeechConfig
import ai.hermes.mama.feature.voice.SpeechEngine
import ai.hermes.mama.feature.voice.SpeechEngineListener

/**
 * Backend + engine falsos para los tests de la tarjeta de pregunta (C7) —
 * espejo del `FakeSpeechBackend` de feature-voice (vive en su test sourceSet y
 * Gradle no lo publica entre módulos). El test emite los callbacks a mano
 * ([FakeSpeechEngine.emit*]), como haría `RecognitionListener` en el móvil.
 */
class FakeSpeechBackend(
    var recognitionAvailable: Boolean = true,
) : SpeechBackend {
    val engines = mutableListOf<FakeSpeechEngine>()

    override fun isRecognitionAvailable(): Boolean = recognitionAvailable

    override fun supportsOnDeviceRecognition(): Boolean = false

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

    override fun start(config: SpeechConfig) {
        started = true
        lastConfig = config
    }

    override fun stop() {
        stopped = true
    }

    override fun cancel() = Unit

    override fun destroy() = Unit

    // Simulan los callbacks del servicio de reconocimiento (RecognitionListener).
    fun emitReady() = listener.onReady()

    fun emitPartial(text: String) = listener.onPartial(text)

    fun emitResult(text: String) = listener.onResult(text)

    fun emitError(code: Int) = listener.onError(code)
}
