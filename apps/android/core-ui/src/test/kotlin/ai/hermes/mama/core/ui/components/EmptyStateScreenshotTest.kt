package ai.hermes.mama.core.ui.components

import org.junit.Test
import org.robolectric.annotation.Config

/** Capturas Roborazzi de EmptyState: claro/oscuro × fuente 1.0×/1.3×. */
class EmptyStateScreenshotTest : ScreenshotTestBase() {
    @Test
    fun emptyState_light() = captureSheet("empty_state_light_font100.png", darkTheme = false) { EmptyStateSheet() }

    @Test
    @Config(sdk = [34], fontScale = 1.3f)
    fun emptyState_light_font130() =
        captureSheet("empty_state_light_font130.png", darkTheme = false) { EmptyStateSheet() }

    @Test
    fun emptyState_dark() = captureSheet("empty_state_dark_font100.png", darkTheme = true) { EmptyStateSheet() }

    @Test
    @Config(sdk = [34], fontScale = 1.3f)
    fun emptyState_dark_font130() = captureSheet("empty_state_dark_font130.png", darkTheme = true) { EmptyStateSheet() }
}
