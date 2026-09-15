package ai.hermes.mama.feature.browser

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Persiste una descarga en `Downloads/Hermes/` (ROADMAP §5/G1) y devuelve su
 * identidad final en MediaStore.
 */
interface DownloadSink {
    /**
     * Crea la fila `IS_PENDING`, vuelca el cuerpo llamando a [write] con el
     * `OutputStream` abierto y marca el fichero como disponible. Si algo falla
     * a mitad, la fila se borra — nada de restos a medias en Descargas.
     *
     * @return el `DISPLAY_NAME` final (MediaStore puede añadir «(1)» si el
     *   nombre ya existía), la `content://` uri y los bytes escritos.
     */
    suspend fun save(
        fileName: String,
        mimeType: String,
        write: (OutputStream) -> Long,
    ): SavedDownload
}

/** Identidad del fichero ya materializado en MediaStore. */
data class SavedDownload(
    val displayName: String,
    val contentUri: String,
    val sizeBytes: Long,
)

/**
 * `MediaStore.Downloads` + `RELATIVE_PATH=Download/Hermes` (§5/G1). Sin
 * `WRITE_EXTERNAL_STORAGE`: en API 29+ MediaStore no lo pide para la carpeta
 * pública de descargas (minSdk es 29 — no hay rama legacy).
 */
class MediaStoreDownloadSink(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DownloadSink {
    private val resolver = context.applicationContext.contentResolver

    override suspend fun save(
        fileName: String,
        mimeType: String,
        write: (OutputStream) -> Long,
    ): SavedDownload =
        withContext(ioDispatcher) {
            val values =
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + File.separator + SUBDIRECTORY,
                    )
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            val uri =
                resolver.insert(COLLECTION, values)
                    ?: throw IOException("MediaStore no aceptó la descarga")
            try {
                val out =
                    resolver.openOutputStream(uri)
                        ?: throw IOException("MediaStore no abrió el fichero")
                val written = out.use(write)
                val published =
                    resolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                        null,
                        null,
                    )
                if (published <= 0) {
                    throw IOException("MediaStore no publicó la descarga")
                }
                // La fila ya es válida: si el query del nombre final falla no
                // debe caer al catch (borraría un fichero bien publicado).
                val finalName = runCatching { displayNameOf(uri) }.getOrNull() ?: fileName
                SavedDownload(finalName, uri.toString(), written)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // Cualquier fallo a medias (red cortada, SecurityException…):
                // la fila pending se borra — nada de restos en Descargas.
                resolver.delete(uri, null, null)
                throw e
            }
        }

    /** Nombre definitivo en MediaStore (deduplicado «(1)» incluido). */
    private fun displayNameOf(uri: android.net.Uri): String? =
        resolver
            .query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }

    private companion object {
        /** Subcarpeta pública: `Download/Hermes` bajo Descargas (§5/G1). */
        const val SUBDIRECTORY = "Hermes"
        val COLLECTION = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }
}
