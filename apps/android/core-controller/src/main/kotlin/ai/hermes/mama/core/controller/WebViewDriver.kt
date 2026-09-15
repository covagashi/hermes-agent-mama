package ai.hermes.mama.core.controller

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import java.util.concurrent.atomic.AtomicLong

/**
 * Evento de ciclo de vida/red de la página que observa [WebViewController]
 * (ROADMAP §5/F2): esperar `onPageFinished` + calma de red y detectar la
 * navegación que un click/type/press puede desencadenar.
 *
 * [seq] es un contador monótono asignado por la fuente de eventos
 * ([WebViewEventBus]); permite filtrar «sólo lo que ocurra desde ahora».
 */
public sealed interface WebViewPageEvent {
    public val seq: Long

    /** `WebViewClient.onPageStarted` — comienza una carga de documento. */
    public data class PageStarted(
        override val seq: Long,
        val url: String,
    ) : WebViewPageEvent

    /** `WebViewClient.onPageFinished` — el documento terminó de cargar. */
    public data class PageFinished(
        override val seq: Long,
        val url: String,
    ) : WebViewPageEvent

    /** `WebViewClient.onLoadResource` — actividad de red (para la «calma» de 500 ms). */
    public data class ResourceLoaded(
        override val seq: Long,
        val url: String,
    ) : WebViewPageEvent

    /** `WebViewClient.onReceivedError` del frame principal — cierra la espera de navegación. */
    public data class PageError(
        override val seq: Long,
        val url: String?,
        val description: String,
    ) : WebViewPageEvent
}

/** Captura PNG del viewport: píxeles comprimidos y tamaño final ya escalado (≤ 1 200 px). */
public class WebViewScreenshot(
    public val pngBytes: ByteArray,
    public val width: Int,
    public val height: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is WebViewScreenshot &&
            width == other.width &&
            height == other.height &&
            pngBytes.contentEquals(other.pngBytes)

    override fun hashCode(): Int = 31 * (31 * width + height) + pngBytes.contentHashCode()
}

/**
 * Abstracción del `WebView` que [WebViewController] necesita (§5/F2).
 *
 * La interfaz es deliberadamente pequeña y libre de tipos Android: la lógica del
 * controlador (mapeo comando→JS, esperas, timeouts) vive en `core-controller`
 * (JVM puro) y se prueba con un `FakeWebView`; la implementación real sobre
 * `android.webkit.WebView` — confinada al hilo principal — está en
 * `feature-browser` (`AndroidWebViewDriver`).
 *
 * Contrato de threading: cualquier implementación puede recibir las llamadas
 * desde cualquier corrutina; la impl real las redirige al hilo principal.
 */
public interface WebViewDriver {
    /**
     * Flujo de eventos **posteriores a la llamada** (incluye los emitidos entre
     * la llamada y la primera colección). Sirve para acotar la espera a la
     * navegación que uno mismo provoca sin tragarse eventos de páginas anteriores.
     */
    public fun pageEventsSince(): Flow<WebViewPageEvent>

    /**
     * `WebView.evaluateJavascript`: devuelve el valor del callback **tal cual**
     * (los strings JS llegan con un nivel extra de quoting JSON; `"null"` si el
     * script devolvió `undefined`/lanzó).
     */
    public suspend fun evaluateJavascript(script: String): String

    /** `WebView.loadUrl` — comienza una navegación de documento. */
    public suspend fun loadUrl(url: String)

    /** `WebView.goBack` — retrocede en el historial (llamar sólo si [canGoBack]). */
    public suspend fun goBack()

    /** `WebView.canGoBack`. */
    public suspend fun canGoBack(): Boolean

    /** URL cargada actual (`WebView.getUrl`), ya resueltos redirects. */
    public suspend fun currentUrl(): String?

    /** Título del documento actual (`WebView.getTitle`). */
    public suspend fun currentTitle(): String?

    /**
     * Captura PNG del viewport escalado para que `max(width,height) ≤ maxDimPx`
     * (§5/F2: `PixelCopy`/`draw` → PNG ≤ 1 200 px).
     *
     * @throws Exception si la vista no es capturable (p. ej. no adjunta a ventana).
     */
    public suspend fun capturePng(maxDimPx: Int): WebViewScreenshot
}

/**
 * Fuente de eventos con `seq` monótono + replay acotado, compartida por la impl
 * real (`AndroidWebViewDriver`, emite desde `WebViewClient`) y por `FakeWebView`
 * en tests JVM (emite manualmente).
 *
 * `replay` > 0 es lo que hace seguro [since]: los eventos emitidos entre la
 * llamada a [WebViewDriver.pageEventsSince] y la primera colección quedan en el
 * buffer y se entregan si su `seq` supera la marca.
 */
public class WebViewEventBus(
    replay: Int = DEFAULT_REPLAY,
) {
    private val seq = AtomicLong(0)

    // DROP_OLDEST: emit nunca suspende ni falla; el consumidor sólo necesita los
    // eventos recientes (pageFinished + calma de red), no el historial completo.
    private val events =
        MutableSharedFlow<WebViewPageEvent>(replay = replay, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Emite un evento estampando el siguiente [WebViewPageEvent.seq]. */
    public fun emit(event: (seq: Long) -> WebViewPageEvent) {
        events.tryEmit(event(seq.incrementAndGet()))
    }

    /** Flujo de eventos posteriores a esta llamada (ver [WebViewDriver.pageEventsSince]). */
    public fun since(): Flow<WebViewPageEvent> {
        val mark = seq.get()
        return events.filter { it.seq > mark }
    }

    public companion object {
        public const val DEFAULT_REPLAY: Int = 64
    }
}
