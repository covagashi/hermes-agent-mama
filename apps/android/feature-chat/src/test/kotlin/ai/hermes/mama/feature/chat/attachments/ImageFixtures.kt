package ai.hermes.mama.feature.chat.attachments

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random

/**
 * Fixtures de imagen 100 % sintéticas (ROADMAP §7.2): se generan en el test con
 * [Bitmap.compress] — nada de fotos reales ni ficheros binarios en el repo.
 */
object ImageFixtures {
    val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte())

    /** Bitmap ARGB_8888 de ruido determinista (comprime mal: sirve para forzar >1 MB). */
    fun noiseBitmap(
        width: Int,
        height: Int,
        transparentLeftHalf: Boolean = false,
        seed: Int = 42,
    ): Bitmap {
        val random = Random(seed.toLong())
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val row = IntArray(width)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val alpha = if (transparentLeftHalf && x < width / 2) 0x00 else 0xFF
                row[x] = (alpha shl 24) or (random.nextInt() and 0xFFFFFF)
            }
            bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }
        return bitmap
    }

    /** JPEG real codificado desde [noiseBitmap]. */
    fun noiseJpeg(
        width: Int,
        height: Int,
        quality: Int = 95,
    ): ByteArray {
        val bitmap = noiseBitmap(width, height)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    /** JPEG real con un tag EXIF de orientación escrito de verdad en el fichero. */
    fun noiseJpegWithExif(
        width: Int,
        height: Int,
        orientation: Int,
        dir: File,
    ): ByteArray {
        val file = File.createTempFile("fixture-exif", ".jpg", dir)
        try {
            file.writeBytes(noiseJpeg(width, height))
            val exif = ExifInterface(file)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            exif.saveAttributes()
            return file.readBytes()
        } finally {
            file.delete()
        }
    }

    /** PNG real con la mitad izquierda totalmente transparente. */
    fun alphaPng(
        width: Int,
        height: Int,
    ): ByteArray {
        val bitmap = noiseBitmap(width, height, transparentLeftHalf = true)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    /** PNG real completamente opaco (canal alfa a 0xFF en todo). */
    fun opaquePng(
        width: Int,
        height: Int,
    ): ByteArray {
        val bitmap = noiseBitmap(width, height, transparentLeftHalf = false)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    /** Carga "no imagen" para el caso NotAnImage. */
    fun garbageBytes(): ByteArray = "esto no es una imagen, es texto".toByteArray()
}

/** ¿Empieza por los magic bytes dados (FFD8 JPEG, 89504E47 PNG…)? */
internal fun ByteArray.hasMagicPrefix(magic: ByteArray): Boolean =
    size >= magic.size && magic.indices.all { this[it] == magic[it] }
