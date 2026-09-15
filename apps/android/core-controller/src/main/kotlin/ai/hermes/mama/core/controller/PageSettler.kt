package ai.hermes.mama.core.controller

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Motor de «espera a que la página quede estable» de [WebViewController]
 * (ROADMAP §5/F2), extraído para mantener el controlador legible:
 *
 * - `navigate`/`back` llaman con `peekForNavigationMs = null`: la navegación se
 *   da por hecha → espera su `PageFinished`/`PageError` + calma de red.
 * - Tras click/type/press/scroll se llama con una ventana de detección: si la
 *   acción abrió una página (PageStarted dentro del peek) se espera su carga;
 *   si no, se vuelve enseguida.
 *
 * «Estable» = sin navegación pendiente y [networkCalmMs] ms sin actividad de
 * red (`onLoadResource`), con techo de presupuesto por llamada. Los eventos se
 * vacían a un [Channel] porque el [Flow] del driver colecciona desde el hilo de
 * la corrutina llamante y el tiempo lo da [nowMs] (reloj virtual en tests).
 */
internal class PageSettler(
    private val nowMs: () -> Long,
    private val networkCalmMs: Long,
) {
    /**
     * Suspende hasta que la página esté estable o se agote [navBudgetMs].
     * Nunca lanza: un WebView errático sólo hace volver antes de tiempo.
     *
     * @return el último [WebViewPageEvent.PageError] del frame principal visto
     *   durante la navegación, o `null` si la carga fue limpia — el llamador lo
     *   convierte en `ok:false` "Navigation failed: <desc>".
     */
    suspend fun awaitSettled(
        events: Flow<WebViewPageEvent>,
        navBudgetMs: Long,
        peekForNavigationMs: Long?,
    ): WebViewPageEvent.PageError? =
        coroutineScope {
            val channel = Channel<WebViewPageEvent>(capacity = Channel.UNLIMITED)
            val collector = launch { events.collect { channel.send(it) } }
            try {
                val navStarted =
                    if (peekForNavigationMs != null) {
                        awaitNavStart(channel, peekForNavigationMs)
                    } else {
                        true // navigate/back: la navegación viene dada por la propia acción
                    }
                if (navStarted) {
                    drainUntilCalm(channel, navBudgetMs)
                } else {
                    null
                }
            } finally {
                collector.cancel()
            }
        }

    /**
     * Ventana de detección post-acción: `true` si empezó una navegación
     * (`PageStarted`) dentro de [peekMs]. `PageFinished`/`PageError`/
     * `ResourceLoaded` sueltos no cuentan como navegación nueva.
     */
    private suspend fun awaitNavStart(
        channel: Channel<WebViewPageEvent>,
        peekMs: Long,
    ): Boolean {
        val deadline = nowMs() + peekMs
        var event: WebViewPageEvent?
        do {
            val remaining = deadline - nowMs()
            event =
                if (remaining > 0) {
                    withTimeoutOrNull(remaining) { channel.receive() }
                } else {
                    null
                }
        } while (event != null && event !is WebViewPageEvent.PageStarted)
        return event is WebViewPageEvent.PageStarted
    }

    /**
     * Bucle principal: entra con una navegación en curso y sale cuando no hay
     * navegación pendiente y la red lleva [networkCalmMs] ms en calma, o al
     * agotar [navBudgetMs].
     *
     * @return el último [WebViewPageEvent.PageError] del frame principal (un
     *   `PageFinished` posterior — p. ej. la página de error del sistema — no
     *   lo borra: la navegación igualmente fracasó).
     */
    private suspend fun drainUntilCalm(
        channel: Channel<WebViewPageEvent>,
        navBudgetMs: Long,
    ): WebViewPageEvent.PageError? {
        var navPending = true
        var lastActivity = nowMs()
        var lastError: WebViewPageEvent.PageError? = null
        val deadline = lastActivity + navBudgetMs
        var waiting = true
        while (waiting) {
            val now = nowMs()
            val slice = minOf(networkCalmMs, deadline - now)
            val calmReached = !navPending && now - lastActivity >= networkCalmMs
            if (calmReached || slice <= 0) {
                waiting = false
            } else {
                when (val event = withTimeoutOrNull(slice) { channel.receive() }) {
                    // Ventana de calma consumida sin eventos: si no hay navegación, fin.
                    null -> waiting = navPending
                    is WebViewPageEvent.PageStarted -> {
                        navPending = true
                        lastActivity = nowMs()
                    }
                    is WebViewPageEvent.PageFinished -> {
                        navPending = false
                        lastActivity = nowMs()
                    }
                    is WebViewPageEvent.PageError -> {
                        navPending = false
                        lastActivity = nowMs()
                        lastError = event
                    }
                    is WebViewPageEvent.ResourceLoaded -> lastActivity = nowMs()
                }
            }
        }
        return lastError
    }
}
