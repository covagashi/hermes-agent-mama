package ai.hermes.mama.feature.chat.chats

import ai.hermes.mama.core.storage.OpenedChat
import ai.hermes.mama.core.storage.SessionRepository
import ai.hermes.mama.gateway.ConnectionState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * ViewModel de la pantalla Chats (ROADMAP C3): la lista cacheada de
 * [SessionRepository] + la franja de [ConnectionState] + las acciones
 * (crear, abrir, borrar con confirmación, pull-to-refresh).
 *
 * - **Offline primero**: la lista sale sólo de Room ([SessionRepository.chats]);
 *   sin red se ve la última lista y la franja de conexión lo avisa (aceptación
 *   C3). Un [refresh] fallido nunca borra la caché (el repositorio ya lo
 *   garantiza: la red que falla no toca Room).
 * - **Crear**: `session.create` con título "Chat de <fecha>" — Hermes lo
 *   renombra después vía `session.title` (el reducer de B6 ya aplica ese
 *   evento). El chat nace `localOnly` y aparece arriba de la lista al instante.
 * - **Navegación**: [navigation] emite el [OpenedChat] tras un `open`/`create`
 *   con éxito — es la señal que la pantalla (y en C8 el NavHost) consume;
 *   el ViewModel no conoce destinos.
 * - **Errores**: nunca propagan a la UI como excepción — [notices] lleva un
 *   [ChatsNotice] que la pantalla traduce a texto humano (snackbar).
 */
class ChatsViewModel(
    private val repository: SessionRepository,
    connectionState: Flow<ConnectionState>,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val newChatTitle: (nowMillis: Long) -> String = ::defaultNewChatTitle,
) : ViewModel() {
    private val loaded = MutableStateFlow(false)
    private val refreshing = MutableStateFlow(false)
    private val creating = MutableStateFlow(false)
    private val openingStoredId = MutableStateFlow<String?>(null)
    private val pendingDelete = MutableStateFlow<ChatRowUi?>(null)

    private val chatRows =
        repository.chats.map { entities ->
            // `loaded` marca cuándo la caché ha hablado al menos una vez (para
            // no pintar "Aún no hay chats" en el arranque mientras Room responde).
            loaded.value = true
            entities.map { entity -> entity.toRowUi(nowMillis()) }
        }

    private val banner =
        connectionState.map { state ->
            when (state) {
                // Connected/Connecting no pintan franja: "sólo cuando falla" (§3).
                is ConnectionState.Connected -> null
                ConnectionState.Connecting -> null
                is ConnectionState.Reconnecting -> ChatsBanner.Reconnecting
                is ConnectionState.Failed -> ChatsBanner.Failed
                ConnectionState.Disconnected -> ChatsBanner.Disconnected
            }
        }

    /**
     * `stateIn` con `Eagerly`: Chats es la pantalla raíz — el VM vive lo que la
     * pantalla y su upstream es barato (una Flow de Room + dos StateFlow).
     * Además `uiState.value` siempre refleja lo último sin exigir un colector
     * activo (lo que simplifica los tests JVM).
     */
    val uiState: StateFlow<ChatsUiState> =
        combine(
            chatRows,
            banner,
            loaded,
            refreshing,
            creating,
        ) { rows, bannerValue, isLoaded, isRefreshing, isCreating ->
            ChatsUiState(
                chats = rows,
                banner = bannerValue,
                loaded = isLoaded,
                refreshing = isRefreshing,
                creating = isCreating,
            )
        }.combine(openingStoredId) { state, opening -> state.copy(openingStoredId = opening) }
            .combine(pendingDelete) { state, pending -> state.copy(pendingDelete = pending) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = ChatsUiState(),
            )

    private val _navigation = MutableSharedFlow<OpenedChat>(extraBufferCapacity = NAVIGATION_BUFFER)

    /** Señal de navegación (mock hasta C8): el chat ya abierto/creado al que ir. */
    val navigation: SharedFlow<OpenedChat> = _navigation.asSharedFlow()

    private val _notices = MutableSharedFlow<ChatsNotice>(extraBufferCapacity = NOTICE_BUFFER)

    /** Avisos humanos de una sola vez (la pantalla los pinta como snackbar). */
    val notices: SharedFlow<ChatsNotice> = _notices.asSharedFlow()

    init {
        // Refresco silencioso de arranque: si la lista del servidor cambió
        // mientras la app estaba cerrada, se ve al entrar. Un fallo aquí NO
        // genera aviso — la franja de conexión ya cuenta la historia.
        refresh(silent = true)
    }

    /**
     * Pull-to-refresh (y el refresco silencioso del arranque): `session.list`
     * → Room → la lista se recompone sola. Los fallos explícitos avisan; los
     * silenciosos no.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // cualquier fallo del RPC = aviso humano
    fun refresh(silent: Boolean = false) {
        if (refreshing.value) {
            return
        }
        // La marca es síncrona (antes del launch): un segundo gesto durante el
        // refresco no encola otro `session.list`.
        refreshing.value = true
        viewModelScope.launch {
            try {
                repository.refreshList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!silent) {
                    _notices.tryEmit(ChatsNotice.RefreshFailed)
                }
            } finally {
                refreshing.value = false
            }
        }
    }

    /**
     * "＋ Nuevo chat": crea el chat y emite la señal de navegación con el
     * [OpenedChat] resultante. Mientras corre, [ChatsUiState.creating] quita el
     * botón (un doble tap no crea dos chats).
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // cualquier fallo del RPC = aviso humano
    fun createChat() {
        if (creating.value) {
            return
        }
        // Marca síncrona: un doble tap en "＋ Nuevo chat" no crea dos chats.
        creating.value = true
        viewModelScope.launch {
            try {
                _navigation.emit(repository.create(newChatTitle(nowMillis())))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _notices.tryEmit(ChatsNotice.CreateFailed)
            } finally {
                creating.value = false
            }
        }
    }

    /**
     * Tap en una fila: `session.resume` (vía [SessionRepository.open]) y
     * navegación. Sólo una apertura en vuelo — la fila pulsada queda marcada en
     * [ChatsUiState.openingStoredId].
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // cualquier fallo del RPC = aviso humano
    fun openChat(row: ChatRowUi) {
        if (openingStoredId.value != null) {
            return
        }
        // Marca síncrona: dos taps seguidos no disparan dos `session.resume`.
        openingStoredId.value = row.storedId
        viewModelScope.launch {
            try {
                _navigation.emit(repository.open(row.storedId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _notices.tryEmit(ChatsNotice.OpenFailed)
            } finally {
                openingStoredId.value = null
            }
        }
    }

    /** Swipe (o acción de TalkBack) sobre una fila → diálogo de confirmación. */
    fun requestDelete(row: ChatRowUi) {
        pendingDelete.value = row
    }

    /** "Conservar" / gesto de cierre: se va el diálogo, no pasa nada. */
    fun dismissDelete() {
        pendingDelete.value = null
    }

    /**
     * "Borrar" confirmado: el diálogo se cierra ya y el borrado corre en
     * segundo plano — Room quita la fila cuando el remoto confirma (B6: si el
     * `session.delete` remoto falla, la caché no se toca y la fila queda).
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // cualquier fallo del RPC = aviso humano
    fun confirmDelete() {
        val row = pendingDelete.value ?: return
        pendingDelete.value = null
        viewModelScope.launch {
            try {
                repository.delete(row.storedId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _notices.tryEmit(ChatsNotice.DeleteFailed)
            }
        }
    }

    private companion object {
        const val NAVIGATION_BUFFER = 8
        const val NOTICE_BUFFER = 8
    }
}

private val SPANISH: Locale = Locale.forLanguageTag("es")
private val NEW_CHAT_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d 'de' MMMM", SPANISH)

/**
 * Título por defecto de un chat nuevo (C3): "Chat de 15 de septiembre".
 * Hermes lo renombra después vía `session.title`.
 */
fun defaultNewChatTitle(
    nowMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val date = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    return "Chat de ${date.format(NEW_CHAT_DATE_FORMAT)}"
}
