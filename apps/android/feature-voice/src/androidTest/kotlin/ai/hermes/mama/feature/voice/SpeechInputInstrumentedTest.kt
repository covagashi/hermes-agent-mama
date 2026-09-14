package ai.hermes.mama.feature.voice

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests instrumentados de D1 en dispositivo/emulador real.
 *
 * Criterio de aceptación del roadmap: **permiso denegado → mensaje humano y el botón
 * queda en "Escribir"**. Como la UI del composer llega en C5, aquí se comprueba el
 * contrato que la UI va a pintar: estado Error + texto amable + `fallsBackToTyping`.
 */
@RunWith(AndroidJUnit4::class)
class SpeechInputInstrumentedTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Backend mínimo: en estos tests el engine nunca llega a crearse. */
    private object NoSpeechBackend : SpeechBackend {
        override fun isRecognitionAvailable(): Boolean = false

        override fun supportsOnDeviceRecognition(): Boolean = false

        override fun createEngine(listener: SpeechEngineListener): SpeechEngine =
            throw AssertionError("no debe crearse engine en este test")
    }

    @Test
    fun permisoDenegado_mensajeHumano_yModoEscribir() {
        val input = SpeechInput(backend = NoSpeechBackend, audioPermission = { false })

        input.startListening()

        val state = input.state.value as? SpeechState.Error
        assertNotNull("permiso denegado debe emitir Error", state)
        state ?: return
        assertEquals(SpeechErrorKind.PermissionDenied, state.kind)

        // Mensaje humano en pantalla (nada de jerga) y modo "Escribir" disponible.
        assertEquals(
            context.getString(R.string.voice_error_permission_denied),
            state.kind.humanMessage(context),
        )
        assertTrue(state.kind.fallsBackToTyping)
        assertEquals("Escribir", context.getString(R.string.voice_mode_write))
    }

    @Test
    fun sinServicioDeReconocimiento_ofreceFallbackYEscribir() {
        val input =
            SpeechInput(
                backend = NoSpeechBackend, // isRecognitionAvailable = false
                audioPermission = { true },
            )

        input.startListening()

        val state = input.state.value as? SpeechState.Error
        assertNotNull("sin servicio debe emitir Error", state)
        state ?: return
        assertEquals(SpeechErrorKind.NotAvailable, state.kind)
        assertTrue(state.kind.fallsBackToTyping)
        assertEquals(
            context.getString(R.string.voice_error_not_available),
            state.kind.humanMessage(context),
        )
    }

    @Test
    fun intentDeFallback_esEspanolYExtraeTexto() {
        val intent = RecognizerIntentFallback.buildIntent(context)

        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, intent.action)
        assertEquals("es-ES", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertEquals(
            context.getString(R.string.voice_fallback_prompt),
            intent.getStringExtra(RecognizerIntent.EXTRA_PROMPT),
        )

        val data =
            Intent().putStringArrayListExtra(
                RecognizerIntent.EXTRA_RESULTS,
                arrayListOf("hola mamá"),
            )
        assertEquals("hola mamá", RecognizerIntentFallback.extractText(data))
    }

    @Test
    fun explicacionDePermiso_esHumanaYMencionaMantenerPulsado() {
        // La explicación previa a la petición del sistema existe y es comprensible.
        val rationale = context.getString(R.string.voice_permission_rationale)
        assertTrue(rationale.contains("micrófono"))
        assertTrue(rationale.contains("mantienes pulsado"))
    }
}
