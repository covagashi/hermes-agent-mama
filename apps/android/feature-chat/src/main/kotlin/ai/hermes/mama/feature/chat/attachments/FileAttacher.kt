package ai.hermes.mama.feature.chat.attachments

import ai.hermes.mama.contract.FileAttachParams
import ai.hermes.mama.contract.FileAttachResult
import ai.hermes.mama.gateway.JsonRpcException
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Base64

/**
 * Lo que devuelve [FileAttacher.attach] — siempre un resultado, nunca una
 * excepción: la pantalla muestra el error humano ([FileAttachError.humanMessage])
 * o añade la chip del adjunto a la cola del composer (C5).
 */
sealed interface FileAttachOutcome {
    /**
     * El archivo se subió con `file.attach` (§2.3).
     *
     * [refText] es la referencia `@file:…` que devuelve el servidor y que el
     * composer añade AL FINAL del texto del `prompt.submit` (C5: "primero
     * file.attach, después prompt.submit con ref_text").
     */
    data class Attached(
        /** Nombre del archivo (el del móvil, no el saneado del servidor): pinta la chip. */
        val name: String,
        /** `ref_text` del `FileAttachResult` — `@file:attachments/…`; va al texto del prompt. */
        val refText: String,
        /** Tamaño real leído, para la burbuja de documento (G3). */
        val sizeBytes: Long,
    ) : FileAttachOutcome {
        /** Etiqueta corta de la cola de adjuntos del composer: "📎 factura.pdf". */
        val chipLabel: String
            get() = "📎 $name"
    }

    /** No se pudo adjuntar; [kind] decide el mensaje humano y si tiene sentido reintentar. */
    data class Error(
        val kind: FileAttachError,
    ) : FileAttachOutcome
}

/** Fallos de [FileAttacher] en lenguaje de dominio — texto humano en `strings_attachments.xml`. */
enum class FileAttachError {
    /** El proveedor no deja abrir el archivo (permiso perdido, documento borrado…). */
    Unreadable,

    /** El archivo no tiene contenido (0 bytes). */
    Empty,

    /** Supera [FileAttacher.maxBytes] (8 MB): nunca se envía nada al servidor. */
    TooLarge,

    /** `file.attach` respondió `attached: false`. */
    NotAttached,

    /** La llamada `file.attach` falló (red, error JSON-RPC, respuesta fuera de contrato). */
    SendFailed,
}

/**
 * E2 · `FileAttacher` (`data/attachments`): lleva un documento del móvil a la
 * sesión de Hermes con `file.attach` (ROADMAP §2.3, §5/M4).
 *
 * Flujo por archivo: [DocumentReader] resuelve el [Uri] a [OpenedDocument]
 * (nombre, mime, tamaño, stream perezoso) → rechazo rápido si el tamaño
 * declarado supera [maxBytes] → lectura acotada (un proveedor puede mentir:
 * se leen como máximo `maxBytes + 1`) → `data_url` `data:<mime>;base64,…` →
 * `file.attach` → [FileAttachOutcome.Attached] con el `refText` que el
 * composer añade al prompt.
 *
 * - El servidor acepta cualquier media type en el `data_url` (`prompt_attachments.py`
 *   lo decodifica sin filtrar por mime), así que un mime desconocido NO es
 *   error: va como `application/octet-stream`.
 * - Nada de `!!`, `GlobalScope` ni `runBlocking` (AGENTS.md): la lectura del
 *   stream, que es IO bloqueante, corre en [ioDispatcher] inyectado.
 * - §8: los logs sólo llevan el tipo de fallo y el código RPC — nunca el
 *   `data_url`, el nombre del archivo ni `e.message` (puede incrustar input).
 */
class FileAttacher(
    /** En producción: `GatewayClient::attachFile` (B4). Función para falsear el RPC en tests JVM. */
    private val attachFile: suspend (FileAttachParams) -> FileAttachResult,
    /** Resuelve Uris a documentos; en producción [ContentResolverDocumentReader]. */
    private val documents: DocumentReader,
    /** Tamaño máximo del contenido en bytes (8 MB, §5/E2). */
    private val maxBytes: Long = MAX_FILE_BYTES,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Punto de entrada desde el composer/descargas: resuelve [uri] y lo adjunta
     * a [sessionId]. Un proveedor que no responde devuelve `Error(Unreadable)`.
     */
    suspend fun attach(
        sessionId: String,
        uri: Uri,
    ): FileAttachOutcome {
        val document =
            withContext(ioDispatcher) {
                runCatching { documents.read(uri) }.getOrNull()
            } ?: return FileAttachOutcome.Error(FileAttachError.Unreadable)
        return attach(sessionId, document)
    }

    /** Adjunta un [OpenedDocument] ya resuelto — el núcleo, testeable sin Android. */
    suspend fun attach(
        sessionId: String,
        document: OpenedDocument,
    ): FileAttachOutcome =
        when {
            // Rechazo rápido por tamaño declarado: ni se abre el stream.
            document.sizeBytes > maxBytes -> fail(FileAttachError.TooLarge)
            else -> {
                val bytes =
                    withContext(ioDispatcher) {
                        runCatching { readBounded(document.openStream, maxBytes) }.getOrNull()
                    }
                when {
                    bytes == null -> fail(FileAttachError.Unreadable)
                    bytes.size > maxBytes -> fail(FileAttachError.TooLarge)
                    bytes.isEmpty() -> fail(FileAttachError.Empty)
                    else -> send(sessionId, document, bytes)
                }
            }
        }

    @Suppress("TooGenericExceptionCaught") // cualquier fallo del RPC = SendFailed humano
    private suspend fun send(
        sessionId: String,
        document: OpenedDocument,
        bytes: ByteArray,
    ): FileAttachOutcome {
        val name = document.name?.trim()?.takeIf { it.isNotEmpty() } ?: FALLBACK_NAME
        val mimeType = resolveMimeType(document.mimeType, document.name)
        val params =
            FileAttachParams(
                sessionId = sessionId,
                dataUrl = dataUrl(mimeType, bytes),
                name = name,
            )
        val result =
            try {
                attachFile(params)
            } catch (e: CancellationException) {
                throw e
            } catch (e: JsonRpcException) {
                Timber.w("file.attach: error RPC code=%s", e.code)
                null
            } catch (e: Exception) {
                Timber.w("file.attach: fallo (%s)", e::class.simpleName)
                null
            }
        return when {
            result == null -> FileAttachOutcome.Error(FileAttachError.SendFailed)
            !result.attached -> fail(FileAttachError.NotAttached)
            else ->
                FileAttachOutcome.Attached(
                    name = name,
                    refText = result.refText,
                    sizeBytes = bytes.size.toLong(),
                )
        }
    }

    private fun fail(kind: FileAttachError): FileAttachOutcome.Error {
        Timber.w("file.attach: %s", kind)
        return FileAttachOutcome.Error(kind)
    }

    /**
     * MIME para el `data_url`: el del proveedor si existe (normalizado a
     * `tipo/subtipo` minúsculas, sin parámetros), si no el de la extensión del
     * nombre, y si no `application/octet-stream` — comportamiento definido del
     * contrato: `file.attach` acepta cualquier media type (§2.3 "PDF/otros").
     */
    private fun resolveMimeType(
        providerMime: String?,
        name: String?,
    ): String {
        providerMime
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.matches(MIME_PATTERN) }
            ?.let { return it }
        val extension = name?.substringAfterLast('.', "")?.trim()?.lowercase()
        return EXTENSION_MIMES[extension] ?: FALLBACK_MIME
    }

    companion object {
        /** Tamaño máximo del archivo (contenido, antes de base64): 8 MiB (§5/E2). */
        const val MAX_FILE_BYTES: Long = 8L * 1024 * 1024

        /** MIME cuando ni el proveedor ni la extensión lo dicen (§2.3 acepta cualquiera). */
        const val FALLBACK_MIME: String = "application/octet-stream"

        /** Nombre cuando el proveedor no da `DISPLAY_NAME`: va al `name` de `file.attach`. */
        const val FALLBACK_NAME: String = "documento"

        /** `data:<mime>;base64,<bytes>` — la forma que `file.attach` espera en `data_url`. */
        fun dataUrl(
            mimeType: String,
            bytes: ByteArray,
        ): String = "data:$mimeType;base64,${Base64.getEncoder().encodeToString(bytes)}"

        /**
         * Lee [openStream] con cota: como mucho `maxBytes + 1` bytes, así un
         * proveedor que miente en `SIZE` no carga el archivo entero. Devuelve
         * los bytes leídos; lanza si el stream no abre o la lectura falla.
         */
        private fun readBounded(
            openStream: () -> InputStream?,
            maxBytes: Long,
        ): ByteArray {
            val stream = openStream() ?: throw IOException("stream no disponible")
            stream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(BUFFER_BYTES)
                var total = 0L
                val limit = maxBytes + 1
                while (total < limit) {
                    val want = minOf(buffer.size.toLong(), limit - total).toInt()
                    val read = input.read(buffer, 0, want)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    total += read
                }
                return out.toByteArray()
            }
        }

        private const val BUFFER_BYTES = 8 * 1024

        private val MIME_PATTERN = Regex("""[a-z0-9!#$&^_+-]+/[a-z0-9!#$&^_+.-]+""")

        /** Extensión → MIME de reserva cuando el proveedor no informa el tipo. */
        private val EXTENSION_MIMES =
            mapOf(
                "pdf" to "application/pdf",
                "doc" to "application/msword",
                "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "odt" to "application/vnd.oasis.opendocument.text",
                "rtf" to "application/rtf",
                "txt" to "text/plain",
                "md" to "text/markdown",
                "csv" to "text/csv",
                "tsv" to "text/tab-separated-values",
                "xls" to "application/vnd.ms-excel",
                "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "ods" to "application/vnd.oasis.opendocument.spreadsheet",
                "ppt" to "application/vnd.ms-powerpoint",
                "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "epub" to "application/epub+zip",
                "json" to "application/json",
                "xml" to "application/xml",
                "html" to "text/html",
                "htm" to "text/html",
                "zip" to "application/zip",
            )
    }
}
