package ai.hermes.mama.core.ui.theme

import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Guarda contra la trampa silenciosa de los slots de M3: cualquier estilo sin
 * definir cae a `FontFamily.Default` (Roboto) con tamaños fuera de escala.
 * Los 15 slots deben apuntar al MISMO objeto [MamaFontFamily].
 */
class MamaTypographyTest {
    @Test
    fun allSlots_useMamaFontFamily() {
        val styles =
            mapOf(
                "displayLarge" to MamaTypography.displayLarge,
                "displayMedium" to MamaTypography.displayMedium,
                "displaySmall" to MamaTypography.displaySmall,
                "headlineLarge" to MamaTypography.headlineLarge,
                "headlineMedium" to MamaTypography.headlineMedium,
                "headlineSmall" to MamaTypography.headlineSmall,
                "titleLarge" to MamaTypography.titleLarge,
                "titleMedium" to MamaTypography.titleMedium,
                "titleSmall" to MamaTypography.titleSmall,
                "bodyLarge" to MamaTypography.bodyLarge,
                "bodyMedium" to MamaTypography.bodyMedium,
                "bodySmall" to MamaTypography.bodySmall,
                "labelLarge" to MamaTypography.labelLarge,
                "labelMedium" to MamaTypography.labelMedium,
                "labelSmall" to MamaTypography.labelSmall,
            )
        styles.forEach { (slot, style) ->
            assertSame(
                MamaFontFamily,
                style.fontFamily,
                "$slot no usa MamaFontFamily (¿caído al default de M3?)",
            )
        }
    }

    @Test
    fun allSlots_keepMinimumReadableSize() {
        // AGENTS.md: nada de letra pequeña; el mínimo del sistema es 15 sp
        // (bodySmall, sólo metadatos). El chat va a 19 sp.
        val styles =
            listOf(
                MamaTypography.displayLarge,
                MamaTypography.displayMedium,
                MamaTypography.displaySmall,
                MamaTypography.headlineLarge,
                MamaTypography.headlineMedium,
                MamaTypography.headlineSmall,
                MamaTypography.titleLarge,
                MamaTypography.titleMedium,
                MamaTypography.titleSmall,
                MamaTypography.bodyLarge,
                MamaTypography.bodyMedium,
                MamaTypography.bodySmall,
                MamaTypography.labelLarge,
                MamaTypography.labelMedium,
                MamaTypography.labelSmall,
            )
        styles.forEach { style ->
            assertTrue(
                style.fontSize.value >= MIN_FONT_SP,
                "fontSize %.1f sp < %.0f sp".format(style.fontSize.value, MIN_FONT_SP),
            )
        }
    }

    private companion object {
        const val MIN_FONT_SP = 15f
    }
}
