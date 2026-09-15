package ai.hermes.mama.feature.browser

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.Locale

/**
 * Resuelve el nombre con que se guarda una descarga del WebView (ROADMAP §5/G1).
 *
 * Orden de preferencia:
 * 1. `filename*` del `Content-Disposition` (RFC 5987/6266: `UTF-8''%E2%82%AC.pdf`).
 * 2. `filename` del mismo header (entrecomillado con escapes o token plano).
 * 3. Último segmento del path de la URL, percent-decodificado.
 * 4. [FALLBACK_NAME] con la extensión del MIME si se conoce.
 *
 * El resultado se sanea (separadores de ruta, controles y caracteres hostiles
 * a FAT → `_`, sin puntos iniciales, ≤ [MAX_NAME_CHARS]) y se le añade la
 * extensión conocida del MIME cuando el nombre no trae ninguna.
 *
 * JVM puro — sin `android.net.Uri` ni `URLUtil` — para probarlo en unit tests
 * sin Robolectric.
 */
internal object DownloadFileName {
    private const val FALLBACK_NAME = "descarga"
    private const val MAX_NAME_CHARS = 120

    /** Caracteres prohibidos en nombres de archivo (separadores + reservados Windows). */
    private const val ILLEGAL_CHARS = "\\/:*?\"<>|"

    /** Extensiones conocidas para el nombre de respaldo (falta total de nombre). */
    private val MIME_EXTENSIONS =
        mapOf(
            "application/pdf" to "pdf",
            "application/json" to "json",
            "application/xml" to "xml",
            "application/zip" to "zip",
            "text/plain" to "txt",
            "text/csv" to "csv",
            "text/html" to "html",
            "image/jpeg" to "jpg",
            "image/png" to "png",
            "image/gif" to "gif",
            "image/webp" to "webp",
            "audio/mpeg" to "mp3",
            "audio/ogg" to "ogg",
            "video/mp4" to "mp4",
            "application/msword" to "doc",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
            "application/vnd.ms-excel" to "xls",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
            "application/vnd.ms-powerpoint" to "ppt",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx",
        )

    /** Nombre final para guardar en `Downloads/Hermes/`; nunca vacío. */
    fun resolve(
        url: String,
        contentDisposition: String?,
        mimeType: String?,
    ): String {
        val fromHeader =
            contentDisposition
                ?.let(::filenameFromHeader)
                ?.let(::sanitize)
                ?.takeUnless { it.isBlank() }
        val fromUrl =
            nameFromUrl(url)
                ?.let(::sanitize)
                ?.takeUnless { it.isBlank() }
        val base = fromHeader ?: fromUrl ?: FALLBACK_NAME
        return ensureExtension(base, mimeType)
    }

    // ----------------------------------------------------- Content-Disposition --

    /**
     * Extrae `filename`/`filename*` del header. `filename*` (RFC 5987) gana al
     * `filename` plano, como manda RFC 6266 §4.3. `null` si no hay ninguno.
     */
    internal fun filenameFromHeader(header: String): String? {
        var plain: String? = null
        var extended: String? = null
        for (param in splitHeaderParams(header).drop(1)) {
            val eq = param.indexOf('=')
            if (eq < 0) {
                continue
            }
            val name = param.substring(0, eq).trim().lowercase(Locale.ROOT)
            val value = param.substring(eq + 1).trim()
            when (name) {
                "filename" -> plain = unquote(value)
                "filename*" -> extended = decodeExtendedValue(unquote(value))
            }
        }
        return extended?.takeUnless { it.isBlank() } ?: plain?.takeUnless { it.isBlank() }
    }

    /** Parte el header por `;` sin romper los `;` que vivan dentro de comillas. */
    private fun splitHeaderParams(header: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder(header.length)
        var inQuotes = false
        var escaped = false
        for (c in header) {
            when {
                escaped -> {
                    current.append(c)
                    escaped = false
                }
                c == '\\' && inQuotes -> {
                    current.append(c)
                    escaped = true
                }
                c == '"' -> {
                    current.append(c)
                    inQuotes = !inQuotes
                }
                c == ';' && !inQuotes -> {
                    parts += current.toString()
                    current.setLength(0)
                }
                else -> current.append(c)
            }
        }
        parts += current.toString()
        return parts
    }

    /** `"factura \"a\".pdf"` → `factura "a".pdf`; sin comillas → tal cual. */
    private fun unquote(value: String): String {
        if (value.length < 2 || value.first() != '"' || value.last() != '"') {
            return value
        }
        val inner = value.substring(1, value.length - 1)
        val out = StringBuilder(inner.length)
        var escaped = false
        for (c in inner) {
            if (escaped) {
                out.append(c)
                escaped = false
            } else if (c == '\\') {
                escaped = true
            } else {
                out.append(c)
            }
        }
        if (escaped) {
            out.append('\\')
        }
        return out.toString()
    }

    /** `UTF-8''%E2%82%AC.pdf` → `€.pdf` (charset opcional, por defecto UTF-8). */
    private fun decodeExtendedValue(value: String): String? {
        val firstQuote = value.indexOf('\'')
        val secondQuote =
            if (firstQuote < 0) {
                -1
            } else {
                value.indexOf('\'', firstQuote + 1)
            }
        if (secondQuote < 0) {
            return null
        }
        val charsetName = value.substring(0, firstQuote)
        val charset =
            runCatching {
                if (charsetName.isBlank()) Charsets.UTF_8 else Charset.forName(charsetName)
            }.getOrDefault(Charsets.UTF_8)
        return percentDecode(value.substring(secondQuote + 1), charset)
    }

    // ------------------------------------------------------------------- URL --

    /**
     * Último segmento del path, percent-decodificado; `null` si no hay nombre.
     * Se descarta antes `esquema://autoridad` para que `https://host` (sin
     * path) no devuelva el dominio como «nombre».
     */
    private fun nameFromUrl(url: String): String? {
        val noFragment = url.substringBefore('#').substringBefore('?')
        val path =
            if ("://" in noFragment) {
                noFragment.substringAfter("://").substringAfter('/', "")
            } else {
                noFragment
            }
        // Sin '/' en el path el segmento ES el nombre (URL relativa o de un
        // solo nivel): por eso el «missing» es el propio path, no vacío.
        val last = path.substringAfterLast('/')
        return last.takeUnless { it.isBlank() }?.let { percentDecode(it, Charsets.UTF_8) }
    }

    /** `%XX` → byte; el resto (cabeceras/URLs son ASCII) viaja como su byte bajo. */
    private fun percentDecode(
        value: String,
        charset: Charset,
    ): String {
        val bytes = ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            val hex =
                if (c == '%' && i + 2 <= value.lastIndex) {
                    value.substring(i + 1, i + 3).toIntOrNull(HEX_RADIX)
                } else {
                    null
                }
            if (hex != null) {
                bytes.write(hex)
                i += PERCENT_SEQUENCE_LEN
            } else {
                // Un char no-ASCII literal (p. ej. «é» en un path que el
                // WebView ya pasó decodificado) se escribe como sus bytes
                // UTF-8 — `c.code and 0xFF` lo truncaría a U+FFFD al decodificar.
                bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                i += 1
            }
        }
        return String(bytes.toByteArray(), charset)
    }

    // ----------------------------------------------------------------- saneo --

    /** Controles y caracteres ilegales fuera; sin puntos/espacios al inicio. */
    private fun sanitize(name: String): String =
        name
            .map { c -> if (c in ILLEGAL_CHARS || c.isISOControl()) '_' else c }
            .joinToString("")
            .trim()
            .trimStart('.', ' ')
            .take(MAX_NAME_CHARS)

    /** Añade la extensión del MIME cuando el nombre no trae una reconocible. */
    private fun ensureExtension(
        name: String,
        mimeType: String?,
    ): String {
        val lastDot = name.lastIndexOf('.')
        val hasExtension = lastDot > 0 && name.length - lastDot - 1 in 1..MAX_EXTENSION_CHARS
        if (hasExtension || mimeType == null) {
            return name
        }
        val extension = MIME_EXTENSIONS[mimeType.substringBefore(';').trim().lowercase(Locale.ROOT)]
        return extension?.let { "$name.$it" } ?: name
    }

    private const val HEX_RADIX = 16
    private const val PERCENT_SEQUENCE_LEN = 3
    private const val MAX_EXTENSION_CHARS = 5
}
