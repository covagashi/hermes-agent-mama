package ai.hermes.mama.feature.chat.attachments

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Calidades JPEG que se prueban en orden hasta que el payload cabe en [IMAGE_MAX_BYTES]. */
private val JPEG_QUALITIES = listOf(90, 80, 70, 55, 40)

/**
 * Factor de reducción por ronda cuando ni la calidad mínima (JPEG) ni el PNG
 * llegan a [IMAGE_MAX_BYTES]; con tope de rondas y un suelo de lado menor.
 */
private const val SHRINK_FACTOR = 0.7f
private const val MAX_SHRINK_ROUNDS = 8
private const val MIN_SHORT_SIDE = 480

/** Densidad de la rejilla para detectar píxeles translúcidos (~96×96 muestreos). */
private const val ALPHA_SAMPLE_GRID = 96

/** Aplica la orientación EXIF a los píxeles (las 8 variantes); normal/ilegible → mismo bitmap. */
internal fun Bitmap.withExifOrientation(orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.setRotate(270f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
        else -> return this
    }
    return runCatching { Bitmap.createBitmap(this, 0, 0, width, height, matrix, true) }
        .getOrElse { this }
}

/**
 * ¿Hay píxeles translúcidos de verdad? Se muestrea una rejilla en vez de
 * `hasAlpha()`: un PNG con canal alfa pero todo opaco comprime mejor como
 * JPEG (y `hasAlpha` no es fiable en algunos entornos de test).
 */
internal fun Bitmap.hasTransparentPixels(): Boolean {
    val step = max(1, min(width, height) / ALPHA_SAMPLE_GRID)
    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            if (getPixel(x, y) ushr 24 != 0xFF) {
                return true
            }
            x += step
        }
        y += step
    }
    return false
}

/** Escala manteniendo proporción hasta que el lado mayor quepa en [maxDimension] (no-op si ya cabe). */
internal fun Bitmap.scaledToMax(maxDimension: Int): Bitmap {
    val longSide = max(width, height)
    if (longSide <= maxDimension) {
        return this
    }
    return scaledBy(maxDimension.toFloat() / longSide)
}

/**
 * Comprime hasta que el payload quepa en [IMAGE_MAX_BYTES]: JPEG baja la calidad
 * en [JPEG_QUALITIES]; luego (y siempre en PNG, que no tiene calidad) reduce la
 * imagen un [SHRINK_FACTOR] por ronda hasta el suelo de lado menor.
 * `null` si ni así cabe → [ImageAttachError.TooLarge].
 */
internal fun compressToFit(
    bitmap: Bitmap,
    format: Bitmap.CompressFormat,
    pool: MutableCollection<Bitmap>,
): ByteArray? {
    var current = bitmap
    var encoded: ByteArray? = null
    var round = 0
    while (encoded == null) {
        encoded = current.encodeWithinLimit(format)
        if (encoded == null) {
            if (round >= MAX_SHRINK_ROUNDS || min(current.width, current.height) <= MIN_SHORT_SIDE) {
                break
            }
            current = current.scaledBy(SHRINK_FACTOR).also(pool::add)
            round++
        }
    }
    return encoded
}

private fun Bitmap.scaledBy(factor: Float): Bitmap =
    Bitmap.createScaledBitmap(
        this,
        max(1, (width * factor).roundToInt()),
        max(1, (height * factor).roundToInt()),
        true,
    )

/** Codifica y devuelve los bytes sólo si caben en [IMAGE_MAX_BYTES] (`null` si no). */
private fun Bitmap.encodeWithinLimit(format: Bitmap.CompressFormat): ByteArray? =
    if (format == Bitmap.CompressFormat.JPEG) {
        JPEG_QUALITIES.firstNotNullOfOrNull { quality ->
            encode(format, quality)?.takeIf { it.size <= IMAGE_MAX_BYTES }
        }
    } else {
        encode(format, 0)?.takeIf { it.size <= IMAGE_MAX_BYTES }
    }

private fun Bitmap.encode(
    format: Bitmap.CompressFormat,
    quality: Int,
): ByteArray? {
    val out = ByteArrayOutputStream()
    return if (compress(format, quality, out)) {
        out.toByteArray()
    } else {
        null
    }
}
