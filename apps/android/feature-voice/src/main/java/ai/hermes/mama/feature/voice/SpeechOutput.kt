package ai.hermes.mama.feature.voice

/**
 * Lectura en voz alta de las respuestas de Hermes (ROADMAP §5, D2).
 *
 * Clase Kotlin pura (sin imports de Android): toda la política vive aquí para que se
 * pueda testear en JVM con un `FakeTts`. La factoría [SpeechOutputFactory] la conecta
 * con las piezas Android (`TextToSpeech` + `AudioManager`).
 *
 * Política:
 * - **Lectura automática**: [onMessageComplete] se llama cuando llega el evento
 *   `message.complete` de una burbuja de Hermes (C4). Sólo lee si el ajuste
 *   "Leer las respuestas en voz alta" está activo **y** el móvil no está en silencio.
 * - **Lectura manual**: [speakAloud] es el botón 🔊 de cada burbuja (y el caso de H2,
 *   "¿Qué pone aquí?", que lee aunque el ajuste esté apagado). Es una petición
 *   explícita de la usuaria, así que ignora el ajuste; el modo silencio sí lo respeta:
 *   un móvil en silencio nunca habla.
 * - **Cola por burbuja**: cada burbuja es una utterance; el motor las lee en orden.
 * - **Parar / dictar**: [stop] (alias [interrupt]) vacía la cola; lo llaman el botón
 *   **Parar** de C4 y el micrófono de C5 al empezar a dictar.
 */
class SpeechOutput(
    private val engine: TtsEngine,
    private val readAloud: ReadAloudSetting,
    private val deviceSilence: DeviceSilenceChecker,
    private val cleaner: MarkdownToSpeechText,
) {
    private var utteranceCounter = 0

    /**
     * Hook para el evento `message.complete` (C4). Lee el texto de la burbuja sólo si
     * el ajuste está activo y el móvil no está en silencio. `null`/vacío → no-op (un
     * `message.complete` con `error` sin texto no produce voz).
     */
    fun onMessageComplete(text: String?) {
        if (!readAloud.readAloudEnabled.value) return
        enqueue(text, utteranceId = null)
    }

    /**
     * Lectura explícita: botón 🔊 de una burbuja, o la respuesta de "¿Qué pone aquí?"
     * (H2), que se lee aunque el ajuste esté apagado. El modo silencio se respeta
     * siempre. [utteranceId] puede ser el id de la burbuja para trazabilidad.
     */
    fun speakAloud(
        text: String?,
        utteranceId: String? = null,
    ) {
        enqueue(text, utteranceId)
    }

    /**
     * Para la lectura en curso y descarta las burbujas encoladas. Lo llaman el botón
     * **Parar** (C4, junto a `session.interrupt`) y el micrófono (C5) antes de dictar.
     */
    fun stop() {
        engine.stop()
    }

    /** Alias semántico de [stop] para el botón Parar de C4. */
    fun interrupt() = stop()

    /** Libera el motor TTS (cierre del chat o de la app). */
    fun shutdown() {
        engine.shutdown()
    }

    private fun enqueue(
        text: String?,
        utteranceId: String?,
    ) {
        if (deviceSilence.isDeviceSilent()) return
        val speech = text?.let(cleaner::toSpeechText)?.takeUnless { it.isBlank() } ?: return
        val id = utteranceId ?: "hermes-${++utteranceCounter}"
        engine.speak(speech, id)
    }
}
