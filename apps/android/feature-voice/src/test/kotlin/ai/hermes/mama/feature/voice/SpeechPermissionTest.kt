package ai.hermes.mama.feature.voice

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Tests JVM (Robolectric) del checker de permiso real por el camino
 * [SpeechInput.create]: `ContextCompat.checkSelfPermission(RECORD_AUDIO)`.
 */
@RunWith(RobolectricTestRunner::class)
class SpeechPermissionTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `permiso denegado emite Error PermissionDenied sin tocar el recognizer`() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val input = SpeechInput.create(app)

        input.startListening()

        val state = input.state.value
        assertTrue(state is SpeechState.Error)
        assertEquals(SpeechErrorKind.PermissionDenied, (state as SpeechState.Error).kind)
    }

    @Test
    fun `hasAudioPermission refleja el permiso de runtime`() {
        val input = SpeechInput.create(app)

        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertFalse(input.hasAudioPermission())

        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        assertTrue(input.hasAudioPermission())
    }
}
