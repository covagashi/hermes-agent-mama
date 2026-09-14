package ai.hermes.mama.core.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Dimensiones del sistema de diseño (design/README.md + reglas de AGENTS.md).
 * `MinTouchTarget` (56 dp) es el suelo para cualquier objetivo táctil.
 */
object MamaDimens {
    /** Objetivo táctil mínimo: todos los componentes interactivos ≥ 56 dp. */
    val MinTouchTarget = 56.dp

    /** Altura de los botones grandes (BigButton). */
    val ButtonHeight = 64.dp

    /** Altura de los campos de texto (pantalla Conexión, composer). */
    val FieldHeight = 60.dp

    /** Alto mínimo de una fila de la lista de chats. */
    val ChatRowHeight = 92.dp

    /** Alto mínimo de la barra superior de pantalla. */
    val TopBarHeight = 72.dp

    /** Radio de las esquinas "lejanas" de una burbuja de chat. */
    val BubbleCorner = 20.dp

    /** Radio de la esquina "propia" de la burbuja (la pegada al borde). */
    val BubbleOwnCorner = 6.dp

    /** Radio de campos y tarjetas. */
    val CardCorner = 16.dp

    /** Radio superior de la hoja inferior (aprobaciones, documentos). */
    val SheetCorner = 28.dp

    /** Ancho máximo de una burbuja de chat. */
    val BubbleMaxWidth = 320.dp

    /** Padding horizontal interno de las burbujas. */
    val BubblePaddingHorizontal = 18.dp

    /** Padding vertical interno de las burbujas. */
    val BubblePaddingVertical = 14.dp

    /** Tamaño de los iconos habituales (Material Symbols outlined, 24–28 dp). */
    val IconSize = 24.dp

    /** Tamaño de icono grande (botones principales, micrófono). */
    val IconSizeLarge = 28.dp
}
