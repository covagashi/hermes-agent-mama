package ai.hermes.mama

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Mecanismo `fake_script` del flavor dev (README §FakeGateway): con el extra
 * presente, el endpoint dev ws://10.0.2.2:8399 queda registrado para que la
 * conexión (C2/B5) lo use en lugar de las credenciales guardadas.
 */
@RunWith(RobolectricTestRunner::class)
class FakeScriptTest {
    @Test
    fun fakeScriptExtraEnablesDevEndpoint() {
        try {
            DevGateway.onNewIntent(Intent().putExtra(DevGateway.EXTRA_FAKE_SCRIPT, "hola_basico"))

            assertEquals("hola_basico", DevGateway.requestedScript)
            assertEquals("ws://10.0.2.2:8399", DevGateway.endpointOverride)
        } finally {
            DevGateway.onNewIntent(null)
        }
    }

    @Test
    fun missingExtraClearsOverride() {
        DevGateway.onNewIntent(Intent().putExtra(DevGateway.EXTRA_FAKE_SCRIPT, "hola_basico"))
        DevGateway.onNewIntent(Intent())

        assertNull(DevGateway.requestedScript)
        assertNull(DevGateway.endpointOverride)
    }
}
