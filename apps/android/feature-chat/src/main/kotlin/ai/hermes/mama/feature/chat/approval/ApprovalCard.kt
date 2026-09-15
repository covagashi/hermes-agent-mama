package ai.hermes.mama.feature.chat.approval

import ai.hermes.mama.core.ui.components.BigButton
import ai.hermes.mama.core.ui.components.MamaButtonVariant
import ai.hermes.mama.core.ui.theme.MamaDimens
import ai.hermes.mama.core.ui.theme.MamaTheme
import ai.hermes.mama.feature.chat.R
import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.QuestionMark
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Tarjeta modal de aprobación (C6, mockup Aprobacion.dc.html): velo a pantalla
 * completa + hoja inferior con la acción que Hermes quiere hacer en lenguaje
 * llano y dos botones enormes "Sí, adelante" / "No".
 *
 * No se puede descartar sin elegir (la aprobación quedaría colgando el
 * backend): el velo traga los toques y no hay gesto de cierre — la tarjeta se
 * va sola al responder o cuando el servidor envía `request.cancel`.
 *
 * [state] ya viene listo para pintar (ver [ApprovalController]); los taps
 * suben como [onApprove]/[onDeny]. Con [ApprovalStatus.Approved]/[ApprovalStatus.Denied]
 * los botones se sustituyen por la elección hecha (nada clickable); con
 * [ApprovalStatus.SendFailed] el aviso aparece y los botones siguen activos.
 */
@Composable
fun ApprovalOverlay(
    state: ApprovalCardState,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        // Velo que oscurece el chat y traga los toques (modal real, sin gesto
        // de cierre: la aprobación quedaría colgando el backend). detectTapGestures
        // consume el tap sin añadir semántica de click (TalkBack lo ignora).
        Surface(
            modifier =
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures(onTap = {}) },
            color = scrimColor(),
        ) {}
        ApprovalCard(
            state = state,
            onApprove = onApprove,
            onDeny = onDeny,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** La hoja inferior en sí, reusable en previews y capturas. */
@Composable
fun ApprovalCard(
    state: ApprovalCardState,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dialogTitle = stringResource(R.string.approval_kind_generic)
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics {
                    // paneTitle hace que TalkBack anuncie la hoja al aparecer.
                    paneTitle = dialogTitle
                    liveRegion = LiveRegionMode.Polite
                },
        shape =
            RoundedCornerShape(
                topStart = MamaDimens.SheetCorner,
                topEnd = MamaDimens.SheetCorner,
            ),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            // Scroll si el contenido supera la pantalla (fuente ×2, móvil
            // bajo): la usuaria siempre llega a los dos botones.
            modifier =
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TitleRow(kind = state.kind)
            if (state.detail != null) {
                DetailCard(detail = state.detail)
            }
            StatusContent(state = state, onApprove = onApprove, onDeny = onDeny)
        }
    }
}

/** Botones (pendiente/fallo) o fila de estado (respondida) según el ciclo de vida. */
@Composable
private fun StatusContent(
    state: ApprovalCardState,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    when (state.status) {
        ApprovalStatus.Pending -> {
            ChoiceButtons(onApprove = onApprove, onDeny = onDeny)
            HintText()
        }
        ApprovalStatus.Approved ->
            StatusRow(
                icon = Icons.Outlined.Check,
                text = stringResource(R.string.approval_answered_yes),
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        ApprovalStatus.Denied ->
            StatusRow(
                icon = Icons.Outlined.Close,
                text = stringResource(R.string.approval_answered_no),
                container = MaterialTheme.colorScheme.surfaceVariant,
                content = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        ApprovalStatus.SendFailed -> {
            StatusRow(
                icon = Icons.Outlined.ErrorOutline,
                text = stringResource(R.string.approval_send_failed),
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer,
            )
            // Reintento: la respuesta no salió; los botones siguen vivos.
            ChoiceButtons(onApprove = onApprove, onDeny = onDeny)
        }
    }
}

/** Icono en círculo + "Hermes quiere …" (mockup: círculo 52 dp, título 24 sp). */
@Composable
private fun TitleRow(kind: ApprovalKind) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(52.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = kind.icon(),
                    // Decorativo: el texto del título ya dice qué quiere Hermes.
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Text(
            text = kind.title(),
            style = MaterialTheme.typography.headlineSmall,
        )
    }
}

/** Tarjeta blanca con la acción detallada (description/command redactados). */
@Composable
private fun DetailCard(detail: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(MamaDimens.CardCorner),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onBackground,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            // Un command puede ser larguísimo: la hoja no debe comerse la pantalla.
            maxLines = MAX_DETAIL_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        )
    }
}

/** Los dos botones del mockup: "Sí, adelante" (verde) y "No" (contorno gris). */
@Composable
private fun ChoiceButtons(
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    BigButton(
        text = stringResource(R.string.approval_yes),
        onClick = onApprove,
        variant = MamaButtonVariant.Primary,
        icon = Icons.Outlined.Check,
        iconContentDescription = null,
    )
    BigButton(
        text = stringResource(R.string.approval_no),
        onClick = onDeny,
        variant = MamaButtonVariant.Neutral,
        icon = Icons.Outlined.Close,
        iconContentDescription = null,
    )
}

/** Pista del mockup: «Si no estás segura, pulsa "No". No pasa nada.» */
@Composable
private fun HintText() {
    Text(
        text = stringResource(R.string.approval_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Fila de estado respondida/error: sustituye a los botones (nada clickable). */
@Composable
private fun StatusRow(
    icon: ImageVector,
    text: String,
    container: Color,
    content: Color,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        shape = RoundedCornerShape(MamaDimens.CardCorner),
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier =
                Modifier
                    .heightIn(min = MamaDimens.MinTouchTarget)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(MamaDimens.IconSizeLarge),
            )
            Text(text = text, style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun ApprovalKind.title(): String =
    stringResource(
        when (this) {
            ApprovalKind.SendEmail -> R.string.approval_kind_send_email
            ApprovalKind.Email -> R.string.approval_kind_email
            ApprovalKind.BrowseWeb -> R.string.approval_kind_browser
            ApprovalKind.RunCommand -> R.string.approval_kind_terminal
            ApprovalKind.Files -> R.string.approval_kind_files
            ApprovalKind.Generic -> R.string.approval_kind_generic
        },
    )

private fun ApprovalKind.icon(): ImageVector =
    when (this) {
        ApprovalKind.SendEmail, ApprovalKind.Email -> Icons.Outlined.Email
        ApprovalKind.BrowseWeb -> Icons.Outlined.Public
        ApprovalKind.RunCommand -> Icons.Outlined.Terminal
        ApprovalKind.Files -> Icons.Outlined.FolderOpen
        ApprovalKind.Generic -> Icons.Outlined.QuestionMark
    }

/** Velo del mockup: `rgba(31,27,22,0.45)` = onBackground al 45 %. */
@Composable
private fun scrimColor() = MaterialTheme.colorScheme.onBackground.copy(alpha = SCRIM_ALPHA)

private const val SCRIM_ALPHA = 0.45f
private const val MAX_DETAIL_LINES = 6

@Preview(name = "Aprobación pendiente", showBackground = true, widthDp = 390)
@Preview(
    name = "Aprobación pendiente oscuro",
    showBackground = true,
    widthDp = 390,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ApprovalOverlayPendingPreview() {
    MamaTheme {
        ApprovalOverlay(
            state =
                ApprovalCardState(
                    kind = ApprovalKind.SendEmail,
                    detail = stringResource(R.string.approval_example_detail),
                ),
            onApprove = {},
            onDeny = {},
        )
    }
}

@Preview(name = "Aprobación respondida", showBackground = true, widthDp = 390)
@Composable
private fun ApprovalCardAnsweredPreview() {
    MamaTheme {
        ApprovalCard(
            state =
                ApprovalCardState(
                    kind = ApprovalKind.BrowseWeb,
                    detail = stringResource(R.string.approval_example_detail),
                    status = ApprovalStatus.Approved,
                ),
            onApprove = {},
            onDeny = {},
        )
    }
}

@Preview(name = "Aprobación fallo envío", showBackground = true, widthDp = 390)
@Composable
private fun ApprovalCardFailedPreview() {
    MamaTheme {
        ApprovalCard(
            state =
                ApprovalCardState(
                    kind = ApprovalKind.RunCommand,
                    detail = "rsync -av --progress",
                    status = ApprovalStatus.SendFailed,
                ),
            onApprove = {},
            onDeny = {},
        )
    }
}
