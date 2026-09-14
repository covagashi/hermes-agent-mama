package ai.hermes.mama.feature.voice

import android.content.Context

/**
 * Construye el [SpeechOutput] real cableando las piezas Android (ROADMAP §5, D2):
 * `TextToSpeech` + `AudioManager` + las palabras habladas desde recursos.
 *
 * [readAloud] llega inyectado: la implementación DataStore la aporta
 * `feature-settings`/la capa de DI cuando exista (C2/C8).
 */
object SpeechOutputFactory {
    fun create(
        context: Context,
        readAloud: ReadAloudSetting,
    ): SpeechOutput {
        val appContext = context.applicationContext
        return SpeechOutput(
            engine = AndroidTtsEngine(appContext),
            readAloud = readAloud,
            deviceSilence = AudioManagerDeviceSilence(appContext),
            cleaner =
                MarkdownToSpeechText(
                    codeWord = appContext.getString(R.string.tts_code_word),
                    linkWord = appContext.getString(R.string.tts_link_word),
                ),
        )
    }
}
