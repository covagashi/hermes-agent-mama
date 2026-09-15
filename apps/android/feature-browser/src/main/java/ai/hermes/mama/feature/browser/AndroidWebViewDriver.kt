package ai.hermes.mama.feature.browser

import ai.hermes.mama.core.controller.BrowserCommand
import ai.hermes.mama.core.controller.WebViewDriver
import ai.hermes.mama.core.controller.WebViewEventBus
import ai.hermes.mama.core.controller.WebViewPageEvent
import ai.hermes.mama.core.controller.WebViewScreenshot
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.ViewGroup
import android.view.Window
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * [WebViewDriver] sobre `android.webkit.WebView` real (ROADMAP §5/F2).
 *
 * Es la ÚNICA pieza de Android del controlador de navegador:
 * - `settings.javaScriptEnabled = true` se habilita aquí y sólo aquí (es el
 *   WebView que ejecuta `hermes_snapshot.js`; la pantalla Navegador de F4 reusa
 *   esta misma instancia — no crear un segundo WebView con JS).
 * - Toda llamada al `WebView` se confina al hilo principal vía [mainDispatcher];
 *   el [WebViewController] puede invocar desde cualquier corrutina.
 * - `WebViewClient` traduce `onPageStarted`/`onPageFinished`/`onLoadResource`/
 *   `onReceivedError`(frame principal) al [WebViewEventBus] que alimenta las
 *   esperas de «pageFinished + calma de red». No se instala
 *   `shouldOverrideUrlLoading` ni handlers a apps externas (§8).
 * - F4: zoom desactivado (pinza, doble-tap y botones — §5/F4), cookies
 *   persistentes vía `CookieManager` con terceros rechazados, y diálogos JS
 *   auto-resueltos ([AutoDismissJsDialogs]) para que un `alert` no pueda
 *   colgar el hilo del WebView.
 *
 * Ciclo de vida: se crea **en el hilo principal** (la pantalla/Activity que lo
 * hospeda); [webView] es la vista a mostrar; llamar [destroy] al cerrar.
 * El driver —y por tanto el WebView— vive a nivel de sesión de chat, no de
 * pantalla: «Volver al chat» desmonta la vista pero los comandos §2.6 siguen
 * ejecutándose en segundo plano hasta que se cierra el chat (§5/F4).
 */
public class AndroidWebViewDriver(
    context: Context,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val windowProvider: () -> Window? = { null },
) : WebViewDriver {
    private val bus = WebViewEventBus()
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        // WebView exige un Looper thread; el driver además confina todo a main.
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "AndroidWebViewDriver must be created on the main thread"
        }
    }

    /** La vista real: la pantalla Navegador la monta como contenido. */
    public val webView: WebView =
        WebView(context)
            .apply {
                @SuppressLint("SetJavaScriptEnabled") // imprescindible: los comandos son JS (§2.6)
                settings.javaScriptEnabled = true
                // localStorage/sessionStorage: sin DOM storage los logins y SPAs reales se rompen.
                settings.domStorageEnabled = true
                settings.safeBrowsingEnabled = true
                // Frontera §8: el WebView no lee file:// ni content:// (la allowlist
                // de navigate ya los veta; aquí cierra también la vía de redirects/iframes).
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                // §5/F4: sin zoom accidental — ni pinza/doble-tap ni los botones +/-.
                settings.setSupportZoom(false)
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                webViewClient = ControllerWebViewClient()
                // §2.6 `pending_dialogs`: un alert/confirm/prompt JS no puede
                // quedar colgando el hilo del WebView (y menos un modal nativo).
                webChromeClient = AutoDismissJsDialogs()
            }.also { view ->
                // §5/F4: cookies persistentes (almacén privado de la app) y
                // rechazo de cookies de terceros (§8: menos tracking).
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(view, false)
                }
            }

    override fun pageEventsSince(): Flow<WebViewPageEvent> = bus.since()

    override suspend fun evaluateJavascript(script: String): String =
        onMain {
            suspendCancellableCoroutine<String> { cont ->
                webView.evaluateJavascript(script) { value ->
                    if (cont.isActive) {
                        cont.resumeWith(Result.success(value ?: "null"))
                    }
                }
            }
        }

    override suspend fun loadUrl(url: String) {
        // Defensa en profundidad: la allowlist vive en BrowserCommand.from;
        // aquí se repite por si otra vía llegara a tocar el driver.
        require(BrowserCommand.isNavigableUrl(url)) {
            "Refusing to load non-http(s) URL: ${url.take(MAX_URL_TAG_CHARS)}"
        }
        onMain { webView.loadUrl(url) }
    }

    override suspend fun goBack() {
        onMain { webView.goBack() }
    }

    override suspend fun canGoBack(): Boolean = onMain { webView.canGoBack() }

    override suspend fun currentUrl(): String? = onMain { webView.url }

    override suspend fun currentTitle(): String? = onMain { webView.title }

    /**
     * `PixelCopy` (acelerado por hardware) con respaldo `draw()` a bitmap
     * software — cubre el caso de vista no adjunta/no compuesta. Después escala
     * para que el lado mayor ≤ [maxDimPx] (§5/F2: ≤ 1 200 px).
     */
    override suspend fun capturePng(maxDimPx: Int): WebViewScreenshot =
        onMain {
            val source = captureBitmap()
            renderInto(source)
            try {
                val scaled = scaleToFit(source, maxDimPx)
                try {
                    val out = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
                    WebViewScreenshot(out.toByteArray(), scaled.width, scaled.height)
                } finally {
                    if (scaled !== source) {
                        scaled.recycle()
                    }
                }
            } finally {
                source.recycle()
            }
        }

    /** Libera el WebView (al cerrar la pantalla Navegador / Activity). */
    public fun destroy() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "AndroidWebViewDriver.destroy() must run on the main thread"
        }
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    private suspend fun <T> onMain(block: suspend () -> T): T = withContext(mainDispatcher) { block() }

    private fun captureBitmap(): Bitmap {
        val width = webView.width
        val height = webView.height
        require(width > 0 && height > 0) { "WebView is not laid out yet" }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * Rellena [target] con el contenido visible: `PixelCopy` sobre la [Window]
     * que hospeda el WebView (acelerado por hardware, la vía preferida de §5/F2)
     * con respaldo `draw()` a bitmap software cuando no hay ventana o la copia
     * falla. La corrutina suspende sin bloquear el hilo principal.
     */
    private suspend fun renderInto(target: Bitmap) {
        if (copyViaPixelCopy(target)) {
            return
        }
        webView.draw(Canvas(target))
    }

    private suspend fun copyViaPixelCopy(target: Bitmap): Boolean {
        val window = windowProvider() ?: return false
        val viewLoc = IntArray(2)
        val windowLoc = IntArray(2)
        webView.getLocationOnScreen(viewLoc)
        window.decorView.getLocationOnScreen(windowLoc)
        val srcRect =
            Rect(
                viewLoc[0] - windowLoc[0],
                viewLoc[1] - windowLoc[1],
                viewLoc[0] - windowLoc[0] + webView.width,
                viewLoc[1] - windowLoc[1] + webView.height,
            )
        return suspendCancellableCoroutine { cont ->
            val requested =
                runCatching {
                    PixelCopy.request(
                        window,
                        srcRect,
                        target,
                        { code ->
                            if (cont.isActive) {
                                cont.resumeWith(Result.success(code == PixelCopy.SUCCESS))
                            }
                        },
                        mainHandler,
                    )
                }
            if (requested.isFailure && cont.isActive) {
                cont.resumeWith(Result.success(false))
            }
        }
    }

    private fun scaleToFit(
        source: Bitmap,
        maxDimPx: Int,
    ): Bitmap {
        val maxSide = maxOf(source.width, source.height)
        if (maxSide <= maxDimPx) {
            return source
        }
        val ratio = maxDimPx.toFloat() / maxSide
        val w = maxOf(1, (source.width * ratio).roundToInt())
        val h = maxOf(1, (source.height * ratio).roundToInt())
        return Bitmap.createScaledBitmap(source, w, h, true)
    }

    /** Traduce los callbacks del ciclo de vida al bus de eventos (ya en main). */
    private inner class ControllerWebViewClient : WebViewClient() {
        override fun onPageStarted(
            view: WebView?,
            url: String?,
            favicon: Bitmap?,
        ) {
            bus.emit { seq -> WebViewPageEvent.PageStarted(seq, url.orEmpty()) }
        }

        override fun onPageFinished(
            view: WebView?,
            url: String?,
        ) {
            bus.emit { seq -> WebViewPageEvent.PageFinished(seq, url.orEmpty()) }
        }

        override fun onLoadResource(
            view: WebView?,
            url: String?,
        ) {
            bus.emit { seq -> WebViewPageEvent.ResourceLoaded(seq, url.orEmpty()) }
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?,
        ) {
            if (request?.isForMainFrame == true) {
                val description = error?.description?.toString().orEmpty()
                bus.emit { seq -> WebViewPageEvent.PageError(seq, request.url?.toString(), description) }
            }
        }
    }

    /**
     * Diálogos JS auto-resueltos (§2.6 `pending_dialogs`): `alert` se acepta;
     * `confirm`/`prompt` se cancelan — igual que el auto-dismiss del snapshot
     * F1. Devolver `true` = «manejado»: jamás sale el modal nativo.
     */
    private class AutoDismissJsDialogs : WebChromeClient() {
        override fun onJsAlert(
            view: WebView?,
            url: String?,
            message: String?,
            result: JsResult?,
        ): Boolean {
            result?.confirm()
            return true
        }

        override fun onJsConfirm(
            view: WebView?,
            url: String?,
            message: String?,
            result: JsResult?,
        ): Boolean {
            result?.cancel()
            return true
        }

        override fun onJsPrompt(
            view: WebView?,
            url: String?,
            message: String?,
            defaultValue: String?,
            result: JsPromptResult?,
        ): Boolean {
            result?.cancel()
            return true
        }

        // beforeunload: confirmar = dejar navegar (el comando manda, no el diálogo).
        override fun onJsBeforeUnload(
            view: WebView?,
            url: String?,
            message: String?,
            result: JsResult?,
        ): Boolean {
            result?.confirm()
            return true
        }
    }

    private companion object {
        const val PNG_QUALITY = 100
        const val MAX_URL_TAG_CHARS = 64
    }
}
