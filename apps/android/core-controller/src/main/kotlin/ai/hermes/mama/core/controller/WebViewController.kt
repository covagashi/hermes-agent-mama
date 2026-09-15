package ai.hermes.mama.core.controller

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.concurrent.ConcurrentHashMap

/**
 * Ejecuta [BrowserCommand] sobre un [WebViewDriver] (ROADMAP §5/F2).
 *
 * Vive en `core-controller` (JVM puro): toda la lógica — mapeo comando→JS de
 * `window.__hermes` (F1), esperas de `onPageFinished` + calma de red, timeout
 * interno y cancelación — se prueba en JVM con un `FakeWebView`. La impl real
 * sobre `android.webkit.WebView` (hilo principal) es `AndroidWebViewDriver`
 * en `feature-browser`.
 *
 * Semántica del protocolo (§2.6):
 * - Un comando = un job; los comandos se **serializan** (un WebView no ejecuta
 *   dos acciones a la vez con sentido) y se indexan por `command_id` para
 *   `browser.controller.cancel`.
 * - Timeout interno [Timeouts.commandMs] = 25 s < 30 s del broker → `ok:false`
 *   siempre llega antes de que el servidor venza el comando.
 * - El resultado es el string JSON de §2.6 ([BrowserCommandOutcome]): las
 *   acciones `click`/`type`/`press`/`scroll` pasan verbatim el objeto que
 *   devuelve `window.__hermes` (mismo generador que el snapshot).
 */
public class WebViewController(
    private val driver: WebViewDriver,
    private val scope: CoroutineScope,
    private val timeouts: Timeouts = Timeouts(),
    private val nowMs: () -> Long = { System.nanoTime() / NANOS_PER_MS },
    private val scriptSource: () -> String = SnapshotScript::load,
) {
    /** Ventanas de espera (§5/F2); inyectables para tests con reloj virtual. */
    public data class Timeouts(
        /** Timeout interno por comando: 25 s < 30 s del broker (§2.6). */
        val commandMs: Long = 25_000,
        /** Máximo para `onPageFinished` + calma de red tras navigate/back: 10 s. */
        val navSettleMs: Long = 10_000,
        /** «Calma de red»: ms sin [WebViewPageEvent.ResourceLoaded] para dar por estable: 500 ms. */
        val networkCalmMs: Long = 500,
        /** Espera fija tras click/type/press/scroll antes de la comprobación de navegación: 300 ms. */
        val actionSettleMs: Long = 300,
        /** Ventana extra para detectar la navegación que la acción pudo iniciar (peek). */
        val actionNavPeekMs: Long = 500,
    )

    /** command_id → job en curso; la cancelación del broker lo busca aquí. */
    private val inflight = ConcurrentHashMap<String, Job>()

    /** Los comandos se ejecutan de uno en uno: el WebView es un recurso único. */
    private val execMutex = Mutex()

    /** «pageFinished + calma de red» (extraído a [PageSettler] por claridad). */
    private val settler = PageSettler(nowMs, timeouts.networkCalmMs)

    /**
     * Ejecuta [command] y devuelve el resultado §2.6. Nunca lanza: cualquier
     * fallo se convierte en `ok:false` con motivo humano en inglés.
     * `command_id` duplicado → rechazo inmediato sin tocar el WebView.
     */
    @Suppress("TooGenericExceptionCaught") // frontera §2.6: ningún fallo del driver debe propagarse
    public suspend fun execute(command: BrowserCommand): BrowserCommandOutcome {
        val outcome = CompletableDeferred<BrowserCommandOutcome>()
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                val result =
                    try {
                        execMutex.withLock {
                            withTimeoutOrNull(timeouts.commandMs) { dispatch(command) }
                                ?: BrowserCommandOutcome.failure(
                                    "Command timed out after ${timeouts.commandMs / 1_000} s (internal limit)",
                                )
                        }
                    } catch (e: CancellationException) {
                        // La cancelación también es un resultado §2.6: se entrega y se propaga.
                        outcome.complete(BrowserCommandOutcome.failure("Command cancelled"))
                        throw e
                    } catch (e: Exception) {
                        BrowserCommandOutcome.failure(humanError(e))
                    }
                outcome.complete(result)
            }
        if (inflight.putIfAbsent(command.commandId, job) != null) {
            job.cancel()
            return BrowserCommandOutcome.failure(
                "Duplicate command_id \"${command.commandId.take(MAX_WIRE_TAG_CHARS)}\"",
            )
        }
        // Si el job muere sin correr su cuerpo (cancel en la ventana
        // putIfAbsent→start, scope ya muerto o un Error fuera de Exception),
        // outcome.await() no puede colgar: la muerte del job produce resultado.
        job.invokeOnCompletion { cause ->
            if (!outcome.isCompleted) {
                val reason =
                    when {
                        cause == null || cause is CancellationException -> "Command cancelled"
                        cause is Exception -> humanError(cause)
                        else -> cause.message?.take(MAX_ERROR_CHARS) ?: "Command failed"
                    }
                outcome.complete(BrowserCommandOutcome.failure(reason))
            }
        }
        job.start()
        return try {
            outcome.await()
        } finally {
            inflight.remove(command.commandId, job)
            // El llamador se fue (cancelaron su corrutina): el trabajo no queda huérfano.
            if (!outcome.isCompleted) {
                job.cancel()
            }
        }
    }

    /**
     * `browser.controller.cancel {command_id}` → aborta el comando si sigue
     * vivo. Un job ya completado (ventana completado→remove del mapa) devuelve
     * false: el resultado §2.6 ya se entregó, no hay nada que cancelar.
     */
    public fun cancel(commandId: String): Boolean =
        inflight[commandId]?.let { job ->
            // isActive excluiría un job LAZY ya registrado pero aún en New
            // (ventana putIfAbsent→start): cancelarlo igual — nunca arrancará
            // y su invokeOnCompletion produce el resultado "Command cancelled".
            if (!job.isCompleted) {
                job.cancel()
                true
            } else {
                false
            }
        } == true

    /** Aborta todo lo en curso (detach de la sesión controladora, F3). */
    public fun cancelAll() {
        inflight.values.forEach { it.cancel() }
    }

    // ------------------------------------------------------------- acciones ---

    private suspend fun dispatch(command: BrowserCommand): BrowserCommandOutcome =
        when (command) {
            is BrowserCommand.Noop -> BrowserCommandOutcome.ok(CommandResults.success())
            is BrowserCommand.Invalid -> BrowserCommandOutcome.failure(command.reason)
            is BrowserCommand.Unsupported ->
                BrowserCommandOutcome.failure("Unsupported action \"${command.action.take(MAX_WIRE_TAG_CHARS)}\"")

            is BrowserCommand.Navigate -> navigate(command)
            is BrowserCommand.TakeSnapshot -> snapshot(command)
            is BrowserCommand.Back -> back()
            is BrowserCommand.Screenshot -> screenshot()
            is BrowserCommand.Tabs -> tabs()
            is BrowserCommand.TabActivate -> tabActivate(command)

            is BrowserCommand.Click -> jsAction("click(${jsStringLiteral(command.ref)})")
            is BrowserCommand.Type ->
                jsAction("type(${jsStringLiteral(command.ref)}, ${jsStringLiteral(command.text)})")
            is BrowserCommand.Press -> jsAction("press(${jsStringLiteral(command.key)})")
            is BrowserCommand.Scroll -> jsAction("scroll(${jsStringLiteral(command.direction)})")
        }

    /**
     * `browser_navigate` (§5/F2): `loadUrl` → `onPageFinished` + 500 ms de calma
     * de red (10 s máx) → snapshot **compacto** en el resultado.
     */
    private suspend fun navigate(command: BrowserCommand.Navigate): BrowserCommandOutcome {
        val events = driver.pageEventsSince()
        driver.loadUrl(command.url)
        val navError = settler.awaitSettled(events, timeouts.navSettleMs, peekForNavigationMs = null)
        if (navError != null) {
            return navigationError(navError)
        }
        // takeSnapshot ya hace ensureHermes: el mundo JS post-navegación se reconstruye ahí.
        val snap = takeSnapshot(full = false)
        val finalUrl = driver.currentUrl() ?: command.url
        val title = driver.currentTitle().orEmpty()
        return BrowserCommandOutcome.ok(
            CommandResults.success {
                put("url", finalUrl)
                put("title", title)
                put("snapshot", snap.text)
                put("element_count", snap.elementCount)
            },
        )
    }

    /** `browser_snapshot` → `{"success":true,"snapshot":…,"element_count":N}`. */
    private suspend fun snapshot(command: BrowserCommand.TakeSnapshot): BrowserCommandOutcome {
        val snap = takeSnapshot(command.full)
        return BrowserCommandOutcome.ok(
            CommandResults.success {
                put("snapshot", snap.text)
                put("element_count", snap.elementCount)
            },
        )
    }

    /** click/type/press/scroll: eval `__hermes.<fn>` → 300 ms + comprobación de navegación. */
    private suspend fun jsAction(call: String): BrowserCommandOutcome {
        ensureHermes()
        // Marca ANTES del eval: un PageStarted emitido durante la acción también
        // cuenta (si se marcara después, quedaría filtrado y leeríamos un DOM
        // a medio cargar en el siguiente snapshot).
        val events = driver.pageEventsSince()
        val result = evalHermes(call)
        if (result["success"]?.jsonPrimitive?.booleanOrNull == false) {
            val error =
                result["error"]?.jsonPrimitive?.contentOrNull?.take(MAX_ERROR_CHARS)
                    ?: "Action failed"
            return BrowserCommandOutcome.failure(error)
        }
        postActionSettle(events)
        // El objeto JS ya tiene la forma exacta de §2.6: se pasa verbatim.
        return BrowserCommandOutcome.ok(result.toString())
    }

    /** `browser_back` → `goBack` + settle → `{"success":true,"url":…}`. */
    private suspend fun back(): BrowserCommandOutcome {
        if (!driver.canGoBack()) {
            return BrowserCommandOutcome.failure("Cannot go back: no previous page")
        }
        val events = driver.pageEventsSince()
        driver.goBack()
        val navError = settler.awaitSettled(events, timeouts.navSettleMs, peekForNavigationMs = null)
        return if (navError != null) {
            navigationError(navError)
        } else {
            ensureHermes()
            val url = driver.currentUrl().orEmpty()
            BrowserCommandOutcome.ok(CommandResults.success { put("url", url) })
        }
    }

    /** `browser_screenshot` → PNG base64 con `max(width,height)` ≤ 1 200 px (§5/F2). */
    private suspend fun screenshot(): BrowserCommandOutcome {
        val shot = driver.capturePng(SCREENSHOT_MAX_EDGE_PX)
        return BrowserCommandOutcome.ok(
            CommandResults.success {
                put(
                    "image_base64",
                    java.util.Base64
                        .getEncoder()
                        .encodeToString(shot.pngBytes),
                )
                put("width", shot.width)
                put("height", shot.height)
            },
        )
    }

    /** `browser_tabs` — la app maneja una sola pestaña (§2.6). */
    private suspend fun tabs(): BrowserCommandOutcome {
        val url = driver.currentUrl().orEmpty()
        val title = driver.currentTitle().orEmpty()
        return BrowserCommandOutcome.ok(
            CommandResults.success {
                putJsonArray("tabs") {
                    addJsonObject {
                        put("id", SINGLE_TAB_ID)
                        put("url", url)
                        put("title", title)
                        put("active", true)
                    }
                }
            },
        )
    }

    /** `browser_tab_activate {id}` — sólo existe la pestaña "1". */
    private suspend fun tabActivate(command: BrowserCommand.TabActivate): BrowserCommandOutcome =
        if (command.tabId.trim() == SINGLE_TAB_ID) {
            BrowserCommandOutcome.ok(CommandResults.success())
        } else {
            BrowserCommandOutcome.failure(
                "No tab found with id \"${command.tabId.trim().take(MAX_WIRE_TAG_CHARS)}\"",
            )
        }

    // ------------------------------------------------------- JS `__hermes` ----

    /** Inyecta `hermes_snapshot.js` (idempotente: el IIFE no-op si ya está la versión). */
    private suspend fun ensureHermes() {
        driver.evaluateJavascript(scriptSource())
    }

    /**
     * Evalúa `window.__hermes.<call>` y desenvuelve el doble quoting de
     * `evaluateJavascript` (los strings JS llegan JSON-dentro-de-JSON).
     *
     * @throws IllegalArgumentException si el resultado no es un objeto JSON
     *   (p. ej. `"null"` cuando la página navegó y `__hermes` ya no existe).
     */
    private suspend fun evalHermes(call: String): JsonObject {
        val raw = driver.evaluateJavascript("window.__hermes.$call")
        return unwrapToJsonObject(raw, "window.__hermes.$call")
    }

    /** `__hermes.snapshot(full)` → texto (ya con truncado de seguridad) + element_count. */
    private suspend fun takeSnapshot(full: Boolean): SnapshotText {
        ensureHermes()
        val result = evalHermes("snapshot($full)")
        if (result["success"]?.jsonPrimitive?.booleanOrNull == false) {
            throw ActionFailedException(
                result["error"]?.jsonPrimitive?.contentOrNull?.take(MAX_ERROR_CHARS) ?: "Snapshot failed",
            )
        }
        val text =
            result["snapshot"]?.jsonPrimitive?.contentOrNull
                ?: throw ActionFailedException("Snapshot result is missing the \"snapshot\" field")
        val count = result["element_count"]?.jsonPrimitive?.intOrNull ?: 0
        return SnapshotText(SnapshotResult.truncate(text), count)
    }

    private data class SnapshotText(
        val text: String,
        val elementCount: Int,
    )

    // --------------------------------------------------------------- esperas ---

    /**
     * click/type/press/scroll (§5/F2): 300 ms fijos y luego comprobación de
     * navegación — si la acción abrió una página nueva, se espera a que cargue
     * (mismo criterio que navigate, dentro del presupuesto total del comando).
     */
    private suspend fun postActionSettle(events: Flow<WebViewPageEvent>) {
        delay(timeouts.actionSettleMs)
        settler.awaitSettled(events, timeouts.navSettleMs, peekForNavigationMs = timeouts.actionNavPeekMs)
    }

    /**
     * `onReceivedError` del frame principal → `ok:false` "Navigation failed:
     * <desc>" (sin él, un host caído devolvería ok:true con la página de error).
     */
    private fun navigationError(pageError: WebViewPageEvent.PageError): BrowserCommandOutcome {
        val reason = pageError.description.ifBlank { "unknown error" }.take(MAX_ERROR_CHARS)
        return BrowserCommandOutcome.failure("Navigation failed: $reason")
    }

    // -------------------------------------------------------------- helpers ---

    /** Excepción → motivo humano en inglés, una línea y acotado (va al modelo, §2.6). */
    private fun humanError(e: Exception): String {
        val raw =
            when (e) {
                is ActionFailedException -> e.message ?: "Action failed"
                is TimeoutCancellationException -> "Command timed out"
                else -> e.message?.takeIf { it.isNotBlank() } ?: "${e::class.simpleName} while running command"
            }
        return raw.replace('\n', ' ').take(MAX_ERROR_CHARS)
    }

    public companion object {
        /** Tamaño máximo del lado mayor de la captura PNG (§5/F2). */
        public const val SCREENSHOT_MAX_EDGE_PX: Int = 1_200

        /** El WebView de la app es de pestaña única (§2.6 `browser_tabs`). */
        public const val SINGLE_TAB_ID: String = "1"

        private const val NANOS_PER_MS = 1_000_000
        private const val MAX_ERROR_CHARS = 240
        private const val MAX_WIRE_TAG_CHARS = 64
    }
}
