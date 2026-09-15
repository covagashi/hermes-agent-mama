package ai.hermes.mama.feature.chat.attachments

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap

/**
 * [ContentProvider] de test: sirve los bytes registrados en [payloads] como un
 * `content://` real para probar `ContentResolver.openInputStream` de punta a
 * punta (ROADMAP §7.3: contratos de comportamiento).
 *
 * - nombre ausente → [FileNotFoundException] (Uri ilegible);
 * - nombre [NAME_NULL_STREAM] → descriptor `null` (provider que no devuelve nada);
 * - [declaredLengths] declara un tamaño distinto al del payload real (un
 *   provider que miente o un fichero gigante sin materializar en el test).
 */
class FakeImageProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openAssetFile(
        uri: Uri,
        mode: String,
    ): AssetFileDescriptor? {
        if (uri.lastPathSegment == NAME_NULL_STREAM) {
            return null
        }
        // Como un provider real respaldado por fichero, el AFD declara el
        // tamaño: el de `declaredLengths` si está (fichero gigante sin
        // materializarlo), si no el del payload registrado.
        val declared = declaredLengths[uri.lastPathSegment] ?: payloads[uri.lastPathSegment]?.size?.toLong()
        return AssetFileDescriptor(openFile(uri, mode), 0, declared ?: AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        val data =
            payloads[uri.lastPathSegment]
                ?: throw FileNotFoundException("sin payload para $uri")
        val cacheDir =
            context?.cacheDir
                ?: throw FileNotFoundException("provider sin context")
        val file = File.createTempFile("fake-image", ".bin", cacheDir)
        file.writeBytes(data)
        file.deleteOnExit()
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ) = null

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ) = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?,
    ) = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?,
    ) = 0

    companion object {
        const val AUTHORITY = "ai.hermes.mama.test.attachments"
        const val NAME_NULL_STREAM = "stream-nulo"

        /** `lastPathSegment` → bytes que servirá el provider. */
        val payloads = ConcurrentHashMap<String, ByteArray>()

        /** `lastPathSegment` → tamaño que declarará vía `AssetFileDescriptor.getLength`. */
        val declaredLengths = ConcurrentHashMap<String, Long>()

        fun uri(name: String): Uri = Uri.parse("content://$AUTHORITY/$name")

        /** Registra el provider para `content://$AUTHORITY/…` en el sandbox de Robolectric. */
        fun register() {
            org.robolectric.android.controller.ContentProviderController
                .of(FakeImageProvider())
                .create(AUTHORITY)
        }
    }
}
