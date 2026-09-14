package ai.hermes.mama.core.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verificación WCAG del sistema de diseño (aceptación C1: contraste ≥ 4.5:1).
 *
 * Calcula el ratio de contraste de cada par texto/fondo que usan los
 * componentes, en claro y en oscuro, a partir de los propios tokens — si un
 * token cambia, el test se reevalúa solo.
 *
 * Única excepción deliberada: el par tertiary/tertiaryContainer del botón
 * "Parar" en claro (~4.2:1). Es texto grande en negrita (17–20 sp bold ≥
 * 18.66 px bold ⇒ WCAG "large text", exige ≥ 3:1) y además es el color exacto
 * de los mockups. Se aserta ≥ 3:1 y queda documentado en el PR de C1.
 */
class MamaContrastTest {
    private data class PairCase(
        val name: String,
        val fg: Color,
        val bg: Color,
        val min: Double = MIN_NORMAL_TEXT,
    )

    @Test
    fun lightScheme_textPairs_meetAA() {
        val s = MamaLightColorScheme
        val cases =
            listOf(
                PairCase("BigButton Primary (onPrimary/primary)", s.onPrimary, s.primary),
                PairCase("BigButton Outline (onPrimaryContainer/surface)", s.onPrimaryContainer, s.surface),
                PairCase("BigButton Neutral (onSurface/surface)", s.onSurface, s.surface),
                PairCase("ChatBubble usuario (onBackground/primaryContainer)", s.onBackground, s.primaryContainer),
                PairCase("ChatBubble Hermes (onBackground/surface)", s.onBackground, s.surface),
                PairCase("Icono escuchar (onPrimaryContainer/surfaceVariant)", s.onPrimaryContainer, s.surfaceVariant),
                PairCase("ActivityChip texto (onSurfaceVariant/surfaceVariant)", s.onSurfaceVariant, s.surfaceVariant),
                PairCase("ActivityChip indicador (primary/surfaceVariant)", s.primary, s.surfaceVariant),
                PairCase("TopBanner texto (onBackground/tertiaryContainer)", s.onBackground, s.tertiaryContainer),
                PairCase("EmptyState título (onBackground/background)", s.onBackground, s.background),
                PairCase("EmptyState descripción (onSurfaceVariant/background)", s.onSurfaceVariant, s.background),
                PairCase(
                    "EmptyState icono (onPrimaryContainer/primaryContainer)",
                    s.onPrimaryContainer,
                    s.primaryContainer,
                ),
                PairCase("Texto secundario general (onSurfaceVariant/background)", s.onSurfaceVariant, s.background),
                PairCase("Texto secundario en tarjeta (onSurfaceVariant/surface)", s.onSurfaceVariant, s.surface),
                PairCase("Error (onError/error)", s.onError, s.error),
            )
        assertPairs(cases)
    }

    @Test
    fun darkScheme_textPairs_meetAA() {
        val s = MamaDarkColorScheme
        val cases =
            listOf(
                PairCase("BigButton Primary (onPrimary/primary)", s.onPrimary, s.primary),
                PairCase("BigButton Outline (onPrimaryContainer/surface)", s.onPrimaryContainer, s.surface),
                PairCase("BigButton Neutral (onSurface/surface)", s.onSurface, s.surface),
                PairCase(
                    "BigButton Danger (onTertiaryContainer/tertiaryContainer)",
                    s.onTertiaryContainer,
                    s.tertiaryContainer,
                ),
                PairCase("ChatBubble usuario (onBackground/primaryContainer)", s.onBackground, s.primaryContainer),
                PairCase("ChatBubble Hermes (onBackground/surface)", s.onBackground, s.surface),
                PairCase("Icono escuchar (onPrimaryContainer/surfaceVariant)", s.onPrimaryContainer, s.surfaceVariant),
                PairCase("ActivityChip texto (onSurfaceVariant/surfaceVariant)", s.onSurfaceVariant, s.surfaceVariant),
                PairCase("ActivityChip indicador (primary/surfaceVariant)", s.primary, s.surfaceVariant),
                PairCase("TopBanner texto (onBackground/tertiaryContainer)", s.onBackground, s.tertiaryContainer),
                PairCase("EmptyState título (onBackground/background)", s.onBackground, s.background),
                PairCase("EmptyState descripción (onSurfaceVariant/background)", s.onSurfaceVariant, s.background),
                PairCase(
                    "EmptyState icono (onPrimaryContainer/primaryContainer)",
                    s.onPrimaryContainer,
                    s.primaryContainer,
                ),
                PairCase("Texto secundario general (onSurfaceVariant/background)", s.onSurfaceVariant, s.background),
                PairCase("Error (onError/error)", s.onError, s.error),
            )
        assertPairs(cases)
    }

    @Test
    fun lightScheme_stopButton_largeBoldText_meetsAALarge() {
        val s = MamaLightColorScheme
        // "Parar": 17–20 sp en negrita sobre tertiaryContainer (mockup Chat).
        val ratio = contrastRatio(s.onTertiaryContainer, s.tertiaryContainer)
        assertTrue(
            ratio >= MIN_LARGE_BOLD_TEXT,
            "Parar (large bold): ratio %.2f < %.1f".format(ratio, MIN_LARGE_BOLD_TEXT),
        )
        // Y el icono/borde (no texto) del mismo botón.
        val iconRatio = contrastRatio(s.tertiary, s.tertiaryContainer)
        assertTrue(
            iconRatio >= MIN_LARGE_BOLD_TEXT,
            "Parar (icono/borde): ratio %.2f < %.1f".format(iconRatio, MIN_LARGE_BOLD_TEXT),
        )
    }

    @Test
    fun darkScheme_outlinesVisible() {
        // Bordes de burbuja/tarjeta en oscuro: no-texto WCAG 1.4.11 ≥ 3:1.
        val s = MamaDarkColorScheme
        val ratio = contrastRatio(s.outline, s.background)
        assertTrue(ratio >= MIN_LARGE_BOLD_TEXT, "outline/background oscuro: %.2f".format(ratio))
    }

    private fun assertPairs(cases: List<PairCase>) {
        val failures =
            cases
                .map { Triple(it.name, contrastRatio(it.fg, it.bg), it.min) }
                .filter { (_, ratio, min) -> ratio < min }
        assertTrue(
            failures.isEmpty(),
            "Pares bajo contraste:\n" +
                failures.joinToString("\n") { (name, ratio, min) ->
                    "- %s: %.2f < %.1f".format(name, ratio, min)
                },
        )
    }

    private companion object {
        const val MIN_NORMAL_TEXT = 4.5
        const val MIN_LARGE_BOLD_TEXT = 3.0

        /** Ratio de contraste WCAG 2.x: (L1 + 0.05) / (L2 + 0.05). */
        fun contrastRatio(
            fg: Color,
            bg: Color,
        ): Double {
            val l1 = relativeLuminance(fg)
            val l2 = relativeLuminance(bg)
            val lighter = maxOf(l1, l2)
            val darker = minOf(l1, l2)
            return (lighter + 0.05) / (darker + 0.05)
        }

        /** Luminancia relativa WCAG sobre los canales sRGB del Color. */
        fun relativeLuminance(color: Color): Double {
            fun channel(c: Float): Double {
                val cs = c.toDouble()
                return if (cs <= 0.04045) cs / 12.92 else ((cs + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
        }
    }
}
