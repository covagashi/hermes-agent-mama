package ai.hermes.mama.core.ui.components

import org.junit.Test
import org.robolectric.annotation.Config

/** Capturas Roborazzi de TopBanner: claro/oscuro × fuente 1.0×/1.3×. */
class TopBannerScreenshotTest : ScreenshotTestBase() {
    @Test
    fun topBanner_light() = captureSheet("top_banner_light_font100.png", darkTheme = false) { TopBannerSheet() }

    @Test
    @Config(sdk = [34], fontScale = 1.3f)
    fun topBanner_light_font130() = captureSheet("top_banner_light_font130.png", darkTheme = false) { TopBannerSheet() }

    @Test
    fun topBanner_dark() = captureSheet("top_banner_dark_font100.png", darkTheme = true) { TopBannerSheet() }

    @Test
    @Config(sdk = [34], fontScale = 1.3f)
    fun topBanner_dark_font130() = captureSheet("top_banner_dark_font130.png", darkTheme = true) { TopBannerSheet() }
}
