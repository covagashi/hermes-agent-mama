package ai.hermes.mama.feature.voice

/**
 * Motor de texto a voz (ROADMAP §5, D2).
 *
 * Abstracción mínima sobre `android.speech.tts.TextToSpeech` para que la política de
 * [SpeechOutput] se pueda testear en JVM con un `FakeTts`, sin framework Android.
 *
 * La implementación Android ([AndroidTtsEngine]) vive en este mismo paquete; el motor se
 * crea una vez por proceso y hay que liberarlo con [shutdown] al cerrar la sesión de voz.
 */
interface TtsEngine {
    /**
     * Encola una frase para leerla en voz alta. Las utterances se leen en orden de
     * llegada (equivalente a `TextToSpeech.QUEUE_ADD`): cada burbuja de Hermes es una
     * utterance, así la cola respeta el orden de las burbujas.
     *
     * [utteranceId] identifica la frase (p. ej. el id de la burbuja); sirve para
     * depurar y para callbacks de progreso futuros.
     */
    fun speak(
        text: String,
        utteranceId: String,
    )

    /**
     * Detiene la frase en curso y vacía la cola de utterances pendientes.
     * Es lo que llaman el botón **Parar** (C4) y el micrófono al empezar a dictar (C5).
     */
    fun stop()

    /** Libera el motor TTS. Después de [shutdown] la instancia no se puede reutilizar. */
    fun shutdown()
}
