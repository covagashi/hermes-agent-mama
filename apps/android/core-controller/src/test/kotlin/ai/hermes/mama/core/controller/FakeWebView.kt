package ai.hermes.mama.core.controller

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonPrimitive

/**
 * `WebViewDriver` de mentira para los tests JVM de [WebViewController] (§5/F2):
 * graba los scripts evaluados y las URLs cargadas, responde con JSON al estilo
 * `evaluateJavascript` (doble quoting) y permite empujar eventos de página a
 * mano para ejercitar las esperas de navegación/calma de red.
 */
class FakeWebView : WebViewDriver {
    val bus = WebViewEventBus()
    val evaluatedScripts = mutableListOf<String>()
    val loadedUrls = mutableListOf<String>()
    var goBackCalls = 0
    var canGoBackValue = false
    var urlValue: String? = "https://hermes.example.invalid/orders.html"
    var titleValue: String? = "Fake Page"
    var screenshotValue = WebViewScreenshot(PNG_STUB, width = 640, height = 480)

    /**
     * Respuesta de `evaluateJavascript`: recibe el script y devuelve el string
     * crudo (ya con el quoting de `evaluateJavascript`). Por defecto el script
     * de inyección responde `"null"` y las llamadas `window.__hermes.*`
     * responden `{"success":true}` — los tests lo especializan por substring.
     */
    var responder: suspend (String) -> String = { script ->
        if (script.startsWith("window.__hermes.")) {
            jsResult("""{"success":true}""")
        } else {
            "null"
        }
    }

    override fun pageEventsSince(): Flow<WebViewPageEvent> = bus.since()

    override suspend fun evaluateJavascript(script: String): String {
        evaluatedScripts += script
        return responder(script)
    }

    override suspend fun loadUrl(url: String) {
        loadedUrls += url
        urlValue = url
    }

    override suspend fun goBack() {
        goBackCalls += 1
    }

    override suspend fun canGoBack(): Boolean = canGoBackValue

    override suspend fun currentUrl(): String? = urlValue

    override suspend fun currentTitle(): String? = titleValue

    override suspend fun capturePng(maxDimPx: Int): WebViewScreenshot = screenshotValue

    // ---- helpers de fixtures ----

    fun emitPageStarted(url: String = urlValue.orEmpty()) {
        bus.emit { seq -> WebViewPageEvent.PageStarted(seq, url) }
    }

    fun emitPageFinished(url: String = urlValue.orEmpty()) {
        bus.emit { seq -> WebViewPageEvent.PageFinished(seq, url) }
    }

    fun emitResourceLoaded(url: String = urlValue.orEmpty()) {
        bus.emit { seq -> WebViewPageEvent.ResourceLoaded(seq, url) }
    }

    fun emitPageError(
        url: String? = urlValue,
        description: String = "net::ERR_FAILED",
    ) {
        bus.emit { seq -> WebViewPageEvent.PageError(seq, url, description) }
    }

    companion object {
        /** `evaluateJavascript` entrega los strings JS con una capa extra de quoting JSON. */
        fun jsResult(innerJson: String): String = JsonPrimitive(innerJson).toString()

        val PNG_STUB =
            byteArrayOf(
                0x89.toByte(),
                0x50,
                0x4E,
                0x47,
                0x0D,
                0x0A,
                0x1A,
                0x0A,
            )
    }
}
