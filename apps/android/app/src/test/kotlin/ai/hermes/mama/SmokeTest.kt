package ai.hermes.mama

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Smoke test JVM (Robolectric): la app arranca y, sin credenciales guardadas,
 * muestra la pantalla Conexión de C2 (gating de `mama`/`dev` sin `fake_script`).
 *
 * [FakeHermesMamaApp] sustituye el SecureStore cifrado por uno en memoria —
 * `AndroidKeyStore` no existe en JVM (ver B3).
 *
 * Robolectric + las reglas oficiales de Compose UI Test usan JUnit4; los módulos
 * JVM/feature usan JUnit 5 (ROADMAP §4).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FakeHermesMamaApp::class)
class SmokeTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `primer arranque sin credenciales muestra la pantalla Conexion`() {
        composeTestRule.onNodeWithText("Conectar con Hermes").assertIsDisplayed()
    }
}
