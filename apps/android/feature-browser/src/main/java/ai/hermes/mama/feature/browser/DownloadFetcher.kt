package ai.hermes.mama.feature.browser

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.InputStream

/**
 * Re-descarga la URL de una [WebViewDownloadRequest] con las credenciales del
 * WebView (§5/G1). Se abre la respuesta para leer `Content-Disposition` real
 * y el cuerpo; el consumidor la cierra con [OpenedDownload.close].
 */
interface DownloadFetcher {
    /**
     * GET a [url] con `User-Agent` y `Cookie` del WebView.
     *
     * @throws DownloadException si el servidor no responde 2xx o la red falla.
     */
    suspend fun open(
        url: String,
        userAgent: String?,
        cookies: String?,
    ): OpenedDownload
}

/** Respuesta abierta: metadatos + cuerpo como stream. Cerrarla cierra la conexión. */
interface OpenedDownload : Closeable {
    /** Stream del cuerpo (un solo uso). */
    val body: InputStream

    /** `Content-Type` de la respuesta (mejor que el del listener). */
    val mimeType: String?

    /** `Content-Disposition` de la respuesta (fuente del `filename*` real). */
    val contentDisposition: String?
}

/** El servidor no devolvió un cuerpo descargable. */
class DownloadException(
    message: String,
) : Exception(message)

/** [DownloadFetcher] sobre OkHttp — mismo stack que el WebSocket del gateway. */
class OkHttpDownloadFetcher(
    private val client: OkHttpClient = OkHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DownloadFetcher {
    override suspend fun open(
        url: String,
        userAgent: String?,
        cookies: String?,
    ): OpenedDownload =
        withContext(ioDispatcher) {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .get()
                    .apply {
                        // §5/G1: misma sesión web — UA y cookies del WebView.
                        if (!userAgent.isNullOrBlank()) {
                            header("User-Agent", userAgent)
                        }
                        if (!cookies.isNullOrBlank()) {
                            header("Cookie", cookies)
                        }
                    }.build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                throw DownloadException("HTTP ${response.code}")
            }
            val body = response.body
            if (body == null) {
                response.close()
                throw DownloadException("respuesta sin cuerpo")
            }
            OkHttpOpenedDownload(
                response = response,
                body = body.byteStream(),
                mimeType = body.contentType()?.toString(),
                contentDisposition = response.header("Content-Disposition"),
            )
        }

    private class OkHttpOpenedDownload(
        private val response: okhttp3.Response,
        override val body: InputStream,
        override val mimeType: String?,
        override val contentDisposition: String?,
    ) : OpenedDownload {
        override fun close() {
            response.close()
        }
    }
}
