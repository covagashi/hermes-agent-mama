package ai.hermes.mama.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Radios de design/README.md: campos y tarjetas 16 dp, hoja inferior 28 dp.
 * Las burbujas (20 dp, esquina propia 6 dp) y los botones (píldora 32 dp) los
 * fijan los propios componentes — ver MamaDimens.
 */
val MamaShapes =
    Shapes(
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(MamaDimens.CardCorner),
        large = RoundedCornerShape(MamaDimens.BubbleCorner),
        extraLarge = RoundedCornerShape(MamaDimens.SheetCorner),
    )
