package ai.hermes.mama.core.ui.components

import org.junit.Test
import org.robolectric.annotation.Config

/** Capturas Roborazzi de BigButton: claro/oscuro × fuente 1.0×/1.3×. */
class BigButtonScreenshotTest : ScreenshotTestBase() {
    @Test
    fun bigButton_light() = captureSheet("big_button_light_font100.png", darkTheme = false) { BigButtonSheet() }

    @Test
    @Config(sdk = [34], fontScale = 1.3f)
    fun bigButton_light_font130() = captureSheet("big_button_light_font130.png", darkTheme = false) { BigButtonSheet() }

    @Test
    fun bigButton_dark() = captureSheet("big_button_dark_font100.png", darkTheme = true) { BigButtonSheet() }

    @Test
    @Config(sdk = [34], fontScale = 1.3f)
    fun bigButton_dark_font130() = captureSheet("big_button_dark_font130.png", darkTheme = true) { BigButtonSheet() }
}
