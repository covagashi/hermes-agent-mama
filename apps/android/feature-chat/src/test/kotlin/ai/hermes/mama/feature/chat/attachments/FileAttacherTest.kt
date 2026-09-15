package ai.hermes.mama.feature.chat.attachments

import ai.hermes.mama.contract.FileAttachParams
import ai.hermes.mama.contract.FileAttachResult
import ai.hermes.mama.gateway.JsonRpcException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests JVM de [FileAttacher] (E2) — lógica pura sobre [OpenedDocument], sin
 * Android ni emulador: `data_url`, cota de 8 MB, resolución de mime, nombres y
 * mapeo de fallos. El camino `Uri`/strings se cubre en [FileAttacherUriTest]
 * (Robolectric). Los fixtures son sintéticos (§7.2).
 */
class FileAttacherTest {
    private val rpc = RecordingAttachRpc()
    private val attacher =
        FileAttacher(
            attachFile = rpc::invoke,
            documents = DocumentReader { null },
        )

    private fun doc(
        name: String?,
        mime: String?,
        bytes: ByteArray,
        declaredSize: Long = bytes.size.toLong(),
    ) = OpenedDocument(
        name = name,
        mimeType = mime,
        sizeBytes = declaredSize,
        openStream = { bytes.inputStream() },
    )

    @Test
    fun `PDF sintetico produce data_url valido y devuelve el refText del servidor`() =
        runTest {
            val pdf = syntheticPdf()

            val outcome = attacher.attach("sess_7f2a1c", doc("factura.pdf", "application/pdf", pdf))

            val attached = assertIs<FileAttachOutcome.Attached>(outcome)
            assertEquals("factura.pdf", attached.name)
            assertEquals("@file:attachments/factura.pdf", attached.refText)
            assertEquals(pdf.size.toLong(), attached.sizeBytes)
            assertEquals("📎 factura.pdf", attached.chipLabel)

            val params = rpc.calls.single()
            assertEquals("sess_7f2a1c", params.sessionId)
            assertEquals("factura.pdf", params.name)
            val dataUrl = assertNotNull(params.dataUrl)
            val prefix = "data:application/pdf;base64,"
            assertTrue(dataUrl.startsWith(prefix), "data_url con mime correcto: $prefix…")
            assertContentEquals(pdf, Base64.getDecoder().decode(dataUrl.removePrefix(prefix)))
        }

    @Test
    fun `9 MB declarados produce TooLarge y ni lee ni llama al gateway`() =
        runTest {
            val outcome =
                attacher.attach(
                    "sess_1",
                    OpenedDocument(
                        name = "gordo.pdf",
                        mimeType = "application/pdf",
                        sizeBytes = 9L * 1024 * 1024,
                        openStream = { throw AssertionError("no debe abrirse el stream") },
                    ),
                )

            assertEquals(FileAttachOutcome.Error(FileAttachError.TooLarge), outcome)
            assertTrue(rpc.calls.isEmpty(), "file.attach no se llama para un TooLarge")
        }

    @Test
    fun `proveedor que miente el tamano se acota igualmente al leer`() =
        runTest {
            // SIZE dice 0 (o no informa) pero el contenido real pesa 9 MB.
            val big = ByteArray(9 * 1024 * 1024) { it.toByte() }
            val outcome =
                attacher.attach("sess_1", doc("gordo.pdf", "application/pdf", big, declaredSize = -1))

            assertEquals(FileAttachOutcome.Error(FileAttachError.TooLarge), outcome)
            assertTrue(rpc.calls.isEmpty())
        }

    @Test
    fun `exactamente 8 MB se admite - el limite es inclusivo`() =
        runTest {
            val outcome =
                attacher.attach(
                    "sess_1",
                    doc("justo.pdf", "application/pdf", ByteArray(FileAttacher.MAX_FILE_BYTES.toInt())),
                )

            assertIs<FileAttachOutcome.Attached>(outcome)
        }

    @Test
    fun `mime desconocido va como octet-stream - el servidor acepta cualquier mime`() =
        runTest {
            // §2.3 "PDF/otros": file.attach no filtra por mime; la app define
            // application/octet-stream cuando ni el provider ni la extension lo dicen.
            val outcome = attacher.attach("sess_1", doc("cosa.rara", null, syntheticPdf()))

            assertIs<FileAttachOutcome.Attached>(outcome)
            val dataUrl = assertNotNull(rpc.calls.single().dataUrl)
            assertTrue(dataUrl.startsWith("data:application/octet-stream;base64,"))
        }

    @Test
    fun `mime del provider se normaliza a tipo base minusculas sin parametros`() =
        runTest {
            val outcome = attacher.attach("sess_1", doc("notas.txt", "Text/Plain; charset=UTF-8", bytesOf("hola")))

            assertIs<FileAttachOutcome.Attached>(outcome)
            assertTrue(assertNotNull(rpc.calls.single().dataUrl).startsWith("data:text/plain;base64,"))
        }

    @Test
    fun `sin mime del provider se usa la extension del nombre`() =
        runTest {
            attacher.attach("sess_1", doc("notas.txt", null, bytesOf("hola")))
            assertTrue(assertNotNull(rpc.calls.single().dataUrl).startsWith("data:text/plain;base64,"))

            attacher.attach("sess_1", doc("hoja.XLSX", null, bytesOf("pk")))
            assertTrue(
                assertNotNull(rpc.calls[1].dataUrl).startsWith(
                    "data:application/vnd.openxmlformats-officedocument.spreadsheetml.sheet;base64,",
                ),
            )
        }

    @Test
    fun `nombre con espacios y unicode viaja tal cual en el param name`() =
        runTest {
            val nombre = "mi factura de la luz — año 2025.pdf"

            val outcome = attacher.attach("sess_1", doc(nombre, "application/pdf", syntheticPdf()))

            val attached = assertIs<FileAttachOutcome.Attached>(outcome)
            assertEquals(nombre, rpc.calls.single().name)
            assertEquals(nombre, attached.name)
            assertEquals("📎 $nombre", attached.chipLabel)
        }

    @Test
    fun `sin nombre del provider se usa un nombre de reserva`() =
        runTest {
            val outcome = attacher.attach("sess_1", doc(null, "application/pdf", syntheticPdf()))

            assertIs<FileAttachOutcome.Attached>(outcome)
            assertEquals("documento", rpc.calls.single().name)
            assertEquals("documento", outcome.name)
        }

    @Test
    fun `archivo vacio produce Empty sin llamar al gateway`() =
        runTest {
            val outcome = attacher.attach("sess_1", doc("vacio.txt", "text/plain", ByteArray(0)))

            assertEquals(FileAttachOutcome.Error(FileAttachError.Empty), outcome)
            assertTrue(rpc.calls.isEmpty())
        }

    @Test
    fun `stream que no abre o falla produce Unreadable`() =
        runTest {
            val noStream =
                attacher.attach(
                    "sess_1",
                    OpenedDocument(name = "x.pdf", mimeType = "application/pdf", sizeBytes = 10, openStream = { null }),
                )
            assertEquals(FileAttachOutcome.Error(FileAttachError.Unreadable), noStream)

            val roto =
                attacher.attach(
                    "sess_1",
                    OpenedDocument(
                        name = "x.pdf",
                        mimeType = "application/pdf",
                        sizeBytes = 10,
                        openStream = { throw IOException("provider roto") },
                    ),
                )
            assertEquals(FileAttachOutcome.Error(FileAttachError.Unreadable), roto)
            assertTrue(rpc.calls.isEmpty())
        }

    @Test
    fun `error JSON-RPC del gateway produce SendFailed`() =
        runTest {
            rpc.error = JsonRpcException(code = 5028, message = "attach failed")

            val outcome = attacher.attach("sess_1", doc("factura.pdf", "application/pdf", syntheticPdf()))

            assertEquals(FileAttachOutcome.Error(FileAttachError.SendFailed), outcome)
        }

    @Test
    fun `attached false del servidor produce NotAttached`() =
        runTest {
            rpc.result = rpc.result.copy(attached = false)

            val outcome = attacher.attach("sess_1", doc("factura.pdf", "application/pdf", syntheticPdf()))

            assertEquals(FileAttachOutcome.Error(FileAttachError.NotAttached), outcome)
        }

    @Test
    fun `cancelacion durante el RPC se propaga - nunca se traga`() =
        runTest {
            rpc.error = CancellationException()

            assertFailsWith<CancellationException> {
                attacher.attach("sess_1", doc("factura.pdf", "application/pdf", syntheticPdf()))
            }
        }

    private companion object {
        fun bytesOf(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

        /** PDF de ~1 KB 100 % sintético (§7.2): estructura mínima, contenido de relleno. */
        fun syntheticPdf(): ByteArray =
            buildString {
                append("%PDF-1.4\n")
                append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
                append("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
                append("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] >>\nendobj\n")
                append("% fixture sintético — relleno hasta ~1 KB\n")
                while (length < 1000) append("% relleno relleno relleno\n")
                append("%%EOF\n")
            }.toByteArray(Charsets.US_ASCII)
    }
}

/** Doble del RPC `file.attach`: graba params y devuelve un result (o error) fijado. */
private class RecordingAttachRpc {
    val calls = mutableListOf<FileAttachParams>()

    var result: FileAttachResult =
        FileAttachResult(
            attached = true,
            name = "factura.pdf",
            path = "/home/usuario/.hermes/attachments/factura.pdf",
            refPath = "attachments/factura.pdf",
            refText = "@file:attachments/factura.pdf",
            uploaded = true,
        )

    var error: Throwable? = null

    suspend fun invoke(params: FileAttachParams): FileAttachResult {
        calls += params
        error?.let { throw it }
        return result
    }
}
