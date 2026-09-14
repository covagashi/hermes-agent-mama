package ai.hermes.mama.feature.voice

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import timber.log.Timber
import java.util.Locale

/**
 * [TtsEngine] real sobre `android.speech.tts.TextToSpeech` (ROADMAP §5, D2).
 *
 * - Idioma `es-ES` con fallback a `es` genérico; si el dispositivo no tiene datos de
 *   voz en español se registra el problema y se usa la voz por defecto del sistema
 *   (mejor una voz en otro acento que silencio).
 * - La inicialización del motor es asíncrona: las frases que lleguen antes de que
 *   esté listo se guardan en [pending] y se sueltan en orden al completarse.
 * - `AudioAttributes.USAGE_ASSISTANT`: la voz va por el stream multimedia con el uso
 *   de asistente, coherente con un TTS de app (no es una notificación).
 *
 * Todos los métodos son seguros desde cualquier hilo: `TextToSpeech` serializa
 * internamente y [pending] se protege con [lock].
 */
class AndroidTtsEngine(
    context: Context,
) : TtsEngine,
    TextToSpeech.OnInitListener {
    private val lock = Any()
    private val pending = ArrayDeque<Pair<String, String>>()

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var failed = false

    override fun onInit(status: Int) {
        synchronized(lock) {
            if (status != TextToSpeech.SUCCESS) {
                failed = true
                tts = null
                pending.clear()
                Timber.w("TextToSpeech no pudo inicializarse (status=$status); voz desactivada")
                return
            }
            val engine = tts ?: return
            engine.setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            selectSpanishVoice(engine)
            ready = true
            while (pending.isNotEmpty()) {
                val (text, id) = pending.removeFirst()
                engine.enqueue(text, id)
            }
        }
    }

    override fun speak(
        text: String,
        utteranceId: String,
    ) {
        synchronized(lock) {
            val engine = tts
            when {
                ready && engine != null -> engine.enqueue(text, utteranceId)
                failed -> Timber.d("TTS no disponible; frase descartada")
                else -> pending.addLast(text to utteranceId)
            }
        }
    }

    override fun stop() {
        synchronized(lock) {
            pending.clear()
            tts?.stop()
        }
    }

    override fun shutdown() {
        synchronized(lock) {
            pending.clear()
            tts?.shutdown()
            tts = null
            ready = false
            failed = true
        }
    }

    /** Cola una utterance con `QUEUE_ADD`: cada burbuja espera a la anterior. */
    private fun TextToSpeech.enqueue(
        text: String,
        utteranceId: String,
    ) {
        speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
    }

    private fun selectSpanishVoice(engine: TextToSpeech) {
        val locale =
            SPANISH_LOCALES.firstOrNull {
                engine.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE
            }
        if (locale == null) {
            Timber.w("Sin datos de voz en español; se usa la voz por defecto del sistema")
            return
        }
        val result = engine.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Timber.w("La voz $locale no está disponible; se usa la voz por defecto")
        }
    }

    private companion object {
        private val SPANISH_LOCALES =
            listOf(
                Locale.forLanguageTag("es-ES"),
                Locale.forLanguageTag("es"),
            )
    }
}
