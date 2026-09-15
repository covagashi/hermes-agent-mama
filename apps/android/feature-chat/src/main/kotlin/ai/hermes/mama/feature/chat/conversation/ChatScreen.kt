package ai.hermes.mama.feature.chat.conversation

import ai.hermes.mama.core.ui.components.ActivityChip
import ai.hermes.mama.core.ui.components.ChatBubble
import ai.hermes.mama.core.ui.components.ChatBubbleAuthor
import ai.hermes.mama.core.ui.components.TopBanner
import ai.hermes.mama.core.ui.theme.MamaDimens
import ai.hermes.mama.core.ui.theme.MamaTheme
import ai.hermes.mama.feature.chat.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Pantalla Chat (ROADMAP C4, mockup `Chat.dc.html`): transcript cacheado +
 * streaming + actividad de Hermes + composer.
 *
 * La `LazyColumn` va invertida (`reverseLayout`): el índice 0 es lo más nuevo
 * — el ítem vivo del streaming va primero y la lista queda anclada abajo. El
 * auto-scroll sólo salta al 0 cuando la usuaria ya está al final; si subió a
 * leer, no se le mueve la pantalla.
 */
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val header by viewModel.header.collectAsStateWithLifecycle()
    val activity by viewModel.activity.collectAsStateWithLifecycle()
    val offline by viewModel.offline.collectAsStateWithLifecycle()
    val streaming by viewModel.liveStreaming.collectAsStateWithLifecycle()
    DisposableEffect(viewModel) {
        onDispose { viewModel.close() }
    }
    ChatContent(
        items = items,
        header = header,
        activity = activity,
        offline = offline,
        streaming = streaming,
        liveText = viewModel.liveText,
        notices = viewModel.notices,
        onSend = viewModel::send,
        onStop = viewModel::stop,
        onRetry = viewModel::retry,
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * Contenido sin estado de [ChatScreen] — los tests de Robolectric/Roborazzi lo
 * alimentan con datos fijos (`items` en orden cronológico).
 */
@Composable
internal fun ChatContent(
    items: List<ChatListItem>,
    header: ChatHeader,
    activity: ActivityKind?,
    offline: Boolean,
    streaming: Boolean,
    liveText: StateFlow<String>,
    notices: Flow<ChatNotice>,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onRetry: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val styles = rememberMarkdownStyles()
    val listState = rememberLazyListState()
    val notice = rememberNotice(notices)

    // Ítem vivo primero (posición "más nueva" de la lista invertida).
    val displayItems =
        remember(items, streaming) {
            buildList {
                if (streaming) {
                    add(ChatListItem.Message(ChatMessage(key = LIVE_KEY, author = ChatBubbleAuthor.Hermes, text = "")))
                }
                addAll(items.asReversed())
            }
        }

    // Auto-scroll al final sólo si la usuaria no ha subido a leer (índice 0-1).
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex <= AT_BOTTOM_INDEX } }
    LaunchedEffect(displayItems.size) {
        if (atBottom && displayItems.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        ChatTopBar(header = header, streaming = streaming, onBack = onBack)
        StatusBanner(offline = offline, notice = notice)
        Box(modifier = Modifier.weight(1f)) {
            if (displayItems.isEmpty()) {
                EmptyChat(modifier = Modifier.align(Alignment.Center))
            }
            MessageList(
                displayItems = displayItems,
                listState = listState,
                liveText = liveText,
                styles = styles,
                onRetry = onRetry,
            )
        }
        WorkingStrip(
            activity = activity,
            showStop = activity != null || streaming || header.running,
            onStop = onStop,
        )
        Composer(onSend = onSend)
    }
}

/** Aviso efímero del VM: lo enseña [NOTICE_VISIBLE_MS] ms y lo retira él solo. */
@Composable
private fun rememberNotice(notices: Flow<ChatNotice>): ChatNotice? {
    var notice by remember { mutableStateOf<ChatNotice?>(null) }
    LaunchedEffect(notices) {
        notices.collect { notice = it }
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(NOTICE_VISIBLE_MS)
            notice = null
        }
    }
    return notice
}

/** Franja superior: el aviso de offline tiene prioridad sobre los avisos del VM. */
@Composable
private fun StatusBanner(
    offline: Boolean,
    notice: ChatNotice?,
) {
    if (offline) {
        TopBanner(text = stringResource(R.string.chat_offline), icon = Icons.Outlined.WifiOff)
    } else {
        notice?.let { TopBanner(text = noticeText(it), icon = Icons.Outlined.Info) }
    }
}

/** Transcript + ítem vivo (lista invertida: `displayItems` ya viene en orden). */
@Composable
private fun MessageList(
    displayItems: List<ChatListItem>,
    listState: LazyListState,
    liveText: StateFlow<String>,
    styles: MarkdownStyles,
    onRetry: (String) -> Unit,
) {
    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(
            items = displayItems,
            key = { it.key },
            contentType = { it.contentType },
        ) { item ->
            when (item) {
                is ChatListItem.DayHeader -> DayHeaderRow(ts = item.ts)
                is ChatListItem.Message ->
                    if (item.message.key == LIVE_KEY) {
                        LiveBubble(liveText = liveText, styles = styles)
                    } else {
                        MessageRow(message = item.message, styles = styles, onRetry = onRetry)
                    }
            }
        }
    }
}

// --- transcript ---

@Composable
private fun DayHeaderRow(ts: Double) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Text(
                text = dayLabel(ts),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun MessageRow(
    message: ChatMessage,
    styles: MarkdownStyles,
    onRetry: (String) -> Unit,
) {
    if (message.failed) {
        // Envío fallido: burbuja + aviso; un solo nodo accesible que reintenta.
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { onRetry(message.key) }
                    .semantics(mergeDescendants = true) {},
            horizontalAlignment = Alignment.End,
        ) {
            ChatBubble(
                // El parse de markdown se memoiza: las filas del transcript se
                // recomponen con la lista y el texto no cambia.
                text = remember(message.text, styles) { markdownToAnnotated(message.text, styles) },
                author = message.author,
            )
            Text(
                text = stringResource(R.string.chat_retry),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp, end = 8.dp),
            )
        }
    } else {
        ChatBubble(
            text = remember(message.text, styles) { markdownToAnnotated(message.text, styles) },
            author = message.author,
            isError = message.isError,
            modifier = Modifier.alpha(if (message.pending) PENDING_ALPHA else 1f),
        )
    }
}

/**
 * La burbuja viva del turno: colecta `liveText` ELLA SOLA, así un `message.delta`
 * recompone este ítem y no el transcript entero (requisito de rendimiento C4).
 * `liveRegion` hace que TalkBack anuncie el texto nuevo al crecer.
 */
@Composable
private fun LiveBubble(
    liveText: StateFlow<String>,
    styles: MarkdownStyles,
) {
    val text by liveText.collectAsStateWithLifecycle()
    ChatBubble(
        text = if (text.isEmpty()) AnnotatedString(STREAMING_PLACEHOLDER) else markdownToAnnotated(text, styles),
        author = ChatBubbleAuthor.Hermes,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun EmptyChat(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.chat_empty),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(32.dp),
    )
}

// --- franja de actividad + Parar + composer ---

/** Franja fija sobre el composer: chip de actividad y botón «Parar». */
@Composable
private fun WorkingStrip(
    activity: ActivityKind?,
    showStop: Boolean,
    onStop: () -> Unit,
) {
    if (activity == null && !showStop) {
        return
    }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (activity != null) {
            ActivityChip(
                text = activityLabel(activity),
                modifier = Modifier.weight(1f),
            )
        }
        if (showStop) {
            Button(onClick = onStop, modifier = Modifier.heightIn(min = MamaDimens.MinTouchTarget)) {
                Text(text = stringResource(R.string.chat_stop))
            }
        }
    }
}

@Composable
private fun activityLabel(activity: ActivityKind): String =
    stringResource(
        when (activity) {
            ActivityKind.SearchWeb -> R.string.chat_activity_web
            ActivityKind.Browse -> R.string.chat_activity_browser
            ActivityKind.Files -> R.string.chat_activity_files
            ActivityKind.ReadEmail -> R.string.chat_activity_email
            ActivityKind.SendEmail -> R.string.chat_activity_send_email
            ActivityKind.Working -> R.string.chat_activity_working
        },
    )

// --- helpers ---

@Composable
private fun noticeText(notice: ChatNotice): String =
    when (notice) {
        ChatNotice.SendFailed -> stringResource(R.string.chat_send_failed)
        ChatNotice.InterruptFailed -> stringResource(R.string.chat_interrupt_failed)
        ChatNotice.GatewayError -> stringResource(R.string.chat_gateway_error)
        is ChatNotice.Info -> notice.text
    }

/** «Hoy» / «Ayer» / fecha media en el idioma del sistema (mensajes sin ts → hoy). */
@Composable
private fun dayLabel(ts: Double): String {
    val today = remember { LocalDate.now() }
    val day =
        remember(ts) {
            if (ts <= 0) {
                today
            } else {
                Instant
                    .ofEpochMilli((ts * MILLIS_PER_SECOND).toLong())
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
            }
        }
    return when (day) {
        today -> stringResource(R.string.chat_today)
        today.minusDays(1) -> stringResource(R.string.chat_yesterday)
        else -> day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }
}

private val ChatListItem.contentType: Int
    get() =
        when (this) {
            is ChatListItem.DayHeader -> CONTENT_DAY
            is ChatListItem.Message -> if (message.key == LIVE_KEY) CONTENT_LIVE else CONTENT_MESSAGE
        }

private const val LIVE_KEY = "live-turn"
private const val STREAMING_PLACEHOLDER = "…"
private const val NOTICE_VISIBLE_MS = 6_000L
private const val AT_BOTTOM_INDEX = 1
private const val MILLIS_PER_SECOND = 1_000.0
private const val PENDING_ALPHA = 0.6f
private const val CONTENT_DAY = 0
private const val CONTENT_MESSAGE = 1
private const val CONTENT_LIVE = 2

@Preview(name = "Chat claro", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun ChatContentPreview() {
    MamaTheme {
        ChatContent(
            items =
                listOf(
                    ChatListItem.DayHeader(key = "day-1", ts = 0.0),
                    ChatListItem.Message(
                        ChatMessage(
                            key = "msg-1",
                            author = ChatBubbleAuthor.User,
                            text = stringResource(R.string.chat_example_user),
                        ),
                    ),
                    ChatListItem.Message(
                        ChatMessage(
                            key = "msg-2",
                            author = ChatBubbleAuthor.Hermes,
                            text = stringResource(R.string.chat_example_hermes),
                        ),
                    ),
                ),
            header =
                ChatHeader(
                    title = stringResource(R.string.chat_example_title),
                    streaming = true,
                ),
            activity = ActivityKind.Browse,
            offline = false,
            streaming = true,
            liveText = MutableStateFlow(stringResource(R.string.chat_example_stream)),
            notices = emptyFlow(),
            onSend = {},
            onStop = {},
            onRetry = {},
            onBack = {},
        )
    }
}
