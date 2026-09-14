package ai.hermes.mama.feature.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Activity mínima con un [WebView] a pantalla completa para los tests
 * instrumentados de `hermes_snapshot.js` (F1). El WebView sólo vive en el hilo
 * principal; [onPageFinished] se engancha por test con un latch.
 */
class SnapshotTestActivity : Activity() {
    lateinit var webView: WebView
        private set

    /** Callback invocado desde [WebViewClient.onPageFinished] en el hilo UI. */
    @Volatile
    var onPageFinished: (() -> Unit)? = null

    @SuppressLint("SetJavaScriptEnabled") // imprescindible: el snapshot es JS
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = WebView(this)
        view.settings.javaScriptEnabled = true
        view.webViewClient =
            object : WebViewClient() {
                override fun onPageFinished(
                    view: WebView?,
                    url: String?,
                ) {
                    onPageFinished?.invoke()
                }
            }
        webView = view
        setContentView(view)
    }
}
