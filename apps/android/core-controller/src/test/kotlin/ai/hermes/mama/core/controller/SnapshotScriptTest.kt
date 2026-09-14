package ai.hermes.mama.core.controller

import kotlin.test.Test
import kotlin.test.assertTrue

/** `hermes_snapshot.js` debe viajar en el classpath para inyectarlo en el WebView. */
class SnapshotScriptTest {
    @Test
    fun `el script está en el classpath y expone __hermes`() {
        val js = SnapshotScript.load()
        assertTrue(js.contains("window.__hermes"))
        assertTrue(js.contains("snapshot"))
        assertTrue(js.contains("[truncated]"))
    }
}
