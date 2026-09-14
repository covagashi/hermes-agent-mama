package ai.hermes.mama.feature.voice

import android.speech.SpeechRecognizer
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests JVM de [SpeechInput] (D1) con [FakeSpeechBackend]/[FakeSpeechEngine]:
 * sin emulador ni Robolectric — el SPI no toca tipos Android y los códigos
 * `SpeechRecognizer.ERROR_*` son constantes incrustadas en compilación.
 */
class SpeechInputTest {
    private val backend = FakeSpeechBackend()
    private var permissionGranted = true
    private val input = SpeechInput(backend, audioPermission = { permissionGranted })

    private fun startListening(): FakeSpeechEngine {
        input.startListening()
        return backend.engines.last()
    }

    @Test
    fun `empieza en Idle`() {
        assertEquals(SpeechState.Idle, input.state.value)
    }

    @Test
    fun `mantener para hablar emite parciales en vivo y Done al soltar`() =
        runTest {
            input.state.test {
                assertEquals(SpeechState.Idle, awaitItem())

                val engine = startListening()
                assertEquals(SpeechState.Listening(""), awaitItem())
                assertTrue(engine.started)

                engine.emitReady()
                engine.emitPartial("hola")
                assertEquals(SpeechState.Listening("hola"), awaitItem())
                engine.emitPartial("hola mamá")
                assertEquals(SpeechState.Listening("hola mamá"), awaitItem())

                input.stopListening()
                assertTrue(engine.stopped)
                engine.emitResult("hola mamá")

                assertEquals(SpeechState.Done("hola mamá"), awaitItem())
                assertTrue(engine.destroyed)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `config es-ES con parciales y preferOffline sólo si hay modelo on-device`() {
        backend.onDeviceRecognition = false
        startListening()
        assertEquals(
            SpeechConfig(languageTag = "es-ES", partialResults = true, preferOffline = false),
            backend.engines.last().lastConfig,
        )

        backend.onDeviceRecognition = true
        startListening()
        assertTrue(
            backend.engines
                .last()
                .lastConfig
                ?.preferOffline == true,
        )
    }

    @Test
    fun `sin permiso RECORD_AUDIO emite Error PermissionDenied y no crea engine`() {
        permissionGranted = false

        input.startListening()

        val state = assertIs<SpeechState.Error>(input.state.value)
        assertEquals(SpeechErrorKind.PermissionDenied, state.kind)
        assertTrue(state.kind.fallsBackToTyping)
        assertTrue(backend.engines.isEmpty())
    }

    @Test
    fun `sin servicio de reconocimiento emite Error NotAvailable para fallback`() {
        backend.recognitionAvailable = false
        assertFalse(input.isRecognitionAvailable())

        input.startListening()

        val state = assertIs<SpeechState.Error>(input.state.value)
        assertEquals(SpeechErrorKind.NotAvailable, state.kind)
        assertTrue(state.kind.fallsBackToTyping)
        assertTrue(backend.engines.isEmpty())
    }

    @Test
    fun `stopListening sin sesión es no-op`() {
        input.stopListening()
        input.cancel()
        assertEquals(SpeechState.Idle, input.state.value)
    }

    @Test
    fun `cancel vuelve a Idle y destruye el engine`() {
        val engine = startListening()
        input.cancel()

        assertEquals(SpeechState.Idle, input.state.value)
        assertTrue(engine.cancelled)
        assertTrue(engine.destroyed)
    }

    @Test
    fun `resultado vacío o en blanco es NoMatch, no Done`() {
        val engine = startListening()
        engine.emitResult("   ")

        val state = assertIs<SpeechState.Error>(input.state.value)
        assertEquals(SpeechErrorKind.NoMatch, state.kind)
    }

    @Test
    fun `el texto final se recorta`() {
        val engine = startListening()
        engine.emitResult("  hola mamá  ")

        assertEquals(SpeechState.Done("hola mamá"), input.state.value)
    }

    @Test
    fun `cada código ERROR del recognizer mapea a su kind`() {
        val casos =
            mapOf(
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS to SpeechErrorKind.PermissionDenied,
                SpeechRecognizer.ERROR_NO_MATCH to SpeechErrorKind.NoMatch,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT to SpeechErrorKind.NoMatch,
                SpeechRecognizer.ERROR_AUDIO to SpeechErrorKind.Audio,
                SpeechRecognizer.ERROR_NETWORK to SpeechErrorKind.Network,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT to SpeechErrorKind.Network,
                SpeechRecognizer.ERROR_SERVER_DISCONNECTED to SpeechErrorKind.Network,
                SpeechRecognizer.ERROR_SERVER to SpeechErrorKind.Server,
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY to SpeechErrorKind.Busy,
                SpeechRecognizer.ERROR_TOO_MANY_REQUESTS to SpeechErrorKind.Busy,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE to SpeechErrorKind.LanguageUnavailable,
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED to SpeechErrorKind.LanguageUnavailable,
                SpeechRecognizer.ERROR_CLIENT to SpeechErrorKind.Unknown,
                -1 to SpeechErrorKind.Unknown,
            )

        for ((code, expected) in casos) {
            val engine = startListening()
            engine.emitError(code)
            val state = assertIs<SpeechState.Error>(input.state.value, "código $code")
            assertEquals(expected, state.kind, "código $code")
            assertTrue(engine.destroyed, "código $code")
        }
    }

    @Test
    fun `tras Done se puede volver a dictar`() {
        startListening().emitResult("hola")
        assertEquals(SpeechState.Done("hola"), input.state.value)

        val segundo = startListening()
        assertEquals(SpeechState.Listening(""), input.state.value)
        assertEquals(2, backend.engines.size)
        assertTrue(backend.engines[0].destroyed)
        assertFalse(segundo.destroyed)
    }

    @Test
    fun `callbacks de una sesión vieja se ignoran`() {
        val viejo = startListening()
        val nuevo = startListening() // startListening reemplaza la sesión

        viejo.emitPartial("fantasma")
        viejo.emitResult("fantasma")
        viejo.emitError(SpeechRecognizer.ERROR_SERVER)

        assertEquals(SpeechState.Listening(""), input.state.value)
        assertTrue(nuevo.started)
        assertTrue(viejo.destroyed)
    }

    @Test
    fun `callbacks tras cancel se ignoran`() {
        val engine = startListening()
        input.cancel()

        engine.emitResult("tarde")
        engine.emitError(SpeechRecognizer.ERROR_CLIENT)

        assertEquals(SpeechState.Idle, input.state.value)
    }

    @Test
    fun `si createEngine lanza emite Error Unknown y no queda Listening colgado`() {
        // SpeechRecognizer.createSpeechRecognizer devuelve tipo plataforma (puede ser null → NPE)
        // y lanza RuntimeException/SecurityException con el servicio roto en algunos OEMs.
        val backendRoto =
            object : SpeechBackend {
                override fun isRecognitionAvailable() = true

                override fun supportsOnDeviceRecognition() = false

                override fun createEngine(listener: SpeechEngineListener): SpeechEngine {
                    error("servicio caído")
                }
            }
        val inputRoto = SpeechInput(backendRoto, audioPermission = { true })

        inputRoto.startListening()

        val state = assertIs<SpeechState.Error>(inputRoto.state.value)
        assertEquals(SpeechErrorKind.Unknown, state.kind)
    }

    @Test
    fun `stopListening con engine roto no crashea`() {
        val engine = startListening()
        engine.failOnStop = true

        input.stopListening()

        // El fallo de stop() se traga: la sesión sigue y aún puede llegar resultado.
        assertEquals(SpeechState.Listening(""), input.state.value)
        engine.emitResult("igualmente llegó")
        assertEquals(SpeechState.Done("igualmente llegó"), input.state.value)
    }

    @Test
    fun `si el engine falla al arrancar emite Error Unknown`() {
        val backendRoto =
            object : SpeechBackend {
                override fun isRecognitionAvailable() = true

                override fun supportsOnDeviceRecognition() = false

                override fun createEngine(listener: SpeechEngineListener): SpeechEngine =
                    object : SpeechEngine {
                        override fun start(config: SpeechConfig) {
                            error("servicio caído")
                        }

                        override fun stop() = Unit

                        override fun cancel() = Unit

                        override fun destroy() = Unit
                    }
            }
        val inputRoto = SpeechInput(backendRoto, audioPermission = { true })

        inputRoto.startListening()

        val state = assertIs<SpeechState.Error>(inputRoto.state.value)
        assertEquals(SpeechErrorKind.Unknown, state.kind)
    }

    @Test
    fun `hasAudioPermission refleja el checker`() {
        assertTrue(input.hasAudioPermission())
        permissionGranted = false
        assertFalse(input.hasAudioPermission())
    }
}
