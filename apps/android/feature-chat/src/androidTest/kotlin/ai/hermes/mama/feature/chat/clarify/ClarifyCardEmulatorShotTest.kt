package ai.hermes.mama.feature.chat.clarify

import ai.hermes.mama.core.ui.components.ChatBubble
import ai.hermes.mama.core.ui.components.ChatBubbleAuthor
import ai.hermes.mama.core.ui.theme.MamaTheme
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

private const val SHOT_TAG = "c7_shot"

/** Tamaño del mockup Pregunta.dc.html (390×844 pt) en dp. */
private val MockupWidth = 390.dp
private val MockupHeight = 844.dp

/**
 * Capturas de validación visual de C7 en el emulador (validación manual del
 * roadmap — los goldens de CI son los Roborazzi de `ClarifyCardScreenshotTest`).
 *
 * Renderiza la hoja de pregunta sobre un chat de mentira al tamaño exacto del
 * mockup (390×844 dp) y graba PNGs en `Android/data/<pkg>.test/files/c7/` del
 * dispositivo; `adb pull` los trae a `apps/android/screenshots/`:
 *
 *     adb pull /sdcard/Android/data/ai.hermes.mama.feature.chat.test/files/c7
 *
 * Escalas de fuente vía `LocalDensity` (equivalente al fontScale del sistema,
 * sin reiniciar el emulador): c7-pregunta-{100,130,200}{,-dark}.png.
 */
@RunWith(AndroidJUnit4::class)
class ClarifyCardEmulatorShotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun capturasC7() {
        val outDir =
            File(
                InstrumentationRegistry
                    .getInstrumentation()
                    .targetContext
                    .getExternalFilesDir(null),
                "c7",
            ).apply { mkdirs() }

        data class Variant(
            val name: String,
            val fontScale: Float,
            val dark: Boolean,
            val state: ClarifyCardState,
        )

        // Args posicionales: una data class local no admite named args.
        val variants =
            listOf(
                Variant("c7-pregunta-100.png", 1.0f, false, choicesState()),
                Variant("c7-pregunta-130.png", 1.3f, false, choicesState()),
                Variant("c7-pregunta-200.png", 2.0f, false, choicesState()),
                Variant("c7-pregunta-100-dark.png", 1.0f, true, choicesState()),
                Variant("c7-pregunta-130-dark.png", 1.3f, true, choicesState()),
                Variant("c7-pregunta-200-dark.png", 2.0f, true, choicesState()),
                Variant("c7-pregunta-multi-100.png", 1.0f, false, multiState()),
                Variant("c7-pregunta-libre-100.png", 1.0f, false, freeTextState()),
            )

        // setContent sólo admite una llamada por test: la variante vive en un
        // estado observable y cada captura recompone la escena.
        var current by mutableStateOf(variants.first())
        composeRule.setContent {
            MamaTheme(darkTheme = current.dark) {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, current.fontScale),
                ) {
                    MockupScene(state = current.state)
                }
            }
        }

        for (variant in variants) {
            composeRule.runOnIdle { current = variant }
            composeRule.waitForIdle()
            val bitmap = composeRule.onNodeWithTag(SHOT_TAG).captureToImage().asAndroidBitmap()
            File(outDir, variant.name).outputStream().use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            assertTrue("captura ${variant.name} no se grabó", File(outDir, variant.name).isFile)
        }
        // El path queda en logcat para el `adb pull` de la validación manual.
        println("C7 capturas en: ${outDir.absolutePath}")
    }
}

/** Escena del mockup: chat de fondo + velo + la hoja de pregunta abajo. */
@Composable
private fun MockupScene(state: ClarifyCardState) {
    Surface(
        modifier =
            Modifier
                .width(MockupWidth)
                .height(MockupHeight)
                .testTag(SHOT_TAG),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ChatBubble(text = "Hermes está listo. ¿En qué te ayudo?", author = ChatBubbleAuthor.Hermes)
                ChatBubble(text = "Quiero preparar la comida del domingo", author = ChatBubbleAuthor.User)
                ChatBubble(text = "Perfecto. Te hago unas preguntas rápidas.", author = ChatBubbleAuthor.Hermes)
            }
            ClarifyOverlay(
                state = state,
                onAnswer = {},
                onAnswerMulti = {},
                speech = null,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

private fun choicesState() =
    ClarifyCardState(
        key = "shot",
        question = "¿En qué tienda compraste la lavadora?",
        input =
            ClarifyInput.Choices(
                options =
                    listOf(
                        ClarifyOption(
                            display = "Tienda Ejemplo (3 de septiembre)",
                            wire = "Tienda Ejemplo (3 de septiembre) (Recommended)",
                            recommended = true,
                        ),
                        ClarifyOption(
                            display = "Electro Casa (28 de agosto)",
                            wire = "Electro Casa (28 de agosto)",
                        ),
                        ClarifyOption(display = "No me acuerdo", wire = "No me acuerdo"),
                    ),
            ),
        progress = ClarifyProgress(current = 1, total = 2),
    )

private fun multiState() =
    ClarifyCardState(
        key = "shot",
        question = "¿Qué recados hago esta tarde?",
        input =
            ClarifyInput.MultiSelect(
                options =
                    listOf(
                        ClarifyOption(display = "Correo", wire = "correo"),
                        ClarifyOption(display = "Farmacia", wire = "farmacia"),
                        ClarifyOption(display = "Panadería", wire = "panadería"),
                    ),
            ),
        progress = ClarifyProgress(current = 2, total = 3),
    )

private fun freeTextState() =
    ClarifyCardState(
        key = "shot",
        question = "¿Para cuándo lo necesitas?",
        input = ClarifyInput.FreeText,
    )
