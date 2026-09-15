package ai.hermes.mama.feature.browser

/**
 * Un documento ya guardado en `Downloads/Hermes/` (ROADMAP §5/G1).
 *
 * [contentUri] es el `content://` de MediaStore (API 29+): lo comparten los
 * intents «Abrir»/«Compartir» con `FLAG_GRANT_READ_URI_PERMISSION` — no hace
 * falta FileProvider mientras el fichero viva en MediaStore.
 */
data class DownloadedDoc(
    /** `DISPLAY_NAME` final en MediaStore (puede llevar «(1)» si ya existía). */
    val fileName: String,
    /** MIME efectivo (el de la respuesta HTTP si el servidor lo mandó). */
    val mimeType: String,
    /** Bytes realmente escritos. */
    val sizeBytes: Long,
    /** `content://media/…/downloads/NNN` del fichero. */
    val contentUri: String,
)
