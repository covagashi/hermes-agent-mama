package ai.hermes.mama.feature.browser

import ai.hermes.mama.core.ui.components.BigButton
import ai.hermes.mama.core.ui.components.MamaButtonVariant
import ai.hermes.mama.core.ui.theme.MamaDimens
import ai.hermes.mama.core.ui.theme.MamaTheme
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Hoja inferior de descarga (ROADMAP §5/G1, mockup Documento.dc.html):
 * scrim + hoja «📄 nombre — Abrir · Compartir · Enviar a Hermes».
 *
 * A diferencia de la aprobación (que NO se puede descartar sin responder), la
 * hoja de descarga sí: el scrim lleva la etiqueta «Cerrar» para TalkBack y el
 * botón atrás del sistema la cierra. Las acciones no la cierran — tras
 * «Abrir» la usuaria vuelve y aún puede «Compartir» o «Enviar a Hermes».
 *
 * [sendEnabled] queda en `false` hasta G2 (`file.attach`): el botón se ve
 * deshabilitado pero cableado — mismo patrón que en el mockup.
 */
@Composable
fun DownloadSheetOverlay(
    doc: DownloadedDoc,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onSendToHermes: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sendEnabled: Boolean = false,
) {
    BackHandler(onBack = onDismiss)
    Box(modifier = modifier.fillMaxSize()) {
        // Scrim al 45 % (mockup): tocarlo cierra la hoja. `clickable` +
        // `semantics` en la misma cadena → UN nodo con OnClick y la etiqueta
        // «Cerrar» para TalkBack — la hoja no es bloqueante.
        val dismissDescription = stringResource(R.string.download_dismiss_cd)
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA))
                    .semantics { contentDescription = dismissDescription }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = dismissDescription,
                        onClick = onDismiss,
                    ),
        )
        DownloadSheetCard(
            doc = doc,
            onOpen = onOpen,
            onShare = onShare,
            onSendToHermes = onSendToHermes,
            sendEnabled = sendEnabled,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** La hoja en sí, reusable en previews y capturas Roborazzi. */
@Composable
fun DownloadSheetCard(
    doc: DownloadedDoc,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onSendToHermes: () -> Unit,
    modifier: Modifier = Modifier,
    sendEnabled: Boolean = false,
) {
    val sheetTitle = stringResource(R.string.download_sheet_cd)
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics {
                    // paneTitle hace que TalkBack anuncie la hoja al aparecer.
                    paneTitle = sheetTitle
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
            // bajo): la usuaria siempre llega a los tres botones.
            modifier =
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FileTitleRow(doc = doc)
            BigButton(
                text = stringResource(R.string.download_open),
                onClick = onOpen,
                variant = MamaButtonVariant.Primary,
                icon = Icons.Outlined.FileOpen,
            )
            BigButton(
                text = stringResource(R.string.download_share),
                onClick = onShare,
                variant = MamaButtonVariant.Outline,
                icon = Icons.Outlined.IosShare,
            )
            BigButton(
                text = stringResource(R.string.download_send_to_hermes),
                onClick = onSendToHermes,
                variant = MamaButtonVariant.Outline,
                icon = Icons.AutoMirrored.Outlined.Send,
                enabled = sendEnabled,
            )
            Text(
                text = stringResource(R.string.download_send_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Icono de documento en baldosa + nombre del fichero y «Guardado en Descargas · 128 KB». */
@Composable
private fun FileTitleRow(doc: DownloadedDoc) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(60.dp),
            shape = RoundedCornerShape(MamaDimens.CardCorner),
            color = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.tertiary,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Description,
                    // Decorativo: el nombre del fichero está al lado.
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = doc.fileName,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                    stringResource(
                        R.string.download_saved_detail,
                        DownloadNotice.sizeLabel(doc.sizeBytes),
                    ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val SCRIM_ALPHA = 0.45f

// ---------------------------------------------------------------- previews --

@Preview(name = "Hoja de descarga", showBackground = true, widthDp = 390, heightDp = 420)
@Composable
private fun DownloadSheetPreview() {
    MamaTheme {
        DownloadSheetCard(
            doc =
                DownloadedDoc(
                    fileName = "factura-lavadora.pdf",
                    mimeType = "application/pdf",
                    sizeBytes = 131_072,
                    contentUri = "content://media/external/downloads/1",
                ),
            onOpen = {},
            onShare = {},
            onSendToHermes = {},
        )
    }
}
