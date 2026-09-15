package ai.hermes.mama.feature.chat.attachments

// Firmas de los formatos que BitmapFactory decodifica — la misma idea que
// `_sniff_image_ext`/`_IMAGE_MAGIC` en `tui_gateway/prompt_attachments.py`.
private val JPEG_SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
private val GIF_87A = "GIF87a".toByteArray(Charsets.US_ASCII)
private val GIF_89A = "GIF89a".toByteArray(Charsets.US_ASCII)
private val BMP_SIGNATURE = byteArrayOf(0x42, 0x4D) // "BM"
private val RIFF_SIGNATURE = "RIFF".toByteArray(Charsets.US_ASCII)
private val WEBP_SIGNATURE = "WEBP".toByteArray(Charsets.US_ASCII)
private val FTYP_SIGNATURE = "ftyp".toByteArray(Charsets.US_ASCII)

/** Marcas `ftyp` de la familia HEIF/AVIF que Android decodifica (heic, heix, avif, mif1…). */
private val FTYP_IMAGE_BRANDS =
    setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs", "mif1", "msf1", "avif", "avis")

/**
 * Puerta rápida por magic bytes ([ImageAttacher] la aplica antes de decodificar):
 * lo que no tiene pinta de imagen — p. ej. un texto renombrado `.jpg` — no
 * entra al pipeline.
 */
internal fun ByteArray.looksLikeImage(): Boolean =
    startsWith(JPEG_SOI) ||
        startsWith(PNG_SIGNATURE) ||
        startsWith(GIF_87A) ||
        startsWith(GIF_89A) ||
        startsWith(BMP_SIGNATURE) ||
        isWebp() ||
        isHeifFamily()

/** `RIFF` + `WEBP` en el offset 8 (contenedor RIFF de WebP). */
private fun ByteArray.isWebp(): Boolean =
    size >= 12 && startsWith(RIFF_SIGNATURE) && copyOfRange(8, 12).contentEquals(WEBP_SIGNATURE)

/** Caja `ftyp` en el offset 4 con una marca de la familia HEIF/AVIF. */
private fun ByteArray.isHeifFamily(): Boolean {
    if (size < 12 || !copyOfRange(4, 8).contentEquals(FTYP_SIGNATURE)) {
        return false
    }
    val brand = String(copyOfRange(8, 12), Charsets.US_ASCII)
    return brand in FTYP_IMAGE_BRANDS
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
