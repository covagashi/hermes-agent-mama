package ai.hermes.mama.feature.chat.attachments

import ai.hermes.mama.contract.FileAttachParams
import ai.hermes.mama.contract.FileAttachResult
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowContentResolver
import java.util.Base64
import kotlin.test.assertIs

/**
 * Tests JVM (Robolectric) del camino `Uri` de [FileAttacher] (E2): el
 * [ContentResolverDocumentReader] lee nombre/mime/tamaño/contenido de un
 * [ContentProvider] falso registrado en el resolver, y los mensajes humanos de
 * [FileAttachError] se resuelven desde `strings_attachments.xml`.
 */
@RunWith(RobolectricTestRunner::class)
class FileAttacherUriTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var provider: FakeDocumentProvider

    @Before
    fun registerProvider() {
        provider = Robolectric.setupContentProvider(FakeDocumentProvider::class.java)
        ShadowContentResolver.registerProviderInternal(AUTHORITY, provider)
    }

    @Test
    fun `attach uri real lee metadatos y contenido del provider`() =
        runTest {
            val bytes = "%PDF-1.4 fixture sintético\n%%EOF\n".toByteArray()
            provider.bytes = bytes
            provider.displayName = "factura uno.pdf"
            provider.size = bytes.size.toLong()
            provider.mimeType = "application/pdf"
            val calls = mutableListOf<FileAttachParams>()
            val attacher =
                FileAttacher(
                    attachFile = { params ->
                        calls += params
                        okResult("factura uno.pdf")
                    },
                    documents = ContentResolverDocumentReader(context),
                )
            val uri = Uri.parse("content://$AUTHORITY/factura%20uno.pdf")
            // Robolectric sirve openInputStream sólo por streams registrados (el
            // provider cubre getType/query; openFile→PFD no pasa por el shadow).
            shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { bytes.inputStream() }

            val outcome = attacher.attach("sess_9", uri)

            val attached = assertIs<FileAttachOutcome.Attached>(outcome)
            assertEquals("factura uno.pdf", attached.name)
            assertEquals(bytes.size.toLong(), attached.sizeBytes)
            val dataUrl = calls.single().dataUrl.orEmpty()
            assertTrue(dataUrl.startsWith("data:application/pdf;base64,"))
            val decoded =
                Base64.getDecoder().decode(dataUrl.substringAfter(";base64,"))
            assertTrue(decoded.contentEquals(bytes))
        }

    @Test
    fun `attach uri sin provider accesible produce Unreadable`() =
        runTest {
            val attacher =
                FileAttacher(
                    attachFile = { throw AssertionError("no debe llamarse") },
                    documents = ContentResolverDocumentReader(context),
                )
            val uri = Uri.parse("content://autoridad.inexistente/doc.pdf")

            val outcome = attacher.attach("sess_9", uri)

            assertEquals(FileAttachOutcome.Error(FileAttachError.Unreadable), outcome)
        }

    @Test
    fun `attach uri delega en el DocumentReader con ese uri`() =
        runTest {
            val seen = mutableListOf<Uri>()
            val attacher =
                FileAttacher(
                    attachFile = { params -> okResult(params.name.orEmpty()) },
                    documents =
                        DocumentReader { uri ->
                            seen += uri
                            OpenedDocument(
                                name = "doc.pdf",
                                mimeType = "application/pdf",
                                sizeBytes = 3,
                                openStream = { "abc".byteInputStream() },
                            )
                        },
                )
            val uri = Uri.parse("content://$AUTHORITY/doc.pdf")

            val outcome = attacher.attach("sess_9", uri)

            assertIs<FileAttachOutcome.Attached>(outcome)
            assertEquals(listOf(uri), seen)
        }

    @Test
    fun `attach uri con reader que devuelve null produce Unreadable`() =
        runTest {
            val attacher =
                FileAttacher(
                    attachFile = { throw AssertionError("no debe llamarse") },
                    documents = DocumentReader { null },
                )

            val outcome = attacher.attach("sess_9", Uri.parse("content://x/y.pdf"))

            assertEquals(FileAttachOutcome.Error(FileAttachError.Unreadable), outcome)
        }

    @Test
    fun `los mensajes humanos son frases en espanol sin tecnicismos`() {
        val esperados =
            mapOf(
                FileAttachError.Unreadable to
                    "No puedo abrir este archivo. Prueba a elegirlo otra vez.",
                FileAttachError.Empty to
                    "Este archivo está vacío, no tiene nada dentro.",
                FileAttachError.TooLarge to
                    "El archivo es demasiado grande. Como máximo puede pesar 8 MB.",
                FileAttachError.NotAttached to
                    "Hermes no ha aceptado el archivo. Inténtalo otra vez.",
                FileAttachError.SendFailed to
                    "No se pudo enviar el archivo. Revisa la conexión e inténtalo otra vez.",
            )

        FileAttachError.entries.forEach { kind ->
            assertEquals(esperados.getValue(kind), kind.humanMessage(context))
        }
    }

    private fun okResult(name: String): FileAttachResult =
        FileAttachResult(
            attached = true,
            name = name,
            path = "/home/usuario/.hermes/attachments/$name",
            refPath = "attachments/$name",
            refText = "@file:attachments/$name",
            uploaded = true,
        )

    private companion object {
        const val AUTHORITY = "ai.hermes.mama.test.docs"
    }
}

/**
 * [ContentProvider] falso para tests: `DISPLAY_NAME`/`SIZE` por query y mime
 * por `getType`. El contenido lo sirve el propio Robolectric
 * (`ShadowContentResolver.registerInputStreamSupplier`).
 */
class FakeDocumentProvider : ContentProvider() {
    var bytes: ByteArray = ByteArray(0)
    var displayName: String? = null
    var size: Long = OpenedDocument.SIZE_UNKNOWN
    var mimeType: String? = null

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns)
        cursor.addRow(
            columns
                .map { column ->
                    when (column) {
                        OpenableColumns.DISPLAY_NAME -> displayName
                        OpenableColumns.SIZE -> size
                        else -> null
                    }
                }.toTypedArray(),
        )
        return cursor
    }

    override fun getType(uri: Uri): String? = mimeType

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
