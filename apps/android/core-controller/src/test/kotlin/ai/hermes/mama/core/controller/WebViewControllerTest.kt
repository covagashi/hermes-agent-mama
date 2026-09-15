@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ai.hermes.mama.core.controller

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [WebViewController] sobre [FakeWebView] (§5/F2):
 * - mapeo comando→JS (`window.__hermes.<fn>(args)` con quoting seguro),
 * - resultados §2.6 (ok/resultJson verbatim de las acciones JS),
 * - esperas de navigate (pageFinished + calma de red),
 * - timeout interno → `ok:false` y cancelación por `command_id`.
 *
 * Todo con reloj virtual (`runTest`): las esperas no cuestan tiempo real.
 */
class WebViewControllerTest {
    // ---------------------------------------------------------- mapeo JS ----

    @Test
    fun `click mapea a __hermes_click con el ref citado`() =
        runTest {
            val (controller, fake) = newController()
            val outcome = controller.execute(BrowserCommand.Click("c1", "@e5"))
            assertTrue(outcome.ok)
            assertTrue(fake.hermesCalls().contains("""window.__hermes.click("@e5")"""))
            // La inyección de hermes_snapshot.js va antes de la acción.
            assertTrue(fake.evaluatedScripts.first().startsWith(SCRIPT_PREFIX))
        }

    @Test
    fun `type escapa el texto como literal JS seguro`() =
        runTest {
            val (controller, fake) = newController()
            val outcome = controller.execute(BrowserCommand.Type("c1", "@e4", "di \"hola\"\ny adiós"))
            assertTrue(outcome.ok)
            val call = fake.hermesCalls().single()
            assertEquals("""window.__hermes.type("@e4", "di \"hola\"\ny adiós")""", call)
        }

    @Test
    fun `press y scroll mapean sus argumentos`() =
        runTest {
            val (controller, fake) = newController()
            controller.execute(BrowserCommand.Press("c1", "Enter"))
            controller.execute(BrowserCommand.Scroll("c2", "down"))
            val calls = fake.hermesCalls()
            assertTrue(calls.contains("""window.__hermes.press("Enter")"""))
            assertTrue(calls.contains("""window.__hermes.scroll("down")"""))
        }

    @Test
    fun `el resultado ok de la acción JS viaja verbatim`() =
        runTest {
            val (controller, fake) = newController()
            fake.responder =
                responder { script ->
                    when {
                        script.contains("click(") -> FakeWebView.jsResult("""{"success":true,"clicked":"@e5"}""")
                        else -> null
                    }
                }
            val outcome = controller.execute(BrowserCommand.Click("c1", "e5"))
            assertTrue(outcome.ok)
            val json = parse(outcome.resultJson)
            assertEquals(true, json["success"]?.jsonPrimitive?.booleanOrNull)
            assertEquals("@e5", json["clicked"]?.jsonPrimitive?.contentOrNull)
        }

    @Test
    fun `el fallo de la acción JS produce ok false con el motivo`() =
        runTest {
            val (controller, fake) = newController()
            fake.responder =
                responder { script ->
                    when {
                        script.contains("click(") ->
                            FakeWebView.jsResult(
                                """{"success":false,"error":"No element found for @e9 — page changed"}""",
                            )
                        else -> null
                    }
                }
            val outcome = controller.execute(BrowserCommand.Click("c1", "@e9"))
            assertFalse(outcome.ok)
            val json = parse(outcome.resultJson)
            assertEquals(false, json["success"]?.jsonPrimitive?.booleanOrNull)
            assertTrue(
                json["error"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    .orEmpty()
                    .contains("No element found"),
            )
        }

    // ------------------------------------------------------------- noop -----

    @Test
    fun `noop devuelve success true sin tocar el WebView`() =
        runTest {
            val (controller, fake) = newController()
            val outcome = controller.execute(BrowserCommand.Noop("c1"))
            assertTrue(outcome.ok)
            assertEquals("""{"success":true}""", outcome.resultJson)
            assertTrue(fake.evaluatedScripts.isEmpty())
        }

    // ---------------------------------------------------------- navigate ----

    @Test
    fun `navigate espera pageFinished y devuelve snapshot compacto`() =
        runTest {
            val (controller, fake) = newController()
            fake.responder =
                responder { script ->
                    when {
                        script.contains("snapshot(") ->
                            FakeWebView.jsResult(
                                buildJsonObject {
                                    put("success", true)
                                    put("snapshot", "- heading \"Mis pedidos\" [ref=e1]")
                                    put("element_count", 1)
                                    put("ref_count", 1)
                                }.toString(),
                            )
                        else -> null
                    }
                }
            val deferred =
                async {
                    controller.execute(
                        BrowserCommand.Navigate("c1", "https://hermes.example.invalid/orders.html"),
                    )
                }
            testScheduler.runCurrent()
            assertEquals(listOf("https://hermes.example.invalid/orders.html"), fake.loadedUrls)
            fake.emitPageStarted()
            fake.emitPageFinished()
            testScheduler.advanceUntilIdle()
            val outcome = deferred.await()
            assertTrue(outcome.ok)
            val json = parse(outcome.resultJson)
            assertEquals("https://hermes.example.invalid/orders.html", json["url"]?.jsonPrimitive?.contentOrNull)
            assertEquals("Fake Page", json["title"]?.jsonPrimitive?.contentOrNull)
            assertEquals("- heading \"Mis pedidos\" [ref=e1]", json["snapshot"]?.jsonPrimitive?.contentOrNull)
            assertEquals(1, json["element_count"]?.jsonPrimitive?.intOrNull)
        }

    @Test
    fun `navigate con PageError del frame principal devuelve ok false con motivo`() =
        runTest {
            val (controller, fake) = newController()
            val deferred =
                async {
                    controller.execute(
                        BrowserCommand.Navigate("c1", "https://hermes.example.invalid/caigo"),
                    )
                }
            testScheduler.runCurrent()
            fake.emitPageStarted()
            fake.emitPageError(description = "net::ERR_CONNECTION_REFUSED")
            testScheduler.advanceUntilIdle()
            val outcome = deferred.await()
            assertFalse(outcome.ok)
            assertTrue(outcome.resultJson.contains("Navigation failed"), outcome.resultJson)
            assertTrue(outcome.resultJson.contains("ERR_CONNECTION_REFUSED"), outcome.resultJson)
        }

    @Test
    fun `navigate sin pageFinished rinde al llegar al techo de 10s`() =
        runTest {
            val (controller, fake) = newController()
            fake.responder =
                responder { script ->
                    when {
                        script.contains("snapshot(") ->
                            FakeWebView.jsResult("""{"success":true,"snapshot":"- main","element_count":1}""")
                        else -> null
                    }
                }
            val deferred =
                async { controller.execute(BrowserCommand.Navigate("c1", "https://hermes.example.invalid/slow")) }
            testScheduler.runCurrent()
            // Nunca llega pageFinished: el presupuesto de settle se agota igualmente.
            testScheduler.advanceUntilIdle()
            val outcome = deferred.await()
            assertTrue(outcome.ok, "navigate debe rendir al presupuesto de settle, no al timeout de 25s")
        }

    // ------------------------------------------------------------ snapshot ---

    @Test
    fun `browser_snapshot devuelve snapshot y element_count`() =
        runTest {
            val (controller, fake) = newController()
            fake.responder =
                responder { script ->
                    when {
                        script.contains("snapshot(true)") ->
                            FakeWebView.jsResult(
                                """{"success":true,"snapshot":"- main\n  - text \"hola\"","element_count":2}""",
                            )
                        else -> null
                    }
                }
            val outcome = controller.execute(BrowserCommand.TakeSnapshot("c1", full = true))
            assertTrue(outcome.ok)
            assertTrue(fake.hermesCalls().contains("window.__hermes.snapshot(true)"))
            val json = parse(outcome.resultJson)
            assertTrue(
                json["snapshot"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    .orEmpty()
                    .contains("- text \"hola\""),
            )
            assertEquals(2, json["element_count"]?.jsonPrimitive?.intOrNull)
        }

    @Test
    fun `snapshot trunca por seguridad al limite de 15000`() =
        runTest {
            val (controller, fake) = newController()
            val bigLine = "- text \"${"x".repeat(100)}\""
            val big = List(200) { bigLine }.joinToString("\n")
            fake.responder =
                responder { script ->
                    when {
                        script.contains("snapshot(") ->
                            FakeWebView.jsResult(
                                buildJsonObject {
                                    put("success", true)
                                    put("snapshot", big)
                                    put("element_count", 200)
                                }.toString(),
                            )
                        else -> null
                    }
                }
            val outcome = controller.execute(BrowserCommand.TakeSnapshot("c1", full = false))
            assertTrue(outcome.ok, outcome.resultJson.take(400))
            val snap = parse(outcome.resultJson)["snapshot"]?.jsonPrimitive?.contentOrNull.orEmpty()
            assertTrue(snap.length <= SnapshotResult.MAX_SNAPSHOT_CHARS)
            assertTrue(snap.endsWith(SnapshotResult.TRUNCATION_MARKER), "tail=<${snap.takeLast(80)}>")
        }

    // ---------------------------------------------------------------- back ---

    @Test
    fun `back sin historial falla con motivo`() =
        runTest {
            val (controller, fake) = newController()
            fake.canGoBackValue = false
            val outcome = controller.execute(BrowserCommand.Back("c1"))
            assertFalse(outcome.ok)
            assertTrue(outcome.resultJson.contains("no previous page"))
            assertEquals(0, fake.goBackCalls)
        }

    @Test
    fun `back navega y devuelve la url final`() =
        runTest {
            val (controller, fake) = newController()
            fake.canGoBackValue = true
            fake.urlValue = "https://hermes.example.invalid/atras.html"
            val deferred = async { controller.execute(BrowserCommand.Back("c1")) }
            testScheduler.runCurrent()
            fake.emitPageFinished()
            testScheduler.advanceUntilIdle()
            val outcome = deferred.await()
            assertTrue(outcome.ok)
            assertEquals(1, fake.goBackCalls)
            assertEquals(
                "https://hermes.example.invalid/atras.html",
                parse(outcome.resultJson)["url"]?.jsonPrimitive?.contentOrNull,
            )
        }

    // ----------------------------------------------------------- screenshot --

    @Test
    fun `screenshot devuelve PNG base64 con dimensiones`() =
        runTest {
            val (controller, _) = newController()
            val outcome = controller.execute(BrowserCommand.Screenshot("c1"))
            assertTrue(outcome.ok)
            val json = parse(outcome.resultJson)
            val decoded =
                java.util.Base64
                    .getDecoder()
                    .decode(json["image_base64"]?.jsonPrimitive?.contentOrNull)
            assertTrue(decoded.contentEquals(FakeWebView.PNG_STUB))
            assertEquals(640, json["width"]?.jsonPrimitive?.intOrNull)
            assertEquals(480, json["height"]?.jsonPrimitive?.intOrNull)
        }

    // --------------------------------------------------------------- tabs ----

    @Test
    fun `tabs anuncia una sola pestaña activa`() =
        runTest {
            val (controller, _) = newController()
            val outcome = controller.execute(BrowserCommand.Tabs("c1"))
            assertTrue(outcome.ok)
            val json = parse(outcome.resultJson)
            assertTrue(json["tabs"].toString().contains("\"id\":\"1\""))
            assertTrue(json["tabs"].toString().contains("\"active\":true"))
        }

    @Test
    fun `tab_activate solo acepta la pestaña 1`() =
        runTest {
            val (controller, _) = newController()
            assertTrue(controller.execute(BrowserCommand.TabActivate("c1", "1")).ok)
            val fail = controller.execute(BrowserCommand.TabActivate("c2", "2"))
            assertFalse(fail.ok)
            assertTrue(fail.resultJson.contains("No tab found"))
        }

    // ------------------------------------------------- invalid/unsupported --

    @Test
    fun `comando invalid y unsupported producen ok false`() =
        runTest {
            val (controller, fake) = newController()
            val invalid =
                controller.execute(
                    BrowserCommand.Invalid("c1", "browser_navigate", "browser_navigate requires a \"url\" argument"),
                )
            assertFalse(invalid.ok)
            assertTrue(invalid.resultJson.contains("url"))
            val unsupported = controller.execute(BrowserCommand.Unsupported("c2", "browser_evaluate"))
            assertFalse(unsupported.ok)
            assertTrue(unsupported.resultJson.contains("Unsupported"))
            assertTrue(fake.evaluatedScripts.isEmpty(), "Invalid/Unsupported no deben evaluar JS")
        }

    // -------------------------------------------------------------- timeout --

    @Test
    fun `evaluateJavascript colgado cae al timeout interno de 25s`() =
        runTest {
            val (controller, fake) = newController()
            fake.responder = { _ -> throw AssertionError("no debe llamarse: simulamos cuelgue con delay en override") }
            val hanging =
                object : WebViewDriver by fake {
                    override suspend fun evaluateJavascript(script: String): String {
                        awaitCancellation() // WebView muerto: nunca responde
                    }
                }
            val ctrl =
                WebViewController(
                    hanging,
                    this,
                    nowMs = { testScheduler.currentTime },
                    scriptSource = { "/* stub */" },
                )
            val deferred = async { ctrl.execute(BrowserCommand.Click("c1", "@e1")) }
            testScheduler.advanceUntilIdle()
            val outcome = deferred.await()
            assertFalse(outcome.ok)
            assertTrue(outcome.resultJson.contains("timed out"), outcome.resultJson)
        }

    // ------------------------------------------------------------ cancel -----

    @Test
    fun `cancel por command_id aborta el comando en curso`() =
        runTest {
            val (controller, fake) = newController()
            val deferred =
                async { controller.execute(BrowserCommand.Navigate("c1", "https://hermes.example.invalid/")) }
            testScheduler.runCurrent()
            assertTrue(controller.cancel("c1"))
            testScheduler.advanceUntilIdle()
            val outcome = deferred.await()
            assertFalse(outcome.ok)
            assertTrue(outcome.resultJson.contains("cancelled"))
            assertTrue(fake.loadedUrls.isNotEmpty(), "el navigate ya había empezado al cancelar")
        }

    @Test
    fun `cancel de id desconocido devuelve false`() =
        runTest {
            val (controller, _) = newController()
            assertFalse(controller.cancel("no-existe"))
        }

    @Test
    fun `command_id duplicado se rechaza sin ejecutar`() =
        runTest {
            val (controller, _) = newController()
            val first = async { controller.execute(BrowserCommand.Navigate("dup", "https://hermes.example.invalid/")) }
            testScheduler.runCurrent()
            val second = controller.execute(BrowserCommand.Noop("dup"))
            assertFalse(second.ok)
            assertTrue(second.resultJson.contains("Duplicate command_id"))
            first.cancel()
            testScheduler.advanceUntilIdle()
        }

    @Test
    fun `execute con scope ya cancelado devuelve fallo en vez de colgar`() =
        runTest {
            val fake = FakeWebView()
            // Scope muerto: el job LAZY nunca corre su cuerpo — invokeOnCompletion
            // debe producir el resultado §2.6 igualmente.
            val deadScope = CoroutineScope(coroutineContext + Job()).also { it.cancel() }
            val ctrl =
                WebViewController(
                    fake,
                    deadScope,
                    nowMs = { testScheduler.currentTime },
                    scriptSource = { "/* stub */" },
                )
            val outcome = ctrl.execute(BrowserCommand.Noop("c1"))
            assertFalse(outcome.ok)
            assertTrue(outcome.resultJson.contains("cancelled"), outcome.resultJson)
        }

    // ------------------------------------------------------ serialización ---

    @Test
    fun `comandos concurrentes se serializan en el driver`() =
        runTest {
            val (controller, fake) = newController()
            val active = AtomicInteger(0)
            val maxSeen = AtomicInteger(0)
            fake.responder = { script ->
                if (script.startsWith("window.__hermes.")) {
                    maxSeen.set(maxOf(maxSeen.get(), active.incrementAndGet()))
                    delay(50) // sin execMutex, el otro comando entraría aquí
                    active.decrementAndGet()
                    FakeWebView.jsResult("""{"success":true}""")
                } else {
                    "null"
                }
            }
            val a = async { controller.execute(BrowserCommand.Click("c1", "@e1")) }
            val b = async { controller.execute(BrowserCommand.Press("c2", "Enter")) }
            testScheduler.advanceUntilIdle()
            assertTrue(a.await().ok)
            assertTrue(b.await().ok)
            assertEquals(1, maxSeen.get(), "el driver nunca debe ejecutar dos comandos a la vez")
        }

    // ------------------------------------------------------ post-action nav --

    @Test
    fun `accion que desencadena navegacion espera a que cargue`() =
        runTest {
            val (controller, fake) = newController()
            val deferred = async { controller.execute(BrowserCommand.Click("c1", "@e2")) }
            testScheduler.runCurrent()
            // La acción abre una página: el controlador debe esperar su carga.
            fake.emitPageStarted("https://hermes.example.invalid/destino")
            testScheduler.runCurrent()
            fake.emitPageFinished("https://hermes.example.invalid/destino")
            testScheduler.advanceUntilIdle()
            val outcome = deferred.await()
            assertTrue(outcome.ok)
        }

    @Test
    fun `navegacion empezada DURANTE el eval de la accion tambien se espera`() =
        runTest {
            val (controller, fake) = newController()
            fake.responder =
                responder { script ->
                    if (script.contains("click(")) {
                        // El click abre la página DURANTE el eval: la marca de
                        // eventos debe haberse tomado antes, o se filtraría.
                        fake.emitPageStarted("https://hermes.example.invalid/en-eval")
                        FakeWebView.jsResult("""{"success":true,"clicked":"@e2"}""")
                    } else {
                        null
                    }
                }
            val deferred = async { controller.execute(BrowserCommand.Click("c1", "@e2")) }
            testScheduler.runCurrent()
            // 300 ms de settle + peek ya consumidos: la carga debe seguir esperándose.
            testScheduler.advanceTimeBy(900)
            testScheduler.runCurrent()
            assertFalse(deferred.isCompleted, "la navegación iniciada en el eval debe esperarse")
            fake.emitPageFinished("https://hermes.example.invalid/en-eval")
            testScheduler.advanceUntilIdle()
            assertTrue(deferred.await().ok)
        }

    @Test
    fun `accion sin navegacion termina sin esperas extra`() =
        runTest {
            val (controller, _) = newController()
            val outcome = controller.execute(BrowserCommand.Click("c1", "@e2"))
            assertTrue(outcome.ok)
        }

    // ------------------------------------------------------------ helpers ----

    /** Construye controller+fake sobre el TestScope del runTest (reloj virtual). */
    private fun kotlinx.coroutines.test.TestScope.newController(): Pair<WebViewController, FakeWebView> {
        val fake = FakeWebView()
        val controller =
            WebViewController(
                fake,
                this,
                nowMs = { testScheduler.currentTime },
                scriptSource = { "/* hermes_snapshot stub */" },
            )
        return controller to fake
    }

    private fun parse(raw: String): JsonObject =
        kotlinx.serialization.json.Json
            .parseToJsonElement(raw) as JsonObject

    /**
     * Responder que deja pasar la inyección (`"null"`) y delega las llamadas
     * `window.__hermes.*` al bloque dado (null → éxito por defecto).
     */
    private fun responder(map: (String) -> String?): suspend (String) -> String =
        { script ->
            if (script.startsWith("window.__hermes.")) {
                map(script) ?: FakeWebView.jsResult("""{"success":true}""")
            } else {
                "null"
            }
        }

    private fun FakeWebView.hermesCalls(): List<String> = evaluatedScripts.filter { it.startsWith("window.__hermes.") }

    private companion object {
        const val SCRIPT_PREFIX = "/*"
    }
}
