package ai.hermes.mama.core.ui.components

import org.junit.Test
import org.robolectric.annotation.Config

/** Capturas Roborazzi de ChatBubble: claro/oscuro × fuente 1.0×/1.3×. */
class ChatBubbleScreenshotTest : ScreenshotTestBase() {
    @Test
    fun chatBubble_light() = captureSheet("chat_bubble_light_font100.png", darkTheme = false) { ChatBubbleSheet() }

    @Test
    @Config(sdk = [34], fontScale = 1.3f)
    fun chatBubble_light_font130() =
        captureSheet("chat_bubble_light_font130.png", darkTheme = false) { ChatBubbleSheet() }

    @Test
    fun chatBubble_dark() = captureSheet("chat_bubble_dark_font100.png", darkTheme = true) { ChatBubbleSheet() }

    @Test
    @Config(sdk = [34], fontScale = 1.3f)
    fun chatBubble_dark_font130() = captureSheet("chat_bubble_dark_font130.png", darkTheme = true) { ChatBubbleSheet() }
}
