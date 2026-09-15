package ai.hermes.mama.feature.browser

import ai.hermes.mama.core.ui.components.BigButton
import ai.hermes.mama.core.ui.components.MamaButtonVariant
import ai.hermes.mama.core.ui.components.TopBanner
import ai.hermes.mama.core.ui.theme.MamaDimens
import ai.hermes.mama.core.ui.theme.MamaTheme
import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Pantalla Navegador (ROADMAP §5/F4, mockup Navegador.dc.html): el WebView del
 * [AndroidWebViewDriver] a pantalla completa con una barra superior de progreso
 * (`browser.progress` o «Hermes está navegando…») + **Parar**, un velo
 * «Un momento…» mientras un comando §2.6 está en curso y «Volver al chat»
 * abajo.
 *
 * Punto de entrada del feature (lo monta el NavHost de C8): ata el ciclo de
 * vida del [BrowserPaneController] a la composición — `start()` al entrar
 * (pide el controlador vía [BrowserPaneController], idempotente tras el
 * auto-registro F3) y `stop()` al salir. `stop()` NO hace detach: el
 * controlador sigue registrado en segundo plano hasta cerrar el chat.
 *
 * [autoOpened] = la navegación vino de `ControllerSignal.NavigateToBrowser`
 * (un `tool.start browser_*`): se muestra el aviso "Hermes va a usar el
 * navegador" unos segundos.
 */
@Composable
fun BrowserPane(
    controller: BrowserPaneController,
    driver: AndroidWebViewDriver,
    onBackToChat: () -> Unit,
    modifier: Modifier = Modifier,
    autoOpened: Boolean = false,
) {
    val state by controller.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(controller, autoOpened) { controller.start(autoOpenNotice = autoOpened) }
    // §5/G1: el DownloadListener del WebView delega en el gestor de la sesión.
    // No se limpia en onDispose: driver y descargas en curso sobreviven a la
    // pantalla («Volver al chat» no cancela una descarga).
    LaunchedEffect(driver, controller.downloads) {
        controller.downloads?.let { driver.downloadHandler = it::onDownloadStart }
    }
    // §5/G1: la notificación «Descarga terminada» necesita POST_NOTIFICATIONS en
    // API 33+ (runtime). Se pide una vez, justo cuando la hoja de la primera
    // descarga la hace relevante; concederla o no no cambia nada más.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val context = LocalContext.current
        var askedNotify by rememberSaveable { mutableStateOf(false) }
        val notifyPermission =
            rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        LaunchedEffect(state.download != null) {
            if (
                !askedNotify &&
                state.download != null &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                askedNotify = true
                notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    DisposableEffect(controller) {
        onDispose {
            // Sólo los colectores: el controlador sigue registrado (§5/F4).
            controller.stop()
        }
    }
    BrowserScreen(
        state = state,
        onStop = controller::stopHermes,
        onBackToChat = onBackToChat,
        modifier = modifier,
        onDownloadOpen = controller::openDownload,
        onDownloadShare = controller::shareDownload,
        onDownloadSendToHermes = controller::sendDownloadToHermes,
        onDownloadDismiss = controller::dismissDownload,
    ) {
        BrowserWebView(driver = driver, modifier = Modifier.fillMaxSize())
    }
}

/**
 * La pantalla ya con el estado resuelto (tests y previews la usan directa):
 * [webContent] es el hueco del WebView — en producción lo rellena
 * [BrowserPane] con el `driver.webView`; en tests/previews puede ser un
 * panel liso. Si el driver sobrevive a la pantalla (lo normal: vive a nivel
 * de sesión de chat), al volver a entrar el mismo WebView se re-adjunta —
 * cookies, historial y página en curso intactos.
 */
@Composable
fun BrowserScreen(
    state: BrowserPaneState,
    onStop: () -> Unit,
    onBackToChat: () -> Unit,
    modifier: Modifier = Modifier,
    onDownloadOpen: () -> Unit = {},
    onDownloadShare: () -> Unit = {},
    onDownloadSendToHermes: () -> Unit = {},
    onDownloadDismiss: () -> Unit = {},
    webContent: @Composable () -> Unit,
) {
    val title = stringResource(R.string.browser_screen_title)
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .semantics { paneTitle = title },
        ) {
            if (state.showAutoOpenNotice) {
                TopBanner(
                    text = stringResource(R.string.browser_auto_open_notice),
                    icon = Icons.Outlined.Public,
                )
            }
            BrowserTopBar(state = state, onStop = onStop)
            // El velo cubre SÓLO el área web: la barra superior (Parar) y la
            // inferior (Volver al chat) siguen alcanzables con un comando en curso.
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                webContent()
                if (state.commandInFlight) {
                    BrowserVeil(
                        detail = state.progressMessage,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            BrowserBottomBar(onBackToChat = onBackToChat)
        }
        // §5/G1: hoja «📄 nombre — Abrir · Compartir · Enviar a Hermes» al
        // terminar una descarga — por encima de todo, también del velo.
        state.download?.let { doc ->
            DownloadSheetOverlay(
                doc = doc,
                onOpen = onDownloadOpen,
                onShare = onDownloadShare,
                onSendToHermes = onDownloadSendToHermes,
                onDismiss = onDownloadDismiss,
            )
        }
    }
}

// ------------------------------------------------------------------ piezas --

/** El `WebView` del driver dentro de la composición (se re-adjunta al volver). */
@Composable
private fun BrowserWebView(
    driver: AndroidWebViewDriver,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.browser_webview_cd)
    AndroidView(
        factory = { driver.webView },
        modifier = modifier,
        update = { view -> view.contentDescription = description },
    )
}

/**
 * Barra superior (mockup): fondo `primaryContainer`, spinner + texto de
 * progreso (`liveRegion` para que TalkBack anuncie cada `browser.progress`) y
 * el botón **Parar**. En los estados de error el icono cambia y el texto es el
 * de la fase (`browser_server_not_enabled` / `browser_registration_failed`).
 */
@Composable
private fun BrowserTopBar(
    state: BrowserPaneState,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusText =
        when (state.phase) {
            BrowserPhase.ServerNotEnabled -> stringResource(R.string.browser_server_not_enabled)
            BrowserPhase.RegistrationFailed -> stringResource(R.string.browser_registration_failed)
            else ->
                state.progressMessage
                    ?: stringResource(R.string.browser_default_progress)
        }
    Column(modifier = modifier.fillMaxWidth()) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = MamaDimens.TopBarHeight)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (state.phase) {
                    BrowserPhase.ServerNotEnabled, BrowserPhase.RegistrationFailed ->
                        Icon(
                            imageVector = Icons.Outlined.ErrorOutline,
                            contentDescription = null, // el texto lo dice todo
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(22.dp),
                        )
                    else ->
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surface,
                            strokeWidth = 3.dp,
                        )
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.titleSmall,
                    modifier =
                        Modifier
                            .weight(1f)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                )
                BrowserStopButton(onClick = onStop)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

/** Botón **Parar** (mockup: píldora tertiary con borde e icono ■), ≥ 56 dp. */
@Composable
private fun BrowserStopButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.browser_stop_cd)
    Surface(
        onClick = onClick,
        modifier =
            modifier
                .heightIn(min = MamaDimens.MinTouchTarget)
                .semantics { contentDescription = description },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.tertiary),
    ) {
        Row(
            modifier =
                Modifier
                    .defaultMinSize(minHeight = MamaDimens.MinTouchTarget)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Icono "stop" del mockup: cuadrado redondeado (material-icons no
            // trae uno en el set core; dibujarlo evita un asset más).
            Box(
                modifier =
                    Modifier
                        .size(14.dp)
                        .background(
                            MaterialTheme.colorScheme.tertiary,
                            RoundedCornerShape(3.dp),
                        ),
            )
            Text(
                text = stringResource(R.string.browser_stop),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/**
 * Velo «Un momento…» sobre el WebView mientras un comando §2.6 está en curso:
 * scrim claro al 35 % (mockup) que traga los toques y píldora oscura abajo con
 * «Un momento…» + el detalle del último `browser.progress` si lo hay.
 */
@Composable
private fun BrowserVeil(
    detail: String?,
    modifier: Modifier = Modifier,
) {
    val wait = stringResource(R.string.browser_wait)
    val veilText = if (detail.isNullOrBlank()) wait else "$wait $detail"
    Box(
        modifier =
            modifier
                .background(MaterialTheme.colorScheme.background.copy(alpha = VEIL_ALPHA))
                // Traga los toques sin añadir semántica de click (modal real).
                .pointerInput(Unit) { detectTapGestures(onTap = {}) },
    ) {
        Surface(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        ) {
            Text(
                text = veilText,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}

/** Barra inferior (mockup): «Volver al chat» a ancho completo. */
@Composable
private fun BrowserBottomBar(
    onBackToChat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        BigButton(
            text = stringResource(R.string.browser_back_to_chat),
            onClick = onBackToChat,
            variant = MamaButtonVariant.Outline,
            icon = Icons.Outlined.ChatBubbleOutline,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

private const val VEIL_ALPHA = 0.35f

// ---------------------------------------------------------------- previews --

@Preview(name = "Navegador navegando", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun BrowserScreenPreviewBrowsing() {
    MamaTheme {
        BrowserScreen(
            state =
                BrowserPaneState(
                    phase = BrowserPhase.Browsing,
                    progressMessage = "Hermes está buscando tu factura en Tienda Ejemplo",
                ),
            onStop = {},
            onBackToChat = {},
        ) {
            PreviewPage()
        }
    }
}

@Preview(name = "Navegador con velo", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun BrowserScreenPreviewVeil() {
    MamaTheme {
        BrowserScreen(
            state =
                BrowserPaneState(
                    phase = BrowserPhase.Browsing,
                    progressMessage = "voy a pulsar «Descargar factura»",
                    commandInFlight = true,
                ),
            onStop = {},
            onBackToChat = {},
        ) {
            PreviewPage()
        }
    }
}

@Preview(
    name = "Navegador oscuro con velo",
    showBackground = true,
    widthDp = 390,
    heightDp = 844,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun BrowserScreenPreviewVeilDark() {
    MamaTheme {
        BrowserScreen(
            state =
                BrowserPaneState(
                    phase = BrowserPhase.Browsing,
                    progressMessage = "voy a pulsar «Descargar factura»",
                    commandInFlight = true,
                ),
            onStop = {},
            onBackToChat = {},
        ) {
            PreviewPage()
        }
    }
}

@Preview(name = "Navegador sin flag", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun BrowserScreenPreviewNotEnabled() {
    MamaTheme {
        BrowserScreen(
            state = BrowserPaneState(phase = BrowserPhase.ServerNotEnabled),
            onStop = {},
            onBackToChat = {},
        ) {
            PreviewPage()
        }
    }
}

/** Panel liso que hace de «página web» en previews (sin WebView real). */
@Composable
private fun PreviewPage() {
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
}
