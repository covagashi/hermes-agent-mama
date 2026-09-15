package ai.hermes.mama.feature.browser

import java.util.Locale

/**
 * Textos que una descarga terminada manda a la sesión (ROADMAP §5/G1).
 *
 * - [userLine]: el aviso visible para la usuaria, en español
 *   («📄 Se ha guardado *factura.pdf* en Descargas»).
 * - [modelLine]: la nota para el modelo, en inglés y con el formato exacto del
 *   roadmap («Downloaded file saved on the user's phone: factura.pdf
 *   (application/pdf, 123 KB)»). Viaja en el `browser.controller.result` del
 *   comando en curso si lo hay, y siempre dentro del `prompt.submit`
 *   (`display_kind:"system"`) — ver [DownloadReporter].
 * - [sessionMessage]: una sola `prompt.submit` que lleva los dos — así el
 *   modelo recibe su línea exacta aunque ningún comando esté en vuelo.
 *
 * JVM puro para probar los formatos en unit tests.
 */
internal object DownloadNotice {
    /** Aviso visible en la sesión, español — texto literal de §5/G1. */
    fun userLine(fileName: String): String = "📄 Se ha guardado *$fileName* en Descargas"

    /** Nota para el modelo, inglés — formato literal de §5/G1. */
    fun modelLine(
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
    ): String =
        "Downloaded file saved on the user's phone: " +
            "$fileName ($mimeType, ${sizeLabel(sizeBytes)})"

    /** El `prompt.submit` lleva el aviso y, bajo una línea en blanco, la nota. */
    fun sessionMessage(
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
    ): String = "${userLine(fileName)}\n\n${modelLine(fileName, mimeType, sizeBytes)}"

    /** «128 KB» / «2,3 MB» — subtítulo de la hoja y tamaño en [modelLine]. */
    fun sizeLabel(sizeBytes: Long): String =
        if (sizeBytes >= BYTES_PER_MB) {
            val mb = sizeBytes.toDouble() / BYTES_PER_MB
            "${String.format(Locale.forLanguageTag("es"), "%.1f", mb)} MB"
        } else {
            "${maxOf(1, sizeBytes / BYTES_PER_KB)} KB"
        }

    private const val BYTES_PER_KB = 1_024L
    private const val BYTES_PER_MB = 1_048_576L
}
