package ai.hermes.mama.feature.browser

import ai.hermes.mama.core.controller.BrowserCommand
import android.content.Context
import android.content.Intent
import android.webkit.CookieManager
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Descargas del WebView (ROADMAP §5/G1, mockup Documento.dc.html).
 *
 * El `DownloadListener` del WebView (ver [AndroidWebViewDriver.downloadHandler])
 * delega aquí cada [WebViewDownloadRequest]; el trabajo sale a [scope]:
 *
 * 1. Re-descarga la URL con OkHttp llevando `User-Agent` y cookies del
 *    `CookieManager` del WebView (el listener no entrega el cuerpo).
 * 2. Resuelve el nombre con [DownloadFileName] (`filename*` RFC 5987 incluido)
 *    y vuelca a `Downloads/Hermes/` vía MediaStore ([DownloadSink]).
 * 3. Publica el documento en [completed] → la hoja inferior «📄 nombre —
 *    Abrir · Compartir · Enviar a Hermes».
 * 4. Notificación del sistema ([DownloadNotifier]) y aviso a la sesión
 *    ([DownloadReporter] — nota en el resultado del comando en curso si lo
 *    hay + `prompt.submit` `display_kind:"system"`).
 *
 * Vive a nivel de sesión de chat (como el driver), no de pantalla: una
 * descarga puede terminar con la pantalla Navegador cerrada — la hoja se verá
 * al reabrirla y el aviso/notificación salen igual.
 *
 * §8: los logs llevan tipos de excepción, jamás URLs ni nombres de fichero.
 */
class BrowserDownloadManager(
    context: Context,
    private val scope: CoroutineScope,
    private val fetcher: DownloadFetcher = OkHttpDownloadFetcher(),
    private val sink: DownloadSink = MediaStoreDownloadSink(context),
    private val cookies: (String) -> String? = { url -> webViewCookies(url) },
    private val notifier: DownloadNotifier = DownloadNotifier(context),
    private val reporter: DownloadReporter? = null,
    private val logger: (String) -> Unit = {},
) {
    private val appContext = context.applicationContext

    private val mutableCompleted = MutableStateFlow<DownloadedDoc?>(null)

    /** Último documento guardado y aún sin descartar → la hoja inferior (§5/G1). */
    val completed: StateFlow<DownloadedDoc?> = mutableCompleted.asStateFlow()

    /**
     * `DownloadListener.onDownloadStart` (hilo main del WebView): valida el
     * esquema y lanza la descarga a [scope] — la UI nunca se bloquea.
     */
    fun onDownloadStart(request: WebViewDownloadRequest) {
        // Misma allowlist que navigate (§8): sólo http(s) entra en el fetcher.
        if (!BrowserCommand.isNavigableUrl(request.url)) {
            warn("descarga rechazada (esquema no http(s))")
            return
        }
        scope.launch { runDownload(request) }
    }

    /** Cierra la hoja inferior sin más acción (la usuaria ya vio el aviso). */
    fun dismissSheet() {
        mutableCompleted.value = null
    }

    /** «Abrir»: `ACTION_VIEW` al `content://` de MediaStore. */
    fun openCurrent() {
        val doc = mutableCompleted.value ?: return
        val intent =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(android.net.Uri.parse(doc.contentUri), doc.mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(appContext.packageManager) == null) {
            // Sin visor instalado: Toast claro y la hoja sigue (puede Compartir).
            Toast
                .makeText(appContext, R.string.download_open_failed, Toast.LENGTH_LONG)
                .show()
            return
        }
        runCatching { appContext.startActivity(intent) }
            .onFailure { warn("abrir descarga falló (${it::class.simpleName})") }
    }

    /** «Compartir»: `ACTION_SEND` con chooser — el sistema lo resuelve siempre. */
    fun shareCurrent() {
        val doc = mutableCompleted.value ?: return
        val uri = android.net.Uri.parse(doc.contentUri)
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = doc.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                // ClipData: sin él el FLAG_GRANT no llega a todos los destinos
                // del chooser (quirk documentado de ACTION_SEND).
                clipData =
                    android.content.ClipData.newUri(
                        appContext.contentResolver,
                        doc.fileName,
                        uri,
                    )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        val chooser =
            Intent
                .createChooser(send, appContext.getString(R.string.download_share_chooser))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(chooser) }
            .onFailure { warn("compartir descarga falló (${it::class.simpleName})") }
    }

    /**
     * «Enviar a Hermes» — llega en **G2** (`file.attach` + prompt). El botón de
     * la hoja queda deshabilitado hasta entonces; el hook ya está cableado.
     */
    fun sendCurrentToHermes() {
        warn("Enviar a Hermes llega en G2 (file.attach)")
    }

    // ------------------------------------------------------------- interno --

    private suspend fun runDownload(request: WebViewDownloadRequest) {
        try {
            fetcher
                .open(request.url, request.userAgent, cookies(request.url))
                .use { opened ->
                    val mimeType =
                        opened.mimeType?.takeUnless { it.isBlank() }
                            ?: request.mimeType?.takeUnless { it.isBlank() }
                            ?: DEFAULT_MIME
                    // La cabecera REAL de la respuesta gana a la del listener.
                    val disposition = opened.contentDisposition ?: request.contentDisposition
                    val fileName = DownloadFileName.resolve(request.url, disposition, mimeType)
                    val saved =
                        sink.save(fileName, mimeType) { out ->
                            opened.body.copyTo(out)
                        }
                    val doc =
                        DownloadedDoc(
                            fileName = saved.displayName,
                            mimeType = mimeType,
                            sizeBytes = saved.sizeBytes,
                            contentUri = saved.contentUri,
                        )
                    mutableCompleted.value = doc
                    notifier.notifySaved(doc)
                    reporter?.onDownloadSaved(doc)
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DownloadException) {
            warn("descarga no completada (${e.message})")
        } catch (e: IOException) {
            warn("descarga no completada (${e::class.simpleName})")
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // §8: tipos, nunca URL/nombre — la hoja no informa del fallo (G1).
            warn("descarga no completada (${e::class.simpleName})")
        }
    }

    private fun warn(message: String) {
        runCatching { logger(message) }
    }

    private companion object {
        const val DEFAULT_MIME = "application/octet-stream"

        /** `CookieManager` del proceso: las cookies que el WebView negoció. */
        fun webViewCookies(url: String): String? =
            runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
    }
}
