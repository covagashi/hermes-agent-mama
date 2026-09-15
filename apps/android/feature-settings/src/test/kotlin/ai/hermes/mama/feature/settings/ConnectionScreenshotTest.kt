package ai.hermes.mama.feature.settings

import ai.hermes.mama.core.ui.theme.MamaTheme
import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val SCREEN_TAG = "connection_screen"

/**
 * Capturas Roborazzi de la pantalla Conexión (C2) contra
 * `design/mockups/Conexion.dc.html`: 390×844 dp, fuente 100/130/200 % y
 * claro/oscuro → `apps/android/screenshots/c2-conexion-*.png`.
 *
 * El estado fotografiado es el del mockup (formulario relleno, toggle activo y
 * banner "Conectado. Hermes está listo.") — el estado feliz tras "Probar".
 * A fuente 200 % el formulario excede la pantalla: se captura lo visible con
 * scroll, como en las capturas de `:feature-chat`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// w390dp-h844dp: las medidas del mockup; sin ellas Robolectric usaría 320×470.
@Config(sdk = [34], qualifiers = "w390dp-h844dp")
class ConnectionScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun conexion_light_font100() = capture("c2-conexion-100.png", darkTheme = false)

    @Test
    @Config(sdk = [34], qualifiers = "w390dp-h844dp", fontScale = 1.3f)
    fun conexion_light_font130() = capture("c2-conexion-130.png", darkTheme = false)

    @Test
    @Config(sdk = [34], qualifiers = "w390dp-h844dp", fontScale = 2.0f)
    fun conexion_light_font200() = capture("c2-conexion-200.png", darkTheme = false)

    @Test
    fun conexion_dark_font100() = capture("c2-conexion-100-dark.png", darkTheme = true)

    @Test
    @Config(sdk = [34], qualifiers = "w390dp-h844dp", fontScale = 1.3f)
    fun conexion_dark_font130() = capture("c2-conexion-130-dark.png", darkTheme = true)

    @Test
    @Config(sdk = [34], qualifiers = "w390dp-h844dp", fontScale = 2.0f)
    fun conexion_dark_font200() = capture("c2-conexion-200-dark.png", darkTheme = true)

    private fun capture(
        fileName: String,
        darkTheme: Boolean,
    ) {
        composeRule.setContent {
            MamaTheme(darkTheme = darkTheme) {
                ConnectionContent(
                    state = mockupState(),
                    onServerChange = {},
                    onUsernameChange = {},
                    onPasswordChange = {},
                    onTogglePasswordVisibility = {},
                    onReadAloudChange = {},
                    onTest = {},
                    onSave = {},
                    modifier = Modifier.testTag(SCREEN_TAG),
                )
            }
        }
        composeRule.onNodeWithTag(SCREEN_TAG).captureRoboImage(fileName)
    }

    /** El estado del mockup: campos rellenos, voz activada, banner "Conectado". */
    private fun mockupState() =
        ConnectionUiState(
            server = "hermes.example.invalid",
            username = "usuario",
            password = "mamamamama",
            readAloud = true,
            banner = ConnectionBanner.Connected(displayName = null),
        )
}
