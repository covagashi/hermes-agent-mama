package ai.hermes.mama.feature.browser

/**
 * Petición cruda del `DownloadListener` del WebView (ROADMAP §5/G1).
 *
 * El listener llega en el hilo principal con la respuesta YA negociada; el
 * [BrowserDownloadManager] la re-descarga por su cuenta (OkHttp con las
 * cookies del `CookieManager` y el `User-Agent` del WebView) porque el
 * listener no entrega el cuerpo.
 */
data class WebViewDownloadRequest(
    /** URL que el WebView intentó navegar (http/https — el resto se rechaza). */
    val url: String,
    /** `User-Agent` del WebView — hay que reenviarlo tal cual al re-descargar. */
    val userAgent: String?,
    /** `Content-Disposition` de la respuesta si lo había. */
    val contentDisposition: String?,
    /** `Content-Type` anunciado por el listener. */
    val mimeType: String?,
    /** `Content-Length` anunciado (puede ser -1; informativo). */
    val contentLength: Long,
)
