package ai.hermes.mama.feature.chat.chats

import ai.hermes.mama.core.storage.OpenedChat
import ai.hermes.mama.core.ui.components.BigButton
import ai.hermes.mama.core.ui.components.EmptyState
import ai.hermes.mama.core.ui.components.MamaButtonVariant
import ai.hermes.mama.core.ui.components.TopBanner
import ai.hermes.mama.core.ui.theme.MamaDimens
import ai.hermes.mama.core.ui.theme.MamaTheme
import ai.hermes.mama.feature.chat.R
import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.QuestionMark
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Pantalla Chats (ROADMAP C3, mockup Main.dc.html): barra superior "Hermes" +
 * ayuda, franja de conexión sólo cuando falla, lista de chats con swipe para
 * borrar (confirmación), pull-to-refresh y el botón grande "＋ Nuevo chat".
 *
 * La navegación real llega en C8: [onOpenChat] es la señal — el VM emite el
 * [OpenedChat] tras `open`/`create` y aquí sólo se reenvía.
 */
@Composable
fun ChatsScreen(
    viewModel: ChatsViewModel,
    onOpenChat: (OpenedChat) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.navigation.collect { opened -> onOpenChat(opened) }
    }
    LaunchedEffect(viewModel) {
        viewModel.notices.collect { notice ->
            snackbarHostState.showSnackbar(context.getString(notice.textRes()))
        }
    }

    ChatsContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onChatClick = viewModel::openChat,
        onNewChat = viewModel::createChat,
        onRefresh = { viewModel.refresh() },
        onDeleteRequest = viewModel::requestDelete,
        onDeleteConfirm = viewModel::confirmDelete,
        onDeleteDismiss = viewModel::dismissDelete,
        modifier = modifier,
    )
}

/** Mapeo aviso → string humano (snackbar). */
private fun ChatsNotice.textRes(): Int =
    when (this) {
        ChatsNotice.CreateFailed -> R.string.chats_notice_create_failed
        ChatsNotice.OpenFailed -> R.string.chats_notice_open_failed
        ChatsNotice.DeleteFailed -> R.string.chats_notice_delete_failed
        ChatsNotice.RefreshFailed -> R.string.chats_notice_refresh_failed
    }

/**
 * La pantalla ya compuesta con su estado (sin ViewModel): la usan los tests de
 * Compose y las capturas de Roborazzi con datos de fixture.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsContent(
    state: ChatsUiState,
    snackbarHostState: SnackbarHostState,
    onChatClick: (ChatRowUi) -> Unit,
    onNewChat: () -> Unit,
    onRefresh: () -> Unit,
    onDeleteRequest: (ChatRowUi) -> Unit,
    onDeleteConfirm: () -> Unit,
    onDeleteDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var helpVisible by rememberSaveable { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(bottom = 0.dp),
        ) {
            state.banner?.let { banner -> TopBanner(text = bannerText(banner)) }
            ChatsTopBar(onHelp = { helpVisible = true })
            Box(modifier = Modifier.weight(1f)) {
                PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = onRefresh,
                ) {
                    if (state.chats.isEmpty()) {
                        EmptyPane(loaded = state.loaded)
                    } else {
                        ChatList(
                            chats = state.chats,
                            openingStoredId = state.openingStoredId,
                            onChatClick = onChatClick,
                            onDeleteRequest = onDeleteRequest,
                        )
                    }
                }
            }
            NewChatBar(onNewChat = onNewChat, enabled = !state.creating)
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    state.pendingDelete?.let { pending ->
        DeleteChatDialog(
            chat = pending,
            onConfirm = onDeleteConfirm,
            onDismiss = onDeleteDismiss,
        )
    }
    if (helpVisible) {
        ChatsHelpDialog(onDismiss = { helpVisible = false })
    }
}

/** Texto de la franja de conexión según el estado (ROADMAP §3: "sólo cuando falla"). */
@Composable
private fun bannerText(banner: ChatsBanner): String =
    stringResource(
        when (banner) {
            ChatsBanner.Reconnecting -> R.string.chats_banner_reconnecting
            ChatsBanner.Failed -> R.string.chats_banner_failed
            ChatsBanner.Disconnected -> R.string.chats_banner_disconnected
        },
    )

/** Barra del mockup: 72 dp, título "Hermes" y el botón de ayuda "?". */
@Composable
private fun ChatsTopBar(onHelp: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = MamaDimens.TopBarHeight)
                        .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.chats_screen_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier =
                        Modifier
                            .weight(1f)
                            .padding(start = 8.dp),
                )
                IconButton(
                    onClick = onHelp,
                    // ≥ 56 dp de objetivo táctil (el mockup dibuja 48; la regla manda).
                    modifier = Modifier.size(MamaDimens.MinTouchTarget),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.QuestionMark,
                        contentDescription = stringResource(R.string.chats_help_button),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(MamaDimens.IconSizeLarge),
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
    }
}

/** La lista: una fila por chat, clave estable = *stored* id. */
@Composable
private fun ChatList(
    chats: List<ChatRowUi>,
    openingStoredId: String?,
    onChatClick: (ChatRowUi) -> Unit,
    onDeleteRequest: (ChatRowUi) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(
            items = chats,
            key = { chat -> chat.storedId },
        ) { chat ->
            ChatListRow(
                chat = chat,
                onClick = { onChatClick(chat) },
                onDeleteRequested = { onDeleteRequest(chat) },
                // Mientras una apertura está en vuelo las demás filas esperan:
                // un doble tap no dispara dos `session.resume`.
                enabled = openingStoredId == null,
            )
        }
    }
}

/** Vacío (o carga inicial): scrollable para que el pull-to-refresh funcione igual. */
@Composable
private fun EmptyPane(loaded: Boolean) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (loaded) {
            EmptyState(
                title = stringResource(R.string.chats_empty_title),
                description = stringResource(R.string.chats_empty_body),
            )
        }
    }
}

/** Botón grande inferior del mockup: "＋ Nuevo chat" (píldora verde 64 dp). */
@Composable
private fun NewChatBar(
    onNewChat: () -> Unit,
    enabled: Boolean,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        BigButton(
            text = stringResource(R.string.chats_new_chat),
            onClick = onNewChat,
            enabled = enabled,
            variant = MamaButtonVariant.Primary,
            icon = Icons.Outlined.Add,
            iconContentDescription = null,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 28.dp),
        )
    }
}

@Preview(name = "Chats claro", showBackground = true, widthDp = 390, heightDp = 844)
@Preview(
    name = "Chats oscuro",
    showBackground = true,
    widthDp = 390,
    heightDp = 844,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ChatsScreenPreview() {
    MamaTheme {
        ChatsContent(
            state = previewState(),
            snackbarHostState = remember { SnackbarHostState() },
            onChatClick = {},
            onNewChat = {},
            onRefresh = {},
            onDeleteRequest = {},
            onDeleteConfirm = {},
            onDeleteDismiss = {},
        )
    }
}

@Preview(name = "Chats vacío", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun ChatsEmptyPreview() {
    MamaTheme {
        ChatsContent(
            state = ChatsUiState(loaded = true),
            snackbarHostState = remember { SnackbarHostState() },
            onChatClick = {},
            onNewChat = {},
            onRefresh = {},
            onDeleteRequest = {},
            onDeleteConfirm = {},
            onDeleteDismiss = {},
        )
    }
}

/** Los 4 chats del mockup (datos ficticios, también en strings_chats.xml). */
@Composable
internal fun previewState(): ChatsUiState =
    ChatsUiState(
        loaded = true,
        chats =
            listOf(
                previewChat(
                    titleRes = R.string.chats_example_title_1,
                    previewRes = R.string.chats_example_preview_1,
                    emoji = chatEmojiForTitle(stringResource(R.string.chats_example_title_1)),
                    label = ChatTimeLabel.Today(stringResource(R.string.chats_example_time_1)),
                    running = true,
                ),
                previewChat(
                    titleRes = R.string.chats_example_title_2,
                    previewRes = R.string.chats_example_preview_2,
                    emoji = chatEmojiForTitle(stringResource(R.string.chats_example_title_2)),
                    label = ChatTimeLabel.Yesterday,
                ),
                previewChat(
                    titleRes = R.string.chats_example_title_3,
                    previewRes = R.string.chats_example_preview_3,
                    emoji = chatEmojiForTitle(stringResource(R.string.chats_example_title_3)),
                    label = ChatTimeLabel.Weekday(stringResource(R.string.chats_example_time_3)),
                ),
                previewChat(
                    titleRes = R.string.chats_example_title_4,
                    previewRes = R.string.chats_example_preview_4,
                    emoji = chatEmojiForTitle(stringResource(R.string.chats_example_title_4)),
                    label = ChatTimeLabel.Date(stringResource(R.string.chats_example_time_4)),
                ),
            ),
    )

@Composable
private fun previewChat(
    titleRes: Int,
    previewRes: Int,
    emoji: String,
    label: ChatTimeLabel,
    running: Boolean = false,
) = ChatRowUi(
    storedId = "preview-$titleRes",
    title = stringResource(titleRes),
    preview = stringResource(previewRes),
    emoji = emoji,
    timeLabel = label,
    running = running,
)
