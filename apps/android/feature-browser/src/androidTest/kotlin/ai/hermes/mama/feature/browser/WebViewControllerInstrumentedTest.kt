package ai.hermes.mama.feature.browser

import ai.hermes.mama.core.controller.BrowserCommandOutcome
import ai.hermes.mama.core.controller.WebViewController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * F2 — `WebViewController` sobre un `WebView` real (`AndroidWebViewDriver`):
 * `FakeCommandSource` (stand-in de FakeGateway/ControllerSession) envía
 * `navigate`→`snapshot`→`click`→… sobre las fixtures HTML servidas por
 * `MockWebServer` y comprueba los resultados §2.6 (§5/F2).
 *
 * Los fixtures viajan como assets del APK de test (build.gradle.kts); el
 * dispatcher sirve `*.html` por nombre y responde la misma `orders.html` a
 * cualquier otra ruta (destinos de los links del fixture).
 */
@RunWith(AndroidJUnit4::class)
class WebViewControllerInstrumentedTest {
    private var scenario: ActivityScenario<ControllerTestActivity>? = null
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var controller: WebViewController
    private lateinit var commands: FakeCommandSource

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    val name = FIXTURES.firstOrNull { path.endsWith("/$it.html") || path == "/$it.html" }
                    val body = readAsset("${name ?: "orders"}.html")
                    return MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "text/html; charset=utf-8")
                        .setBody(body)
                }
            }
        server.start()

        val sc = ActivityScenario.launch(ControllerTestActivity::class.java)
        scenario = sc
        val ref = AtomicReference<ControllerTestActivity>()
        sc.onActivity { ref.set(it) }
        val activity = ref.get() ?: error("no se pudo lanzar ControllerTestActivity")

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        controller = WebViewController(activity.driver, scope)
        commands = FakeCommandSource(controller)
    }

    @After
    fun tearDown() {
        controller.cancelAll()
        scope.cancel()
        scenario?.close()
        server.shutdown()
    }

    // --------------------------------------------------------------- tests --

    @Test
    fun navigateDevuelveResultado26ConSnapshotCompacto() =
        runBlocking {
            val outcome = send("browser_navigate", commands.args("url" to url("/orders.html")))
            val json = assertOk(outcome)
            assertTrue(
                json["url"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    .orEmpty()
                    .endsWith("/orders.html"),
            )
            assertEquals("Mis pedidos — Tienda Ejemplo", json["title"]?.jsonPrimitive?.contentOrNull)
            val snapshot = json["snapshot"]?.jsonPrimitive?.contentOrNull.orEmpty()
            assertTrue(snapshot.contains("""heading "Mis pedidos""""))
            assertTrue(json["element_count"]?.jsonPrimitive?.intOrNull != null)
        }

    @Test
    fun navigateAEsquemaNoHttpDevuelveInvalidSinTocarElWebView() =
        runBlocking {
            send("browser_navigate", commands.args("url" to url("/orders.html")))
            val outcome = send("browser_navigate", commands.args("url" to "javascript:alert(1)"))
            assertFalse(outcome.ok)
            assertTrue(outcome.resultJson.contains("http(s)"))
            // El WebView siguió en la página anterior: el esquema ni llegó al driver.
            val tabs = assertOk(send("browser_tabs"))
            assertTrue(tabs["tabs"].toString().contains("/orders.html"))
        }

    @Test
    fun navigateAHostInexistenteDevuelveOkFalse() =
        runBlocking {
            // `.invalid` nunca resuelve (RFC 2606) → onReceivedError del frame
            // principal → "Navigation failed: net::ERR_*" en vez de ok:true.
            val outcome =
                send("browser_navigate", commands.args("url" to "https://hermes.example.invalid/"))
            assertFalse(outcome.ok)
            assertTrue(outcome.resultJson, outcome.resultJson.contains("Navigation failed"))
        }

    @Test
    fun snapshotCoincideConElGoldenCompacto() =
        runBlocking {
            send("browser_navigate", commands.args("url" to url("/orders.html")))
            val outcome = send("browser_snapshot")
            val json = assertOk(outcome)
            val expected = readAsset("orders.expected.txt")
            assertEquals(expected.trimEnd('\n'), json["snapshot"]?.jsonPrimitive?.contentOrNull)
        }

    @Test
    fun clickEnBotonDevuelveClicked() =
        runBlocking {
            send("browser_navigate", commands.args("url" to url("/orders.html")))
            val ref = findRef("button", "Descargar factura")
            val outcome = send("browser_click", commands.args("ref" to ref))
            val json = assertOk(outcome)
            assertEquals(ref, json["clicked"]?.jsonPrimitive?.contentOrNull)
        }

    @Test
    fun clickEnEnlaceDesencadenaNavegacionYSeEspera() =
        runBlocking {
            send("browser_navigate", commands.args("url" to url("/orders.html")))
            val ref = findRef("link", "Pedido 403-1234567")
            val outcome = send("browser_click", commands.args("ref" to ref))
            val json = assertOk(outcome)
            assertEquals(ref, json["clicked"]?.jsonPrimitive?.contentOrNull)
            // La navegación provocada se esperó: la URL actual ya es la destino.
            val tabs = assertOk(send("browser_tabs"))
            assertTrue(tabs["tabs"].toString().contains("/pedidos/403-1234567"))
        }

    @Test
    fun clickEnElementoDeshabilitadoFallaConMotivo() =
        runBlocking {
            send("browser_navigate", commands.args("url" to url("/orders.html")))
            val ref = findDisabledButtonRef()
            val outcome = send("browser_click", commands.args("ref" to ref))
            assertFalse(outcome.ok)
            assertTrue(outcome.resultJson.contains("disabled"))
        }

    @Test
    fun typeEnTextboxYRechazoEnCombobox() =
        runBlocking {
            send("browser_navigate", commands.args("url" to url("/orders.html")))
            val textbox = findRef("textbox", "Buscar")
            val typed = assertOk(send("browser_type", commands.args("ref" to textbox, "text" to "pedido 403")))
            assertEquals("pedido 403", typed["typed"]?.jsonPrimitive?.contentOrNull)
            assertEquals(textbox, typed["element"]?.jsonPrimitive?.contentOrNull)

            val combo = findRef("combobox", "Ordenar por")
            val fail = send("browser_type", commands.args("ref" to combo, "text" to "x"))
            assertFalse(fail.ok)
            assertTrue(fail.resultJson.contains("not a text field"))
        }

    @Test
    fun scrollYPressDevuelvenExito() =
        runBlocking {
            send("browser_navigate", commands.args("url" to url("/orders.html")))
            val scrolled = assertOk(send("browser_scroll", commands.args("direction" to "down")))
            assertEquals("down", scrolled["scrolled"]?.jsonPrimitive?.contentOrNull)
            val pressed = assertOk(send("browser_press", commands.args("key" to "Escape")))
            assertEquals("Escape", pressed["pressed"]?.jsonPrimitive?.contentOrNull)
        }

    @Test
    fun backDevuelveLaUrlAnterior() =
        runBlocking {
            send("browser_navigate", commands.args("url" to url("/orders.html")))
            send("browser_navigate", commands.args("url" to url("/form.html")))
            val outcome = send("browser_back")
            val json = assertOk(outcome)
            assertTrue(
                json["url"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    .orEmpty()
                    .endsWith("/orders.html"),
            )
        }

    @Test
    fun screenshotDevuelvePngBase64Acotado() =
        runBlocking {
            send("browser_navigate", commands.args("url" to url("/orders.html")))
            val outcome = send("browser_screenshot")
            val json = assertOk(outcome)
            val bytes =
                android.util.Base64.decode(
                    json["image_base64"]?.jsonPrimitive?.contentOrNull,
                    android.util.Base64.DEFAULT,
                )
            assertNotNull(bytes)
            // Firma PNG \x89PNG\r\n\x1a\n
            assertEquals(0x89.toByte(), bytes[0])
            assertEquals(0x50.toByte(), bytes[1])
            assertEquals(0x4E.toByte(), bytes[2])
            assertEquals(0x47.toByte(), bytes[3])
            val w = json["width"]?.jsonPrimitive?.intOrNull ?: 0
            val h = json["height"]?.jsonPrimitive?.intOrNull ?: 0
            assertTrue("width debe ser >0", w > 0)
            assertTrue("height debe ser >0", h > 0)
            assertTrue("lado mayor ≤ 1200", maxOf(w, h) <= WebViewController.SCREENSHOT_MAX_EDGE_PX)
        }

    @Test
    fun tabsYTabActivateDeUnaSolaPestana() =
        runBlocking {
            send("browser_navigate", commands.args("url" to url("/orders.html")))
            val tabs = assertOk(send("browser_tabs"))
            assertTrue(tabs["tabs"].toString().contains("\"active\":true"))
            assertTrue(tabs["tabs"].toString().contains("/orders.html"))
            assertOk(send("browser_tab_activate", commands.args("id" to "1")))
            assertFalse(send("browser_tab_activate", commands.args("id" to "2")).ok)
        }

    @Test
    fun noopUnsupportedEInvalid() =
        runBlocking {
            val noop = assertOk(send("controller.noop"))
            assertEquals(true, noop["success"]?.jsonPrimitive?.booleanOrNull)
            assertFalse(send("browser_evaluate").ok)
            assertFalse(send("browser_navigate").ok) // falta url → Invalid
        }

    @Test
    fun cancelPorCommandIdAbortaElNavigate() =
        runBlocking {
            // Endpoint que nunca responde: la página queda cargando sin
            // pageFinished y el handler del servidor termina enseguida (un
            // body-delay largo impediría el shutdown en tearDown).
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        if (request.path.orEmpty().endsWith("/slow.html")) {
                            MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                        } else {
                            MockResponse()
                                .setResponseCode(200)
                                .setHeader("Content-Type", "text/html; charset=utf-8")
                                .setBody(readAsset("orders.html"))
                        }
                }
            val payload = commands.command("browser_navigate", commands.args("url" to url("/slow.html")))
            val outcome = AtomicReference<BrowserCommandOutcome?>()
            val done = CountDownLatch(1)
            scope.launch {
                outcome.set(commands.send(payload))
                done.countDown()
            }
            // Determinista: el request llegó al servidor → la navegación está en curso.
            server.takeRequest(REQUEST_TIMEOUT_S, TimeUnit.SECONDS)
            assertTrue("cancel debe encontrar el comando en curso", controller.cancel(payload.commandId))
            assertTrue("execute no terminó tras cancel", done.await(RESULT_TIMEOUT_S, TimeUnit.SECONDS))
            val result = outcome.get()
            assertNotNull(result)
            assertFalse(checkNotNull(result).ok)
        }

    // ------------------------------------------------------------- helpers ---

    private suspend fun send(
        action: String,
        args: kotlinx.serialization.json.JsonObject = EMPTY_ARGS,
    ): BrowserCommandOutcome = commands.send(action, args)

    private fun assertOk(outcome: BrowserCommandOutcome): JsonObject {
        assertTrue("esperaba ok:true, llegó: ${outcome.resultJson.take(300)}", outcome.ok)
        return parse(outcome.resultJson)
    }

    private fun parse(raw: String): JsonObject =
        kotlinx.serialization.json.Json
            .parseToJsonElement(raw) as JsonObject

    /** Extrae `@eN` del snapshot compacto por rol+nombre (vía browser_snapshot). */
    private suspend fun findRef(
        role: String,
        nameContains: String,
    ): String {
        val json = assertOk(send("browser_snapshot"))
        val snapshot = json["snapshot"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val regex = Regex("""- $role "[^"]*$nameContains[^"]*" \[ref=e(\d+)\]""")
        val match = regex.find(snapshot) ?: error("no se encontró $role \"$nameContains\" en:\n$snapshot")
        return "@e${match.groupValues[1]}"
    }

    /** El segundo botón "Descargar factura" del fixture está `[disabled]`. */
    private suspend fun findDisabledButtonRef(): String {
        val json = assertOk(send("browser_snapshot"))
        val snapshot = json["snapshot"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val regex = Regex("""- button "Descargar factura" \[ref=e(\d+)\] \[disabled\]""")
        val match = regex.find(snapshot) ?: error("no se encontró el botón disabled en:\n$snapshot")
        return "@e${match.groupValues[1]}"
    }

    private fun url(path: String): String = server.url(path).toString()

    private fun readAsset(name: String): String =
        InstrumentationRegistry
            .getInstrumentation()
            .context.assets
            .open(name)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

    private companion object {
        val FIXTURES = listOf("orders", "form", "login", "password", "dialog", "iframe")
        const val REQUEST_TIMEOUT_S = 10L
        const val RESULT_TIMEOUT_S = 20L
        val EMPTY_ARGS = kotlinx.serialization.json.buildJsonObject {}
    }
}
