package ai.hermes.mama.core.ui.components

import ai.hermes.mama.core.ui.R
import ai.hermes.mama.core.ui.theme.MamaDimens
import ai.hermes.mama.core.ui.theme.MamaTheme
import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Botón principal de la app: píldora de ≥ 64 dp de alto, ancho completo,
 * texto 20 sp en negrita e icono opcional (mockups de Main, Conexión,
 * Aprobación). Siempre ≥ 56 dp de objetivo táctil.
 */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: MamaButtonVariant = MamaButtonVariant.Primary,
    icon: ImageVector? = null,
    iconContentDescription: String? = null,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = variantColors(variant, scheme)
    val finalContainer =
        if (enabled) colors.container else scheme.onSurface.copy(alpha = DISABLED_CONTAINER_ALPHA)
    val finalContent =
        if (enabled) colors.content else scheme.onSurface.copy(alpha = DISABLED_CONTENT_ALPHA)

    Surface(
        onClick = onClick,
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = MamaDimens.ButtonHeight)
                .semantics { role = Role.Button },
        enabled = enabled,
        shape = CircleShape, // píldora (32 dp de radio a 64 dp de alto)
        color = finalContainer,
        contentColor = finalContent,
        border = colors.border?.let { BorderStroke(2.dp, it) },
    ) {
        Row(
            modifier =
                Modifier
                    .defaultMinSize(minHeight = MamaDimens.ButtonHeight)
                    .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = iconContentDescription,
                    modifier = Modifier.size(MamaDimens.IconSizeLarge),
                )
                Spacer(modifier = Modifier.size(10.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private const val DISABLED_CONTAINER_ALPHA = 0.12f
private const val DISABLED_CONTENT_ALPHA = 0.38f

private data class VariantColors(
    val container: Color,
    val content: Color,
    val border: Color?,
)

/** Colores de cada variante según los mockups (design/mockups). */
private fun variantColors(
    variant: MamaButtonVariant,
    scheme: ColorScheme,
): VariantColors =
    when (variant) {
        MamaButtonVariant.Primary ->
            VariantColors(
                container = scheme.primary,
                content = scheme.onPrimary,
                border = null,
            )
        MamaButtonVariant.Outline ->
            VariantColors(
                container = scheme.surface,
                content = scheme.onPrimaryContainer,
                border = scheme.primary,
            )
        MamaButtonVariant.Danger ->
            VariantColors(
                container = scheme.tertiaryContainer,
                content = scheme.onTertiaryContainer,
                border = scheme.tertiary,
            )
        MamaButtonVariant.Neutral ->
            VariantColors(
                container = scheme.surface,
                content = scheme.onSurface,
                border = scheme.onSurfaceVariant,
            )
    }

@Preview(name = "BigButton claro", showBackground = true)
@Preview(name = "BigButton oscuro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun BigButtonPreview() {
    MamaTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BigButton(
                    text = stringResource(R.string.design_example_new_chat),
                    onClick = {},
                )
                BigButton(
                    text = stringResource(R.string.design_example_yes),
                    onClick = {},
                    variant = MamaButtonVariant.Outline,
                )
                BigButton(
                    text = stringResource(R.string.design_stop),
                    onClick = {},
                    variant = MamaButtonVariant.Danger,
                )
                BigButton(
                    text = stringResource(R.string.design_example_no),
                    onClick = {},
                    variant = MamaButtonVariant.Neutral,
                )
            }
        }
    }
}
