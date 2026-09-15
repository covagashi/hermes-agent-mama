package ai.hermes.mama.feature.chat.attachments

import ai.hermes.mama.contract.AttachedImageResult
import ai.hermes.mama.gateway.GatewayClient
import ai.hermes.mama.gateway.attachImage
import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Base64
import kotlin.math.max

/** Lado mayor máximo de la imagen que sale hacia el gateway (E1, ROADMAP §5). */
internal const val IMAGE_MAX_DIMENSION = 1600

/** Tope del payload final: "≤ 1 MB" del roadmap, interpretado como 1 MiB. */
internal const val IMAGE_MAX_BYTES = 1024 * 1024

/**
 * Tope del fichero de ENTRADA leído del provider: el del backend
 * (`_ATTACH_BYTES_MAX_BYTES` = 25 MiB en `prompt_attachments.py`). La foto de
 * origen puede ser mayor que el payload (se recomprime), pero un stream más
 * allá de este tope no se volcaría entero a memoria — se lee con cota.
 */
internal const val IMAGE_MAX_SOURCE_BYTES = 25 * 1024 * 1024

/** Caracteres admitidos en el nombre de fichero enviado al gateway. */
private val SAFE_FILENAME = Regex("[^A-Za-z0-9_-]")

/**
 * Abre el stream de bytes de un [Uri]. En la app es
 * [ContentResolver.openInputStream]; en tests, un provider falso o un lambda.
 */
fun interface ImageStreamSource {
    @Throws(IOException::class)
    fun open(uri: Uri): InputStream?

    /**
     * Tamaño que declara el provider antes de leer (en la app,
     * `AssetFileDescriptor.getLength` vía [ContentResolver.openAssetFileDescriptor]);
     * `null` o negativo = desconocido. [ImageAttacher] lo usa para rechazar
     * ficheros por encima de [IMAGE_MAX_SOURCE_BYTES] sin volcarlos a memoria.
     */
    fun size(uri: Uri): Long? = null
}

/** Envía el payload de `image.attach_bytes` (ROADMAP §2.3) — en la app es [GatewayClient.attachImage]. */
fun interface AttachedImageSender {
    suspend fun send(
        sessionId: String,
        contentBase64: String,
        filename: String,
    ): AttachedImageResult
}

/** Resultado de [ImageAttacher.attach]: el DTO del backend o un error para texto humano. */
sealed interface ImageAttachOutcome {
    /**
     * `image.attach_bytes` respondió; la foto queda a la cola del siguiente
     * `prompt.submit`.
     *
     * OJO (C5): `response.name` es el nombre que el backend genera al encolar
     * (`upload_<ts>_<n>.<ext>`, ver `_queue_attached_image` en
     * `prompt_attachments.py`), NO el nombre local del dispositivo — la
     * etiqueta del chip en la UI sale del `displayName` local del picker,
     * no de este campo.
     */
    data class Attached(
        val response: AttachedImageResult,
    ) : ImageAttachOutcome

    /** Fallo local o de envío; [error] mapea a un string de `strings_attachments.xml`. */
    data class Failed(
        val error: ImageAttachError,
    ) : ImageAttachOutcome
}

/** Tipos de fallo de [ImageAttacher], pensados para mensajes sin tecnicismos. */
enum class ImageAttachError {
    /** El [Uri] no se pudo abrir o leer (permiso, provider caído, foto borrada…). */
    Unreadable,

    /** Los bytes leídos no son una imagen decodificable. */
    NotAnImage,

    /** Ni recomprimiendo al máximo la foto baja de [IMAGE_MAX_BYTES]. */
    TooLarge,

    /** La llamada `image.attach_bytes` falló (red, servidor…). */
    SendFailed,
}

/**
 * `ImageAttacher` (E1, ROADMAP §5 M4 — data/attachments): prepara una foto de un
 * [Uri] de contenido y la manda por `image.attach_bytes` para que se adjunte al
 * siguiente `prompt.submit`.
 *
 * Pipeline: tamaño declarado / lectura con cota ([IMAGE_MAX_SOURCE_BYTES]) →
 * puerta de magic bytes ([looksLikeImage]) → orientación EXIF aplicada a los
 * píxeles → lado mayor ≤ [IMAGE_MAX_DIMENSION] → JPEG salvo que haya
 * transparencia real (PNG) → recompresión iterativa hasta [IMAGE_MAX_BYTES]
 * ([compressToFit]) → base64.
 *
 * Toda la preparación corre en [ioDispatcher]; la clase no guarda estado.
 */
class ImageAttacher(
    private val streamSource: ImageStreamSource,
    private val sender: AttachedImageSender,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Cableado de la app: lee con [ContentResolver] y envía con
     * [GatewayClient.attachImage] (B4).
     */
    constructor(
        contentResolver: ContentResolver,
        gateway: GatewayClient,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        streamSource =
            object : ImageStreamSource {
                override fun open(uri: Uri): InputStream? = contentResolver.openInputStream(uri)

                override fun size(uri: Uri): Long? =
                    contentResolver
                        .openAssetFileDescriptor(uri, "r")
                        ?.use { it.length.takeIf { length -> length >= 0 } }
            },
        sender =
            AttachedImageSender { sessionId, contentBase64, filename ->
                gateway.attachImage(sessionId = sessionId, contentBase64 = contentBase64, filename = filename)
            },
        ioDispatcher = ioDispatcher,
    )

    /**
     * Lee [uri], lo normaliza (EXIF + tamaño + compresión) y lo envía.
     * [displayName] (p. ej. el nombre del documento del picker) fija el nombre
     * del fichero; sin él se deriva del [uri] con la extensión del formato final.
     */
    @Suppress("TooGenericExceptionCaught") // el sender real es un RPC: puede fallar de mil formas
    suspend fun attach(
        sessionId: String,
        uri: Uri,
        displayName: String? = null,
    ): ImageAttachOutcome {
        val prepared = withContext(ioDispatcher) { prepare(uri, displayName) }
        val image =
            when (prepared) {
                is PrepareResult.Ready -> prepared.image
                is PrepareResult.Failed -> return ImageAttachOutcome.Failed(prepared.error)
            }
        return try {
            val response = sender.send(sessionId, image.contentBase64, image.filename)
            if (response.attached) {
                ImageAttachOutcome.Attached(response)
            } else {
                // El backend responde {attached:false, message} cuando no pudo
                // encolar la imagen (mismo shape que clipboard.paste).
                Timber.w("image.attach_bytes respondió attached=false")
                ImageAttachOutcome.Failed(ImageAttachError.SendFailed)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // §8: el log lleva el tipo de excepción, nunca contenido ni el Uri.
            Timber.w("image.attach_bytes falló (%s)", e.javaClass.simpleName)
            ImageAttachOutcome.Failed(ImageAttachError.SendFailed)
        }
    }

    // --- preparación (todo en ioDispatcher) ---

    private sealed interface PrepareResult {
        data class Ready(
            val image: PreparedImage,
        ) : PrepareResult

        data class Failed(
            val error: ImageAttachError,
        ) : PrepareResult
    }

    private class PreparedImage(
        bytes: ByteArray,
        val filename: String,
    ) {
        /** Base64 calculado dentro de `prepare` (ioDispatcher), no en el hilo del llamador. */
        val contentBase64: String = Base64.getEncoder().encodeToString(bytes)
    }

    private fun prepare(
        uri: Uri,
        displayName: String?,
    ): PrepareResult =
        when (val read = readSourceBytes(uri)) {
            is ReadResult.Ok -> prepareFromBytes(uri, displayName, read.bytes)
            is ReadResult.Failed -> PrepareResult.Failed(read.error)
        }

    /** Resultado de leer el Uri: bytes acotados o el error humano correspondiente. */
    private sealed interface ReadResult {
        data class Ok(
            val bytes: ByteArray,
        ) : ReadResult

        data class Failed(
            val error: ImageAttachError,
        ) : ReadResult
    }

    /**
     * Doble cota anti-OOM: si el provider declara tamaño
     * ([ImageStreamSource.size]) y pasa de [IMAGE_MAX_SOURCE_BYTES] se rechaza
     * sin abrir; si no declara (o miente), la lectura va acotada y corta.
     */
    private fun readSourceBytes(uri: Uri): ReadResult =
        if (isDeclaredOverLimit(uri)) {
            ReadResult.Failed(ImageAttachError.TooLarge)
        } else {
            readBytes(uri)?.let { raw ->
                if (raw.size > IMAGE_MAX_SOURCE_BYTES) {
                    Timber.w("imagen rechazada: supera %d bytes al leer", IMAGE_MAX_SOURCE_BYTES)
                    ReadResult.Failed(ImageAttachError.TooLarge)
                } else {
                    ReadResult.Ok(raw)
                }
            } ?: ReadResult.Failed(ImageAttachError.Unreadable)
        }

    /** ¿El provider declara un tamaño por encima de [IMAGE_MAX_SOURCE_BYTES]? Fallo al consultar → `false`. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // providers lanzan cualquier RuntimeException
    private fun isDeclaredOverLimit(uri: Uri): Boolean =
        try {
            (streamSource.size(uri) ?: -1L).let { declared ->
                (declared > IMAGE_MAX_SOURCE_BYTES).also { over ->
                    if (over) {
                        Timber.w("imagen rechazada por tamaño declarado (%d bytes)", declared)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }

    /** Magic bytes → bounds → decode; cualquier fallo de imagen es [ImageAttachError.NotAnImage]. */
    private fun prepareFromBytes(
        uri: Uri,
        displayName: String?,
        raw: ByteArray,
    ): PrepareResult {
        val bounds = raw.takeIf(ByteArray::looksLikeImage)?.let(::decodeBounds)
        val decoded = bounds?.let { decodeBitmap(raw, it) }
        return if (decoded == null) {
            PrepareResult.Failed(ImageAttachError.NotAnImage)
        } else {
            prepareScaled(uri, displayName, raw, decoded)
        }
    }

    /** EXIF + escala + compresión con los bitmaps intermedios ya creados. */
    private fun prepareScaled(
        uri: Uri,
        displayName: String?,
        raw: ByteArray,
        decoded: Bitmap,
    ): PrepareResult {
        // Bitmaps creados en el pipeline — se reciclan al acabar (la foto puede ser de MP altos).
        val pool = linkedSetOf(decoded)
        return try {
            // El chequeo de transparencia va sobre `decoded`: las 8 variantes
            // EXIF sólo reordenan píxeles (misma cobertura alfa) y así el
            // escaneo corre sobre el bitmap recién decodificado.
            val format =
                if (decoded.hasTransparentPixels()) {
                    Bitmap.CompressFormat.PNG
                } else {
                    Bitmap.CompressFormat.JPEG
                }
            val oriented = decoded.withExifOrientation(exifOrientation(raw)).also(pool::add)
            val sized = oriented.scaledToMax(IMAGE_MAX_DIMENSION).also(pool::add)
            compressToFit(sized, format, pool)
                ?.let { bytes ->
                    PrepareResult.Ready(PreparedImage(bytes, filenameFor(uri, displayName, format)))
                }
                ?: PrepareResult.Failed(ImageAttachError.TooLarge)
        } finally {
            pool.forEach(Bitmap::recycle)
        }
    }

    /**
     * Lee el stream con cota [IMAGE_MAX_SOURCE_BYTES] + 1: nunca vuelca un
     fichero gigante entero a memoria. El byte de más es la señal de "pasado".
     */
    @Suppress("TooGenericExceptionCaught") // providers lanzan cualquier RuntimeException
    private fun readBytes(uri: Uri): ByteArray? =
        try {
            streamSource.open(uri)?.use { it.readAtMost(IMAGE_MAX_SOURCE_BYTES + 1) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // FileNotFoundException, SecurityException, fallos del provider…
            Timber.w("no se pudo leer el Uri de imagen (%s)", e.javaClass.simpleName)
            null
        }

    private fun decodeBounds(bytes: ByteArray): BitmapFactory.Options? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return options.takeIf { it.outWidth > 0 && it.outHeight > 0 }
    }

    private fun decodeBitmap(
        bytes: ByteArray,
        bounds: BitmapFactory.Options,
    ): Bitmap? {
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds) }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }
            .getOrNull()
    }

    /** Mayor potencia de 2 que deja el lado mayor decodificado ≥ [IMAGE_MAX_DIMENSION]. */
    private fun sampleSizeFor(bounds: BitmapFactory.Options): Int {
        val longSide = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longSide / (sample * 2) >= IMAGE_MAX_DIMENSION) {
            sample *= 2
        }
        return sample
    }

    private fun exifOrientation(bytes: ByteArray): Int =
        try {
            ExifInterface(ByteArrayInputStream(bytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: IOException) {
            // Sin EXIF legible → la imagen se usa tal cual viene.
            Timber.d("sin EXIF legible (%s)", e.javaClass.simpleName)
            ExifInterface.ORIENTATION_NORMAL
        }

    /** Nombre de fichero para `image.attach_bytes`: el dado o el del [uri], saneado + extensión real. */
    private fun filenameFor(
        uri: Uri,
        displayName: String?,
        format: Bitmap.CompressFormat,
    ): String {
        val extension = if (format == Bitmap.CompressFormat.PNG) ".png" else ".jpg"
        val raw = displayName ?: uri.lastPathSegment?.substringAfterLast('/') ?: ""
        val base =
            raw
                .substringBeforeLast('.')
                .replace(SAFE_FILENAME, "_")
                .trim('_')
                .ifBlank { "foto" }
        return "$base$extension"
    }
}

/** Lee hasta [limit] bytes del stream (sin OOM: un fichero de 100+MB no se vuelca). */
private fun InputStream.readAtMost(limit: Int): ByteArray {
    val out = ByteArrayOutputStream(minOf(limit, READ_BUFFER_SIZE))
    val buffer = ByteArray(READ_BUFFER_SIZE)
    var remaining = limit
    while (remaining > 0) {
        val read = read(buffer, 0, minOf(buffer.size, remaining))
        if (read < 0) {
            break
        }
        out.write(buffer, 0, read)
        remaining -= read
    }
    return out.toByteArray()
}

private const val READ_BUFFER_SIZE = 8 * 1024
