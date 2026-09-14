package ai.hermes.mama.core.ui.components

import org.junit.Test
import org.robolectric.annotation.Config

/** Capturas Roborazzi de ActivityChip: claro/oscuro × fuente 1.0×/1.3×. */
class ActivityChipScreenshotTest : ScreenshotTestBase() {
    @Test
    fun activityChip_light() =
        captureSheet("activity_chip_light_font100.png", darkTheme = false) { ActivityChipSheet() }

    @Test
    @Config(sdk = [34], fontScale = 1.3f)
    fun activityChip_light_font130() =
        captureSheet("activity_chip_light_font130.png", darkTheme = false) { ActivityChipSheet() }

    @Test
    fun activityChip_dark() = captureSheet("activity_chip_dark_font100.png", darkTheme = true) { ActivityChipSheet() }

    @Test
    @Config(sdk = [34], fontScale = 1.3f)
    fun activityChip_dark_font130() =
        captureSheet("activity_chip_dark_font130.png", darkTheme = true) { ActivityChipSheet() }
}
