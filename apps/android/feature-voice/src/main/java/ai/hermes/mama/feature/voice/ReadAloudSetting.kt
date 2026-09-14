package ai.hermes.mama.feature.voice

import kotlinx.coroutines.flow.StateFlow

/**
 * Ajuste "Leer las respuestas en voz alta" (pantalla Conexión, ROADMAP §3 pantalla 1).
 *
 * Se modela como interfaz inyectable para que D2 sea testeable en JVM antes de que
 * exista la UI: la implementación real llegará con `feature-settings` (DataStore) y
 * los tests usan un fake con `MutableStateFlow`.
 */
interface ReadAloudSetting {
    /** `true` → cada respuesta completada de Hermes se lee en voz alta automáticamente. */
    val readAloudEnabled: StateFlow<Boolean>
}

/**
 * Estado de silencio del teléfono (modo silencio / volumen multimedia).
 *
 * Interfaz funcional para poder falsear el `AudioManager` en tests JVM; la
 * implementación Android es [AudioManagerDeviceSilence].
 */
fun interface DeviceSilenceChecker {
    /**
     * `true` cuando el móvil no debe emitir voz: modo silencio/vibración o el stream
     * de música silenciado. Un móvil en silencio nunca habla solo.
     */
    fun isDeviceSilent(): Boolean
}
