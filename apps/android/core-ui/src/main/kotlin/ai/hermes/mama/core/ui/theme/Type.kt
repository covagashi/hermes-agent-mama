package ai.hermes.mama.core.ui.theme

import ai.hermes.mama.core.ui.R
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Atkinson Hyperlegible (Braille Institute, licencia OFL — ver NOTICE del
 * módulo). Diseñada para lectores con baja visión: letra elegida a propósito
 * para "Hermes para mamá" (design/README.md). Sólo existen pesos 400/700:
 * no usar FontWeight.Medium ni variantes intermedias.
 */
val MamaFontFamily =
    FontFamily(
        Font(R.font.atkinson_hyperlegible_regular, FontWeight.Normal),
        Font(R.font.atkinson_hyperlegible_italic, FontWeight.Normal, FontStyle.Italic),
        Font(R.font.atkinson_hyperlegible_bold, FontWeight.Bold),
        Font(R.font.atkinson_hyperlegible_bold_italic, FontWeight.Bold, FontStyle.Italic),
    )

private fun mamaStyle(
    weight: FontWeight,
    sizeSp: Float,
    lineHeightSp: Float,
) = TextStyle(
    fontFamily = MamaFontFamily,
    fontWeight = weight,
    fontSize = sizeSp.sp,
    lineHeight = lineHeightSp.sp,
)

/**
 * Escala tipográfica de design/README.md: cuerpo de chat 19 sp, título de
 * pantalla 22 sp, título de tarjeta 24 sp, etiquetas 16–17 sp, botones 20 sp
 * en negrita. Todo ≥ 14 sp: nada de letra pequeña (AGENTS.md: ≥ 18 sp en el
 * chat, aquí el cuerpo es 19).
 *
 * Los 15 slots de M3 quedan definidos con Atkinson: los de familia display y
 * headlineLarge apenas se usan en la app, pero si un feature-* los invoca no
 * deben caer a la fuente por defecto (Roboto) ni a tamaños fuera de escala.
 */
val MamaTypography =
    Typography(
        // Titulares de portada; en la escala, por encima de los encabezados.
        displayLarge = mamaStyle(FontWeight.Bold, 40f, 48f),
        displayMedium = mamaStyle(FontWeight.Bold, 36f, 44f),
        displaySmall = mamaStyle(FontWeight.Bold, 34f, 40f),
        // Nombre de la app / encabezados destacados (MainActivity).
        headlineLarge = mamaStyle(FontWeight.Bold, 30f, 36f),
        // Encabezado de pantalla ("Conectar con Hermes" en Conexión).
        headlineMedium = mamaStyle(FontWeight.Bold, 28f, 34f),
        // Título de tarjeta (Aprobación, Pregunta, hoja inferior).
        headlineSmall = mamaStyle(FontWeight.Bold, 24f, 29f),
        // Título en la barra superior.
        titleLarge = mamaStyle(FontWeight.Bold, 22f, 27f),
        // Título de fila de chat / texto de los botones grandes.
        titleMedium = mamaStyle(FontWeight.Bold, 20f, 24f),
        // Etiquetas destacadas ("Hermes pregunta", etiquetas de campo).
        titleSmall = mamaStyle(FontWeight.Bold, 17f, 22f),
        // Cuerpo de chat y campos de texto.
        bodyLarge = mamaStyle(FontWeight.Normal, 19f, 27f),
        // Cuerpo secundario (detalle de aprobación, descripciones).
        bodyMedium = mamaStyle(FontWeight.Normal, 17f, 24f),
        // Pistas y metadatos ("Si no estás segura…", hora del chat).
        bodySmall = mamaStyle(FontWeight.Normal, 15f, 20f),
        // Texto de BigButton (botones 20 sp negrita).
        labelLarge = mamaStyle(FontWeight.Bold, 20f, 24f),
        // Etiquetas 16 sp en negrita (banner, chips compactos tipo "Parar").
        labelMedium = mamaStyle(FontWeight.Bold, 16f, 20f),
        // Etiqueta pequeña 16 sp (chip de actividad, contadores).
        labelSmall = mamaStyle(FontWeight.Normal, 16f, 20f),
    )
