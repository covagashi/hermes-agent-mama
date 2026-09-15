package ai.hermes.mama.feature.chat.attachments

import ai.hermes.mama.contract.RpcMethods
import ai.hermes.mama.gateway.GatewayClient
import ai.hermes.mama.gateway.JsonRpcChannel
import android.content.Context
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.Base64
import kotlin.math.max
import kotlin.time.Duration.Companion.minutes

/**
 * Tests JVM (Robolectric) de [ImageAttacher] — E1, ROADMAP §5.
 *
 * Todo el camino es real: `content://…` lo sirve [FakeImageProvider] vía
 * `ContentResolver`, el envío sale por un [GatewayClient] + [JsonRpcChannel]
 * auténticos sobre [RecordingTransport], y los fixtures son imágenes
 * sintéticas generadas en el test (nada de fotos reales).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ImageAttacherTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        FakeImageProvider.register()
        FakeImageProvider.payloads.clear()
    }

    private fun TestScope.newAttacher(transport: RecordingTransport): ImageAttacher {
        val client =
            GatewayClient(
                channel =
                    JsonRpcChannel(
                        transport = transport,
                        scope = backgroundScope,
                        heartbeatInterval = 10.minutes,
                    ),
                scope = backgroundScope,
            )
        return ImageAttacher(context.contentResolver, client, UnconfinedTestDispatcher())
    }

    private fun register(
        name: String,
        bytes: ByteArray,
    ) = FakeImageProvider.uri(name).also { FakeImageProvider.payloads[name] = bytes }

    /** Params del frame `image.attach_bytes` enviado (único por test). */
    private fun sentParams(transport: RecordingTransport): JsonObject {
        val frames =
            transport
                .sentFrames()
                .filter { it["method"]?.jsonPrimitive?.content == RpcMethods.IMAGE_ATTACH_BYTES }
        assertEquals("debe haber exactamente un frame image.attach_bytes", 1, frames.size)
        return frames.single().getValue("params").jsonObject
    }

    private fun sentBytes(transport: RecordingTransport): ByteArray {
        val base64 = sentParams(transport).getValue("content_base64").jsonPrimitive.content
        return Base64.getDecoder().decode(base64)
    }

    private fun dimensionsOf(bytes: ByteArray): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return options.outWidth to options.outHeight
    }

    private fun decode(bytes: ByteArray) = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    // --- casos felices ---

    @Test
    fun `jpeg de 4000px sale con lado mayor 1600 y por debajo de 1MB`() =
        runTest {
            val transport = RecordingTransport()
            val uri = register("foto-grande.jpg", ImageFixtures.noiseJpeg(4000, 2500))

            val outcome = newAttacher(transport).attach("session-1", uri, displayName = "foto-grande.jpg")

            assertTrue(outcome is ImageAttachOutcome.Attached)
            val sent = sentBytes(transport)
            assertTrue(sent.hasMagicPrefix(ImageFixtures.JPEG_MAGIC))
            assertTrue("esperaba <= 1MB, salieron ${sent.size}", sent.size <= 1024 * 1024)
            val (w, h) = dimensionsOf(sent)
            assertEquals(1600, max(w, h))
        }

    @Test
    fun `jpeg con EXIF rotate 90 sale transpuesto`() =
        runTest {
            val transport = RecordingTransport()
            val bytes =
                ImageFixtures.noiseJpegWithExif(
                    200,
                    100,
                    ExifInterface.ORIENTATION_ROTATE_90,
                    context.cacheDir,
                )
            val uri = register("rotada.jpg", bytes)

            val outcome = newAttacher(transport).attach("session-1", uri)

            assertTrue(outcome is ImageAttachOutcome.Attached)
            val (w, h) = dimensionsOf(sentBytes(transport))
            assertEquals("la imagen 200x100 rotada 90 debe quedar 100x200", 100 to 200, w to h)
        }

    @Test
    fun `jpeg con EXIF rotate 270 sale transpuesto`() =
        runTest {
            val transport = RecordingTransport()
            val bytes =
                ImageFixtures.noiseJpegWithExif(
                    160,
                    320,
                    ExifInterface.ORIENTATION_ROTATE_270,
                    context.cacheDir,
                )
            val uri = register("rotada-270.jpg", bytes)

            val outcome = newAttacher(transport).attach("session-1", uri)

            assertTrue(outcome is ImageAttachOutcome.Attached)
            val (w, h) = dimensionsOf(sentBytes(transport))
            assertEquals("la imagen 160x320 rotada 270 debe quedar 320x160", 320 to 160, w to h)
        }

    @Test
    fun `png con transparencia se envia como png, no jpeg`() =
        runTest {
            val transport = RecordingTransport()
            val uri = register("recorte.png", ImageFixtures.alphaPng(800, 600))

            val outcome = newAttacher(transport).attach("session-1", uri)

            assertTrue(outcome is ImageAttachOutcome.Attached)
            val sent = sentBytes(transport)
            assertTrue(sent.hasMagicPrefix(ImageFixtures.PNG_MAGIC))
            assertEquals(800 to 600, dimensionsOf(sent))
            // La transparencia sobrevive al pipeline.
            val decoded = decode(sent)
            assertNotNull(decoded)
            assertEquals(0x00, decoded.getPixel(10, 300) ushr 24)
            // Y el nombre lleva la extensión real del formato enviado.
            assertEquals("recorte.png", sentParams(transport).getValue("filename").jsonPrimitive.content)
        }

    @Test
    fun `png sin transparencia se convierte a jpeg`() =
        runTest {
            val transport = RecordingTransport()
            val uri = register("captura.png", ImageFixtures.opaquePng(640, 480))

            val outcome = newAttacher(transport).attach("session-1", uri)

            assertTrue(outcome is ImageAttachOutcome.Attached)
            val sent = sentBytes(transport)
            assertTrue(sent.hasMagicPrefix(ImageFixtures.JPEG_MAGIC))
            assertEquals("captura.jpg", sentParams(transport).getValue("filename").jsonPrimitive.content)
        }

    @Test
    fun `imagen que no cabe en 1MB se recomprime hasta caber`() =
        runTest {
            // Ruido puro a 4000px: ni a 1600px entra con la primera calidad.
            val transport = RecordingTransport()
            val uri = register("ruido.jpg", ImageFixtures.noiseJpeg(4000, 2500, quality = 95))

            val outcome = newAttacher(transport).attach("session-1", uri)

            assertTrue(outcome is ImageAttachOutcome.Attached)
            val sent = sentBytes(transport)
            assertTrue(sent.size <= 1024 * 1024)
            assertTrue(sent.hasMagicPrefix(ImageFixtures.JPEG_MAGIC))
            assertNotNull("la imagen recomprimida debe seguir decodificando", decode(sent))
        }

    @Test
    fun `el frame en el cable lleva session_id filename y base64 (contrato §2_3)`() =
        runTest {
            val transport = RecordingTransport()
            val uri = register("vacaciones-9", ImageFixtures.noiseJpeg(320, 200))

            val outcome = newAttacher(transport).attach("session-xyz", uri)

            assertTrue(outcome is ImageAttachOutcome.Attached)
            val params = sentParams(transport)
            assertEquals("session-xyz", params.getValue("session_id").jsonPrimitive.content)
            // Sin displayName se usa el lastPathSegment con la extensión real.
            assertEquals("vacaciones-9.jpg", params.getValue("filename").jsonPrimitive.content)
            assertTrue(sentBytes(transport).isNotEmpty())
            // El result eco del backend llega al caller.
            val attached = outcome as ImageAttachOutcome.Attached
            assertEquals(true, attached.response.attached)
            assertEquals("vacaciones-9.jpg", attached.response.name)
        }

    @Test
    fun `displayName se sanea y la extension es la del formato enviado`() =
        runTest {
            val transport = RecordingTransport()
            val uri = register("id-1234", ImageFixtures.opaquePng(120, 90))

            val outcome = newAttacher(transport).attach("session-1", uri, displayName = "mi foto (1).png")

            assertTrue(outcome is ImageAttachOutcome.Attached)
            assertEquals("mi_foto__1.jpg", sentParams(transport).getValue("filename").jsonPrimitive.content)
        }

    // --- errores (texto humano via ImageAttachError) ---

    @Test
    fun `uri que el provider no puede abrir da Unreadable`() =
        runTest {
            val transport = RecordingTransport()
            val uri = FakeImageProvider.uri("no-existe.jpg") // sin payload

            val outcome = newAttacher(transport).attach("session-1", uri)

            assertEquals(ImageAttachOutcome.Failed(ImageAttachError.Unreadable), outcome)
            assertTrue(transport.sent.isEmpty())
        }

    @Test
    fun `provider que devuelve stream nulo da Unreadable`() =
        runTest {
            val transport = RecordingTransport()
            val uri = FakeImageProvider.uri(FakeImageProvider.NAME_NULL_STREAM)

            val outcome = newAttacher(transport).attach("session-1", uri)

            assertEquals(ImageAttachOutcome.Failed(ImageAttachError.Unreadable), outcome)
        }

    @Test
    fun `bytes que no son imagen dan NotAnImage`() =
        runTest {
            val transport = RecordingTransport()
            val uri = register("nota.txt", ImageFixtures.garbageBytes())

            val outcome = newAttacher(transport).attach("session-1", uri)

            assertEquals(ImageAttachOutcome.Failed(ImageAttachError.NotAnImage), outcome)
            assertTrue(transport.sent.isEmpty())
        }

    @Test
    fun `socket roto en el envio da SendFailed`() =
        runTest {
            val transport = RecordingTransport()
            transport.failOnSend = IOException("socket roto")
            val uri = register("foto.jpg", ImageFixtures.noiseJpeg(100, 80))

            val outcome = newAttacher(transport).attach("session-1", uri)

            assertEquals(ImageAttachOutcome.Failed(ImageAttachError.SendFailed), outcome)
        }

    @Test
    fun `error json-rpc del servidor da SendFailed`() =
        runTest {
            val transport = RecordingTransport()
            transport.rpcErrorOnAttach = true
            val uri = register("foto.jpg", ImageFixtures.noiseJpeg(100, 80))

            val outcome = newAttacher(transport).attach("session-1", uri)

            assertEquals(ImageAttachOutcome.Failed(ImageAttachError.SendFailed), outcome)
        }

    @Test
    fun `cada ImageAttachError tiene texto humano en strings`() {
        for (kind in ImageAttachError.entries) {
            val message = kind.humanMessage(context)
            assertTrue("$kind sin texto", message.isNotBlank())
        }
    }
}
