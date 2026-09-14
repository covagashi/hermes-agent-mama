package ai.hermes.mama.core.ui.components

import ai.hermes.mama.core.ui.theme.MamaTheme
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Base de las capturas Roborazzi de C1: cada componente se fotografía sobre un
 * Surface de 390 dp de ancho (ancho de los mockups) en claro/oscuro y con
 * fuente 1.0×/1.3×. Los PNG caen en `apps/android/screenshots/`.
 */
private const val SHEET_TAG = "component_sheet"

/** Ancho de los mockups de design/mockups. */
private val MockupWidth = 390.dp

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// w390dp: sin él, la pantalla de Robolectric es 320 dp y el Surface de
// MockupWidth quedaría coaccionado — los goldens deben ser 390 dp reales.
@Config(sdk = [34], qualifiers = "w390dp")
abstract class ScreenshotTestBase {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    protected fun captureSheet(
        fileName: String,
        darkTheme: Boolean,
        content: @Composable () -> Unit,
    ) {
        composeRule.setContent {
            MamaTheme(darkTheme = darkTheme) {
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    modifier =
                        Modifier
                            .width(MockupWidth)
                            .testTag(SHEET_TAG),
                ) {
                    ComponentSheet(content)
                }
            }
        }
        composeRule.onNodeWithTag(SHEET_TAG).captureRoboImage(fileName)
    }
}
