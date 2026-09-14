package ai.hermes.mama.feature.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Alternativas pedidas al recognizer; nos quedamos con la mejor (la primera). */
private const val MAX_RESULTS = 3

/**
 * [SpeechBackend] real sobre el `SpeechRecognizer` del sistema.
 *
 * Crear y usar desde el hilo principal (requisito del API). `context` puede ser el
 * `applicationContext`: el recognizer sólo lo usa para resolver el servicio.
 */
class SpeechRecognizerBackend(
    private val context: Context,
) : SpeechBackend {
    override fun isRecognitionAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    // isOnDeviceRecognitionAvailable existe desde API 31; debajo no podemos saber si
    // hay modelo offline, así que no pedimos EXTRA_PREFER_OFFLINE (iría a red igualmente).
    override fun supportsOnDeviceRecognition(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    override fun createEngine(listener: SpeechEngineListener): SpeechEngine = SpeechRecognizerEngine(context, listener)
}

/** Engine real: un `SpeechRecognizer` por sesión, adaptado al SPI sin tipos Android. */
private class SpeechRecognizerEngine(
    context: Context,
    listener: SpeechEngineListener,
) : SpeechEngine {
    private val recognizer: SpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)

    init {
        recognizer.setRecognitionListener(ListenerBridge(listener))
    }

    override fun start(config: SpeechConfig) {
        val intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, config.languageTag)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, config.partialResults)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RESULTS)
        if (config.preferOffline) {
            intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        recognizer.startListening(intent)
    }

    override fun stop() {
        recognizer.stopListening()
    }

    override fun cancel() {
        recognizer.cancel()
    }

    override fun destroy() {
        recognizer.destroy()
    }

    private class ListenerBridge(
        private val listener: SpeechEngineListener,
    ) : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            listener.onReady()
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            listener.onError(error)
        }

        override fun onResults(results: Bundle?) {
            listener.onResult(results.firstResult().orEmpty())
        }

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults.firstResult()?.takeIf(String::isNotBlank)?.let(listener::onPartial)
        }

        override fun onEvent(
            eventType: Int,
            params: Bundle?,
        ) = Unit

        private fun Bundle?.firstResult(): String? =
            this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
    }
}
