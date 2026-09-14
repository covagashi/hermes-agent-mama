package ai.hermes.mama.core.ui.components

import ai.hermes.mama.core.ui.R
import ai.hermes.mama.core.ui.theme.MamaTheme
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.accessibility.AccessibilityChecks
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose UI Test con `AccessibilityChecks` (espresso-accessibility → ATF) en
 * Robolectric: cualquier violación de nivel ERROR (objetivos táctiles,
 * contraste, texto anunciable…) rompe el test. Además se aserta el mínimo de
 * 56 dp sobre los nodos táctiles concretos.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DesignSystemA11yTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun bigButton_isAccessible() {
        setSheet { BigButtonSheet() }
        runEspressoA11yCheck()
        composeRule
            .onNodeWithText(newChat)
            .assertHasClickAction()
            .assertHeightIsAtLeast(MIN_TOUCH_DP.dp)
    }

    @Test
    fun chatBubble_isAccessible() {
        setSheet { ChatBubbleSheet() }
        runEspressoA11yCheck()
        composeRule
            .onNodeWithContentDescription(listen)
            .assertHasClickAction()
            .assertHeightIsAtLeast(MIN_TOUCH_DP.dp)
    }

    @Test
    fun activityChip_isAccessible() {
        setSheet { ActivityChipSheet() }
        runEspressoA11yCheck()
    }

    @Test
    fun topBanner_isAccessible() {
        setSheet { TopBannerSheet() }
        runEspressoA11yCheck()
        composeRule.onNodeWithText(offlineBanner).assertHeightIsAtLeast(MIN_TOUCH_DP.dp)
    }

    @Test
    fun emptyState_isAccessible() {
        setSheet { EmptyStateSheet() }
        runEspressoA11yCheck()
        composeRule
            .onNodeWithText(newChat)
            .assertHasClickAction()
            .assertHeightIsAtLeast(MIN_TOUCH_DP.dp)
    }

    private fun setSheet(content: @Composable () -> Unit) {
        composeRule.setContent {
            MamaTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        content()
                    }
                }
            }
        }
    }

    /**
     * AccessibilityChecks.enable() engancha el validador de ATF a Espresso;
     * al evaluar la vista raíz recorre toda la jerarquía (incluidos los nodos
     * semánticos de Compose) y lanza si hay violaciones de nivel ERROR.
     */
    private fun runEspressoA11yCheck() {
        onView(isRoot()).check(matches(isDisplayed()))
    }

    companion object {
        private const val MIN_TOUCH_DP = 56

        private val newChat: String
            get() = testString(R.string.design_example_new_chat)
        private val listen: String
            get() = testString(R.string.design_listen_message)
        private val offlineBanner: String
            get() = testString(R.string.design_banner_offline)

        private fun testString(id: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(id)

        @JvmStatic
        @BeforeClass
        fun enableAccessibilityChecks() {
            AccessibilityChecks
                .enable()
                .setRunChecksFromRootView(true)
                .setThrowExceptionFor(AccessibilityCheckResult.AccessibilityCheckResultType.ERROR)
        }
    }
}
