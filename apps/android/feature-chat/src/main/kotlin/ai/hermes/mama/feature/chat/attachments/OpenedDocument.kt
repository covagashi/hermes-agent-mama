package ai.hermes.mama.feature.chat.attachments

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.InputStream

/**
 * Documento localizado en el móvil, listo para que [FileAttacher] lo suba con
 * `file.attach` (ROADMAP §2.3, tarea E2).
 *
 * Lo produce un [DocumentReader] a partir del `Uri` que devuelve el selector de
 * archivos (`ActivityResultContracts.OpenDocument`, C5) o una descarga del
 * WebView (G1/G2). El contenido NO se lee al construirlo: [openStream] abre el
 * stream de forma perezosa para que el attacher pueda rechazar por tamaño antes
 * de cargar nada en memoria.
 *
 * Todos los campos pueden venir vacíos: muchos `ContentProvider` no informan
 * nombre, tipo ni tamaño. [FileAttacher] define el comportamiento en cada caso
 * (mime por extensión → `application/octet-stream`, nombre "documento").
 */
class OpenedDocument(
    /** Nombre visible (`OpenableColumns.DISPLAY_NAME`); null si el proveedor no lo da. */
    val name: String?,
    /** MIME declarado por el proveedor; null si no lo informa. */
    val mimeType: String?,
    /** Tamaño declarado (`OpenableColumns.SIZE`); [SIZE_UNKNOWN] si no lo informa. */
    val sizeBytes: Long,
    /** Apertura perezosa del contenido; null si el proveedor no da stream. */
    val openStream: () -> InputStream?,
) {
    companion object {
        /** El proveedor no informó el tamaño: se comprueba al leer, con cota. */
        const val SIZE_UNKNOWN: Long = -1L
    }
}

/**
 * Resuelve un [Uri] de documento a un [OpenedDocument] — la pieza de Android de
 * `data/attachments`. En producción es [ContentResolverDocumentReader]; en
 * tests se falsea (los tests JVM de E2 no necesitan un provider real).
 */
fun interface DocumentReader {
    /**
     * Devuelve el [OpenedDocument] de [uri], o null si el proveedor no
     * responde ni a los metadatos (documento inaccesible → `Unreadable`).
     */
    fun read(uri: Uri): OpenedDocument?
}

/**
 * [DocumentReader] real sobre el [ContentResolver] del sistema.
 *
 * - `DISPLAY_NAME` + `SIZE` salen de UNA sola query `OpenableColumns`.
 * - El MIME es `ContentResolver.getType` (el proveedor sabe lo que sirve).
 * - `openInputStream` queda perezoso en [OpenedDocument.openStream]: el stream
 *   no se abre hasta que [FileAttacher] lo pide.
 *
 * Un proveedor que falla ya en `getType` no va a servir bytes: `read` devuelve
 * null y el attacher responde `Unreadable`.
 */
class ContentResolverDocumentReader(
    context: Context,
) : DocumentReader {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    override fun read(uri: Uri): OpenedDocument? {
        val mimeType = runCatching { resolver.getType(uri) }.getOrElse { return null }
        var name: String? = null
        var size = OpenedDocument.SIZE_UNKNOWN
        runCatching {
            resolver
                .query(uri, METADATA_COLUMNS, null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        name = cursor.stringOrNull(OpenableColumns.DISPLAY_NAME)
                        size = cursor.longOrNull(OpenableColumns.SIZE) ?: size
                    }
                }
        }
        return OpenedDocument(
            name = name,
            mimeType = mimeType,
            sizeBytes = size,
            openStream = { runCatching { resolver.openInputStream(uri) }.getOrNull() },
        )
    }

    private companion object {
        val METADATA_COLUMNS =
            arrayOf(
                OpenableColumns.DISPLAY_NAME,
                OpenableColumns.SIZE,
            )
    }
}

private fun Cursor.stringOrNull(column: String): String? {
    val index = getColumnIndex(column)
    return if (index >= 0 && !isNull(index)) getString(index) else null
}

private fun Cursor.longOrNull(column: String): Long? {
    val index = getColumnIndex(column)
    return if (index >= 0 && !isNull(index)) getLong(index) else null
}
