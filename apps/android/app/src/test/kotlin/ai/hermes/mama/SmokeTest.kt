package ai.hermes.mama

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Smoke test JVM (Robolectric): la app arranca y muestra el nombre "Hermes".
 *
 * Robolectric + las reglas oficiales de Compose UI Test usan JUnit4; los módulos
 * JVM/feature usan JUnit 5 (ROADMAP §4).
 */
@RunWith(RobolectricTestRunner::class)
class SmokeTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun showsAppName() {
        val appName = composeTestRule.activity.getString(R.string.app_name)
        composeTestRule.onNodeWithText(appName).assertIsDisplayed()
    }
}
