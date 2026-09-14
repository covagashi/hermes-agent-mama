package ai.hermes.mama.feature.voice

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent

/**
 * Fallback para móviles SIN servicio de reconocimiento
 * (`SpeechRecognizer.isRecognitionAvailable` = false → [SpeechErrorKind.NotAvailable]):
 * la actividad de dictado del sistema vía `RecognizerIntent.ACTION_RECOGNIZE_SPEECH`.
 *
 * El composer (C5) la lanza con `ActivityResultContracts.StartActivityForResult()`;
 * si el resultado es `RESULT_OK`, el texto se saca con [extractText].
 */
object RecognizerIntentFallback {
    /** Intent para lanzar la actividad de dictado del sistema (es-ES). */
    fun buildIntent(
        context: Context,
        languageTag: String = SpeechInput.DEFAULT_LANGUAGE_TAG,
    ): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, context.getString(R.string.voice_fallback_prompt))
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

    /** ¿Hay alguna app instalada que pueda responder a este intent? */
    fun isAvailable(
        context: Context,
        languageTag: String = SpeechInput.DEFAULT_LANGUAGE_TAG,
    ): Boolean = buildIntent(context, languageTag).resolveActivity(context.packageManager) != null

    /** Mejor transcripción del `Intent` de resultado; null si no hubo texto. */
    fun extractText(data: Intent?): String? =
        data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.takeIf(String::isNotBlank)
}
