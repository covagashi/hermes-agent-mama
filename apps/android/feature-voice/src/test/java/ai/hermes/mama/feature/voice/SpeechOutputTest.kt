package ai.hermes.mama.feature.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [TtsEngine] falso para JVM: registra lo que se habría leído y los stops. */
private class FakeTts : TtsEngine {
    val spoken = mutableListOf<Pair<String, String>>() // utteranceId → texto

    var stopCalls = 0
        private set

    var shutdownCalls = 0
        private set

    override fun speak(
        text: String,
        utteranceId: String,
    ) {
        spoken += utteranceId to text
    }

    override fun stop() {
        stopCalls++
    }

    override fun shutdown() {
        shutdownCalls++
    }
}

/** Ajuste "Leer respuestas en voz alta" falseable (la impl real será DataStore, C2). */
private class FakeReadAloud(
    enabled: Boolean,
) : ReadAloudSetting {
    override val readAloudEnabled: StateFlow<Boolean> = MutableStateFlow(enabled)
}

/**
 * Tests JVM de [SpeechOutput] (ROADMAP §5, D2): la política de cuándo la app lee en
 * voz alta — ajuste activo, móvil en silencio, cola por burbuja y Parar.
 */
class SpeechOutputTest {
    private val engine = FakeTts()
    private var silent = false
    private val cleaner = MarkdownToSpeechText(codeWord = "código", linkWord = "enlace")

    private fun output(readAloudEnabled: Boolean) =
        SpeechOutput(
            engine = engine,
            readAloud = FakeReadAloud(readAloudEnabled),
            deviceSilence = DeviceSilenceChecker { silent },
            cleaner = cleaner,
        )

    @BeforeEach
    fun resetSilence() {
        silent = false
    }

    @Test
    fun `message complete reads text aloud when the setting is on`() {
        output(readAloudEnabled = true).onMessageComplete("Hola mamá")
        assertEquals(listOf("Hola mamá."), engine.spoken.map { it.second })
    }

    @Test
    fun `message complete stays quiet when the setting is off`() {
        output(readAloudEnabled = false).onMessageComplete("Hola mamá")
        assertTrue(engine.spoken.isEmpty())
    }

    @Test
    fun `message complete stays quiet when the phone is silent`() {
        silent = true
        output(readAloudEnabled = true).onMessageComplete("Hola mamá")
        assertTrue(engine.spoken.isEmpty())
    }

    @Test
    fun `each completed bubble enqueues one utterance in order`() {
        val output = output(readAloudEnabled = true)
        output.onMessageComplete("Primera burbuja")
        output.onMessageComplete("Segunda burbuja")
        assertEquals(
            listOf("Primera burbuja.", "Segunda burbuja."),
            engine.spoken.map { it.second },
        )
        assertEquals(
            2,
            engine.spoken
                .map { it.first }
                .distinct()
                .size,
            "una utterance por burbuja",
        )
    }

    @Test
    fun `markdown is cleaned before speaking`() {
        output(
            readAloudEnabled = true,
        ).onMessageComplete("**Importante:** revisa [esto](https://hermes.example.invalid)")
        assertEquals(listOf("Importante: revisa esto."), engine.spoken.map { it.second })
    }

    @Test
    fun `blank or missing text produces no speech`() {
        val output = output(readAloudEnabled = true)
        output.onMessageComplete(null)
        output.onMessageComplete("")
        output.onMessageComplete("   ")
        assertTrue(engine.spoken.isEmpty())
    }

    @Test
    fun `manual speak button works even with the setting off`() {
        // El 🔊 de cada burbuja es una petición explícita de la usuaria (y H2 "¿Qué
        // pone aquí?" lee aunque el ajuste esté apagado).
        output(readAloudEnabled = false).speakAloud("Lee esto")
        assertEquals(listOf("Lee esto."), engine.spoken.map { it.second })
    }

    @Test
    fun `manual speak still respects the silent mode`() {
        silent = true
        output(readAloudEnabled = false).speakAloud("Lee esto")
        assertTrue(engine.spoken.isEmpty())
    }

    @Test
    fun `stop and interrupt reach the engine`() {
        val output = output(readAloudEnabled = true)
        output.stop()
        output.interrupt()
        assertEquals(2, engine.stopCalls)
    }

    @Test
    fun `shutdown releases the engine`() {
        output(readAloudEnabled = true).shutdown()
        assertEquals(1, engine.shutdownCalls)
    }
}
