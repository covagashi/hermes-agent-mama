package ai.hermes.mama.feature.voice

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.speech.RecognizerIntent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Tests JVM (Robolectric) del fallback [RecognizerIntentFallback] (D1): el intent de
 * dictado del sistema para móviles sin servicio de reconocimiento.
 */
@RunWith(RobolectricTestRunner::class)
class RecognizerIntentFallbackTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `buildIntent pide dictado libre en es-ES con prompt humano`() {
        val intent = RecognizerIntentFallback.buildIntent(context)

        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, intent.action)
        assertEquals(
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL),
        )
        assertEquals("es-ES", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertEquals(
            context.getString(R.string.voice_fallback_prompt),
            intent.getStringExtra(RecognizerIntent.EXTRA_PROMPT),
        )
        assertEquals(1, intent.getIntExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 0))
    }

    @Test
    fun `isAvailable es false si ninguna app responde al intent`() {
        assertFalse(RecognizerIntentFallback.isAvailable(context))
    }

    @Suppress("DEPRECATION") // API de ShadowPackageManager; la alternativa es instalar un paquete falso.
    @Test
    fun `isAvailable es true si una app instalada responde al intent`() {
        val resolveInfo =
            ResolveInfo().apply {
                activityInfo =
                    ActivityInfo().apply {
                        packageName = "com.example.dictado"
                        name = "DictadoActivity"
                    }
            }
        shadowOf(context.packageManager)
            .addResolveInfoForIntent(RecognizerIntentFallback.buildIntent(context), listOf(resolveInfo))

        assertTrue(RecognizerIntentFallback.isAvailable(context))
    }

    @Test
    fun `extractText devuelve la primera hipótesis`() {
        val data =
            Intent().putStringArrayListExtra(
                RecognizerIntent.EXTRA_RESULTS,
                arrayListOf("hola mamá", "ola mamá"),
            )

        assertEquals("hola mamá", RecognizerIntentFallback.extractText(data))
    }

    @Test
    fun `extractText devuelve null si no hay resultados o están vacíos`() {
        assertNull(RecognizerIntentFallback.extractText(null))
        assertNull(RecognizerIntentFallback.extractText(Intent()))
        assertNull(
            RecognizerIntentFallback.extractText(
                Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf("  ")),
            ),
        )
    }
}
