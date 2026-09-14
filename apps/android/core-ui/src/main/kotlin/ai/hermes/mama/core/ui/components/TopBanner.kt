package ai.hermes.mama.core.ui.components

import ai.hermes.mama.core.ui.R
import ai.hermes.mama.core.ui.theme.MamaDimens
import ai.hermes.mama.core.ui.theme.MamaTheme
import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Franja de aviso que aparece arriba de la pantalla sólo cuando algo falla
 * (ROADMAP §3: "Sin conexión. Reintentando…"). Tono cálido (tertiaryContainer)
 * en lugar de rojo de error: es un aviso discreto, no una alarma.
 *
 * No interactiva; `liveRegion` anuncia el aviso en TalkBack al aparecer.
 */
@Composable
fun TopBanner(
    modifier: Modifier = Modifier,
    text: String = stringResource(R.string.design_banner_offline),
    icon: ImageVector = Icons.Outlined.WifiOff,
) {
    Surface(
        // Un solo nodo accesible con el texto completo (el icono es decorativo).
        modifier = modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = MamaDimens.MinTouchTarget)
                    .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                // Decorativo: el texto ya lo dice todo para TalkBack.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(MamaDimens.IconSize),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}

@Preview(name = "Banner claro", showBackground = true)
@Preview(name = "Banner oscuro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TopBannerPreview() {
    MamaTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            TopBanner()
        }
    }
}
