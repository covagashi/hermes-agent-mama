package ai.hermes.mama.feature.browser

import ai.hermes.mama.core.controller.SnapshotResult
import ai.hermes.mama.core.controller.SnapshotScript
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * F1 — los mismos fixtures `.html` de `testing/fixtures/html/` que usa la suite
 * jsdom, ejecutados en un `WebView` real: `__hermes.snapshot(full)` debe
 * reproducir byte a byte los ficheros `.expected.txt` (Robolectric no ejecuta
 * JS de WebView; §5/F1).
 *
 * Los fixtures viajan como assets del APK de test (ver build.gradle.kts) y se
 * cargan con `loadDataWithBaseURL` sobre el dominio sintético
 * `hermes.example.invalid` — sin red.
 */
@RunWith(AndroidJUnit4::class)
class SnapshotWebViewTest {
    private var scenario: ActivityScenario<SnapshotTestActivity>? = null
    private var activity: SnapshotTestActivity? = null

    @Before
    fun setUp() {
        val sc = ActivityScenario.launch(SnapshotTestActivity::class.java)
        scenario = sc
        val ref = AtomicReference<SnapshotTestActivity>()
        sc.onActivity { ref.set(it) }
        activity = ref.get() ?: error("no se pudo lanzar SnapshotTestActivity")
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    @Test
    fun snapshotCompactoCoincideConExpected() {
        for (base in FIXTURES) {
            assertFixture(base, full = false, expectedAsset = "$base.expected.txt")
        }
    }

    @Test
    fun snapshotCompletoCoincideConExpected() {
        for (base in FIXTURES) {
            assertFixture(base, full = true, expectedAsset = "$base-full.expected.txt")
        }
    }

    private fun assertFixture(
        base: String,
        full: Boolean,
        expectedAsset: String,
    ) {
        val act = activity ?: error("sin activity")
        val html = readAsset("$base.html")
        val expected = readAsset(expectedAsset)
        val steps = readAssetOrNull("$base.steps.js")

        val latch = CountDownLatch(1)
        runOnMain {
            act.onPageFinished = { latch.countDown() }
            act.webView.loadDataWithBaseURL(FIXTURE_ORIGIN, html, "text/html", "utf-8", null)
        }
        assertTrue("onPageFinished no llegó para $base", latch.await(PAGE_TIMEOUT_S, TimeUnit.SECONDS))

        // Inyectar el script, ejecutar los pasos del fixture y tomar el snapshot.
        evaluate(act, SnapshotScript.load())
        if (steps != null) {
            evaluate(act, steps)
        }
        val raw = evaluate(act, "window.__hermes.snapshot($full)")
        val result = SnapshotResult.parse(raw)
        assertEquals("snapshot distinto a $expectedAsset", expected.trimEnd('\n'), result.text)
        assertTrue("$expectedAsset sin refs", result.refCount >= 1)
    }

    private fun runOnMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    /** evaluateJavascript es asíncrono: latch + referencia (sin Thread.sleep). */
    private fun evaluate(
        activity: SnapshotTestActivity,
        js: String,
    ): String {
        val latch = CountDownLatch(1)
        val out = AtomicReference<String>()
        runOnMain {
            activity.webView.evaluateJavascript(js) { value ->
                out.set(value ?: "null")
                latch.countDown()
            }
        }
        assertTrue("evaluateJavascript no respondió", latch.await(JS_TIMEOUT_S, TimeUnit.SECONDS))
        return out.get() ?: "null"
    }

    private fun readAsset(name: String): String =
        InstrumentationRegistry
            .getInstrumentation()
            .context.assets
            .open(name)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

    private fun readAssetOrNull(name: String): String? =
        try {
            readAsset(name)
        } catch (e: java.io.IOException) {
            null
        }

    private companion object {
        const val FIXTURE_ORIGIN = "https://hermes.example.invalid/"
        const val PAGE_TIMEOUT_S = 15L
        const val JS_TIMEOUT_S = 15L
        val FIXTURES = listOf("dialog", "form", "iframe", "login", "orders", "password")
    }
}
