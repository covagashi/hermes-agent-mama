package ai.hermes.mama.feature.settings

import ai.hermes.mama.core.ui.components.BigButton
import ai.hermes.mama.core.ui.components.MamaButtonVariant
import ai.hermes.mama.core.ui.theme.MamaDimens
import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Pantalla Conexión (ROADMAP §5/C2, mockup `design/mockups/Conexion.dc.html`):
 * servidor, usuario, contraseña, "Probar conexión" y "Guardar y empezar", más
 * la preferencia "Leer las respuestas en voz alta" (la consume D2).
 *
 * - En flavor `mama` sólo se abre sin credenciales guardadas o tras la
 *   pulsación larga de 3 s sobre el logo en Chats ([connectionUnlockGesture]).
 * - [secureWindow] aplica `FLAG_SECURE` mientras la pantalla está visible:
 *   la contraseña no debe salir en capturas ni en la vista de recientes (§8).
 * - Toda la cadena de textos vive en `strings_conexion.xml` — aquí no hay
 *   literales de UI.
 */
@Composable
fun ConnectionScreen(
    viewModel: ConnectionViewModel,
    onNavigateToChats: () -> Unit,
    modifier: Modifier = Modifier,
    secureWindow: Boolean = true,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.navigation.collect { event ->
            when (event) {
                ConnectionNavEvent.NavigateToChats -> onNavigateToChats()
            }
        }
    }

    if (secureWindow) {
        FlagSecureEffect()
    }

    ConnectionContent(
        state = state,
        onServerChange = viewModel::onServerChange,
        onUsernameChange = viewModel::onUsernameChange,
        onPasswordChange = viewModel::onPasswordChange,
        onTogglePasswordVisibility = viewModel::onTogglePasswordVisibility,
        onReadAloudChange = viewModel::onReadAloudChange,
        onTest = viewModel::onTest,
        onSave = viewModel::onSave,
        modifier = modifier,
    )
}

/**
 * Contenido puro de la pantalla (estado + callbacks): es lo que capturan los
 * tests de Roborazzi y lo que puede previsualizarse sin ViewModel.
 */
@Composable
fun ConnectionContent(
    state: ConnectionUiState,
    onServerChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onReadAloudChange: (Boolean) -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val paneTitle = stringResource(R.string.conexion_pane_title)

    Surface(
        modifier =
            modifier
                .fillMaxSize()
                .semantics { this.paneTitle = paneTitle },
        color = scheme.background,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding(),
        ) {
            ConnectionHeader()

            // Formulario con scroll: a fuente 130–200 % el contenido excede la
            // pantalla y hay que llegar a todo (los botones quedan fijos abajo).
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                ConnectionFields(
                    state = state,
                    onServerChange = onServerChange,
                    onUsernameChange = onUsernameChange,
                    onPasswordChange = onPasswordChange,
                    onTogglePasswordVisibility = onTogglePasswordVisibility,
                )

                ReadAloudRow(
                    checked = state.readAloud,
                    enabled = !state.checking,
                    onCheckedChange = onReadAloudChange,
                )

                StatusBanner(state = state)
            }

            ConnectionActions(checking = state.checking, onTest = onTest, onSave = onSave)
        }
    }
}

/** Cabecera fija: logo, título y la frase que explica la pantalla. */
@Composable
private fun ConnectionHeader() {
    val scheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    Column(
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(64.dp)
                    .background(scheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            // Logotipo decorativo: el título ya nombra a Hermes.
            Text(text = "H", style = typography.displaySmall, color = scheme.onPrimary)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.conexion_title),
            style = typography.headlineMedium,
            color = scheme.onBackground,
        )
        Text(
            text = stringResource(R.string.conexion_subtitle),
            style = typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
    }
}

/** Los tres campos del formulario: servidor, usuario y contraseña con su ojo. */
@Composable
private fun ConnectionFields(
    state: ConnectionUiState,
    onServerChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
) {
    ConnectionField(
        label = stringResource(R.string.conexion_server_label),
        value = state.server,
        onValueChange = onServerChange,
        icon = Icons.Outlined.Dns,
        enabled = !state.checking,
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                autoCorrectEnabled = false,
            ),
    )
    ConnectionField(
        label = stringResource(R.string.conexion_username_label),
        value = state.username,
        onValueChange = onUsernameChange,
        icon = Icons.Outlined.Person,
        enabled = !state.checking,
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Text,
                autoCorrectEnabled = false,
            ),
    )
    ConnectionField(
        label = stringResource(R.string.conexion_password_label),
        value = state.password,
        onValueChange = onPasswordChange,
        icon = Icons.Outlined.Lock,
        enabled = !state.checking,
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
            ),
        visualTransformation =
            if (state.passwordVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
        trailingIcon = {
            PasswordVisibilityButton(
                visible = state.passwordVisible,
                enabled = !state.checking,
                onClick = onTogglePasswordVisibility,
            )
        },
    )
}

/** Botones fijos abajo (mockup): Probar = contorno, Guardar = relleno. */
@Composable
private fun ConnectionActions(
    checking: Boolean,
    onTest: () -> Unit,
    onSave: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BigButton(
            text = stringResource(R.string.conexion_test_button),
            onClick = onTest,
            variant = MamaButtonVariant.Outline,
            enabled = !checking,
        )
        BigButton(
            text = stringResource(R.string.conexion_save_button),
            onClick = onSave,
            variant = MamaButtonVariant.Primary,
            enabled = !checking,
        )
    }
}

/**
 * Campo del formulario con la etiqueta del mockup encima (17 sp negrita) y el
 * campo M3 de 60 dp, radio 16, fondo blanco y borde 2 dp `outline`. La
 * etiqueta visible ya describe el campo; el icono es decorativo.
 */
@OptIn(ExperimentalMaterial3Api::class) // DecorationBox/ContainerBox: borde 2 dp del mockup
@Composable
private fun ConnectionField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    icon: ImageVector,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val interactionSource = remember { MutableInteractionSource() }
    val colors = connectionFieldColors()

    Column(modifier = modifier) {
        Text(text = label, style = typography.titleSmall, color = scheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = MamaDimens.FieldHeight)
                    .semantics { contentDescription = label },
            enabled = enabled,
            textStyle = typography.bodyLarge.copy(color = scheme.onBackground),
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            singleLine = true,
            interactionSource = interactionSource,
            decorationBox = @Composable { innerTextField ->
                OutlinedTextFieldDefaults.DecorationBox(
                    value = value,
                    innerTextField = innerTextField,
                    enabled = enabled,
                    singleLine = true,
                    visualTransformation = visualTransformation,
                    interactionSource = interactionSource,
                    leadingIcon = {
                        Icon(imageVector = icon, contentDescription = null)
                    },
                    trailingIcon = trailingIcon,
                    colors = colors,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                    container = {
                        OutlinedTextFieldDefaults.ContainerBox(
                            enabled = enabled,
                            isError = false,
                            interactionSource = interactionSource,
                            colors = colors,
                            shape = RoundedCornerShape(MamaDimens.CardCorner),
                            focusedBorderThickness = 2.dp,
                            unfocusedBorderThickness = 2.dp,
                        )
                    },
                )
            },
        )
    }
}

/** Paleta del campo del mockup: fondo `surface`, borde `outline`→`primary` al foco. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun connectionFieldColors() =
    OutlinedTextFieldDefaults.colors(
        focusedContainerColor = MaterialTheme.colorScheme.surface,
        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
        disabledBorderColor = MaterialTheme.colorScheme.outlineVariant,
        focusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        focusedTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unfocusedTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )

/** Ojo del campo contraseña: objetivo 56 dp y `contentDescription` propio. */
@Composable
private fun PasswordVisibilityButton(
    visible: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val description =
        stringResource(
            if (visible) R.string.conexion_password_hide else R.string.conexion_password_show,
        )
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(MamaDimens.MinTouchTarget),
    ) {
        Icon(
            imageVector = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
            contentDescription = description,
        )
    }
}

/** Fila del toggle "Leer las respuestas en voz alta" (persistido para D2). */
@Composable
private fun ReadAloudRow(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = MamaDimens.MinTouchTarget)
                .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.conexion_read_aloud),
            style = MaterialTheme.typography.bodyLarge,
            color = scheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        MamaSwitch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

/**
 * Interruptor del tamaño del mockup (64×36 dp, pulgar 28 dp) — más grande que
 * el Switch M3 estándar, acorde al objetivo táctil de la app. `toggleable`
 * con `Role.Switch` da la semántica "interruptor" completa para TalkBack.
 */
@Composable
private fun MamaSwitch(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val trackColor by animateColorAsState(
        targetValue =
            when {
                !enabled -> scheme.surfaceVariant
                checked -> scheme.primary
                else -> scheme.surfaceVariant
            },
        label = "mama_switch_track",
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 28.dp else 0.dp,
        label = "mama_switch_thumb",
    )
    Box(
        modifier =
            Modifier
                .size(width = 64.dp, height = 36.dp)
                .background(trackColor, RoundedCornerShape(18.dp))
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier =
                Modifier
                    .offset(x = thumbOffset)
                    .padding(4.dp)
                    .size(28.dp)
                    .background(scheme.surface, CircleShape),
        )
    }
}

/** Banner de estado del mockup: checking / Connected / Failed. */
@Composable
private fun StatusBanner(state: ConnectionUiState) {
    val banner = state.banner
    if (banner == null && !state.checking) return

    val scheme = MaterialTheme.colorScheme
    val style =
        when {
            state.checking ->
                BannerStyle(
                    container = scheme.surfaceVariant,
                    content = scheme.onSurfaceVariant,
                    icon = null,
                    text = stringResource(R.string.conexion_checking),
                )
            banner is ConnectionBanner.Connected ->
                BannerStyle(
                    container = scheme.primaryContainer,
                    content = scheme.onPrimaryContainer,
                    icon = Icons.Outlined.Check,
                    text = connectedText(banner.displayName),
                )
            else ->
                BannerStyle(
                    container = scheme.errorContainer,
                    content = scheme.onErrorContainer,
                    icon = Icons.Outlined.ErrorOutline,
                    text = errorText((banner as? ConnectionBanner.Failed)?.reason),
                )
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .background(style.container, RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (style.icon != null) {
            Icon(imageVector = style.icon, contentDescription = null, tint = style.content)
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = style.content,
                strokeWidth = 2.dp,
            )
        }
        Text(text = style.text, style = MaterialTheme.typography.titleSmall, color = style.content)
    }
}

private data class BannerStyle(
    val container: androidx.compose.ui.graphics.Color,
    val content: androidx.compose.ui.graphics.Color,
    val icon: ImageVector?,
    val text: String,
)

@Composable
private fun connectedText(displayName: String?): String =
    if (displayName.isNullOrBlank()) {
        stringResource(R.string.conexion_connected)
    } else {
        stringResource(R.string.conexion_connected_as, displayName)
    }

/** Traducción razón tipada → texto humano (sin códigos HTTP ni jerga, §3). */
@Composable
private fun errorText(reason: ConnectionErrorReason?): String =
    stringResource(
        when (reason) {
            ConnectionErrorReason.MissingFields -> R.string.conexion_error_missing_fields
            ConnectionErrorReason.BadAddress -> R.string.conexion_error_bad_address
            ConnectionErrorReason.InsecureAddress -> R.string.conexion_error_insecure
            ConnectionErrorReason.WrongCredentials -> R.string.conexion_error_credentials
            ConnectionErrorReason.ServerUnreachable -> R.string.conexion_error_unreachable
            ConnectionErrorReason.RateLimited -> R.string.conexion_error_rate_limited
            ConnectionErrorReason.SessionExpired -> R.string.conexion_error_session_expired
            ConnectionErrorReason.Unexpected, null -> R.string.conexion_error_generic
        },
    )

/**
 * `FLAG_SECURE` mientras la pantalla está compuesta (§8): la contraseña no
 * sale en capturas ni en la vista de apps recientes.
 */
@Composable
private fun FlagSecureEffect() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}
