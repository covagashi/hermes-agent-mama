package ai.hermes.mama

import ai.hermes.mama.core.storage.MamaDatabase
import ai.hermes.mama.core.storage.SessionGateway
import ai.hermes.mama.core.storage.SessionRepository
import ai.hermes.mama.feature.chat.conversation.ChatGeneration
import ai.hermes.mama.feature.chat.conversation.ChatScreen
import ai.hermes.mama.feature.chat.conversation.ChatViewModel
import ai.hermes.mama.gateway.ConnectParams
import ai.hermes.mama.gateway.ConnectionManager
import ai.hermes.mama.gateway.ConnectionState
import ai.hermes.mama.gateway.GatewayClient
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import timber.log.Timber

/**
 * Host de desarrollo (C4): monta la pantalla Chat contra el FakeGateway
 * standalone (`:testing:run`) cuando el intent trae `fake_script`
 * (ver [DevGateway]). Inerte en el flavor `mama` — ahí el endpoint nunca se
 * activa y `MainActivity` no llega a crear esta clase.
 *
 * Una sola clase con el grafo dev: Room persistente + `ConnectionManager` con
 * su bucle de reconexión (B1/B2) + una [ChatGeneration] por socket `Connected`
 * — el `SessionRepository` (B6) y el `GatewayClient` (B4) se crean por
 * generación y el viejo repositorio se cierra al rotar.
 */
class DevChatHost(
    context: Context,
    private val endpoint: String,
    private val logger: (String) -> Unit = {},
) {
    /**
     * Scope propio (no `lifecycleScope`): `stop()` corre sobre él desde
     * `onDestroy`, cuando el scope de la activity ya está cancelado.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val db = MamaDatabase.create(context)
    private val http = OkHttpClient()

    /** Bucle de conexión B2 (reintentos con backoff) sobre el endpoint dev. */
    val manager: ConnectionManager =
        ConnectionManager(
            scope = scope,
            client = http,
            onBeforeConnect = { ConnectParams(url = endpoint) },
            logger = logger,
        )

    private var lastRepository: SessionRepository? = null

    /**
     * Una [ChatGeneration] por canal `Connected` distinto (misma forma que la
     * compondrá C8 en producción: reemite la vigente a suscriptores nuevos).
     */
    val generations: Flow<ChatGeneration> =
        manager.state
            .map { state -> (state as? ConnectionState.Connected)?.channel }
            .distinctUntilChanged()
            .filterNotNull()
            .map { channel ->
                lastRepository?.close()
                val client = GatewayClient(channel = channel, scope = scope, logger = logger)
                val repository =
                    SessionRepository(
                        gateway = SessionGateway.from(client),
                        db = db,
                        scope = scope,
                        logger = logger,
                    )
                lastRepository = repository
                ChatGeneration(repository = repository, client = client)
            }.shareIn(scope, SharingStarted.Eagerly, replay = 1)

    /** Arranca el bucle (idempotente). */
    fun start() {
        manager.connect()
    }

    /** Cierre ordenado (onDestroy del host): corre en [scope] y lo cancela al acabar. */
    fun stop() {
        scope
            .launch {
                lastRepository?.close()
                manager.disconnect()
            }.invokeOnCompletion { scope.cancel() }
    }
}

/**
 * Pantalla dev: espera la primera generación, elige (o crea) un chat y monta
 * [ChatScreen]. Sin navegación ni lista — el host de C4 sólo demuestra la
 * pantalla contra el guion `fake_script` activo.
 */
@Composable
fun DevChatScreen(
    host: DevChatHost,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var viewModel by remember { mutableStateOf<ChatViewModel?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(host) {
        host.generations.collect { generation ->
            if (viewModel == null) {
                viewModel =
                    ChatViewModel(
                        storedId = resolveChatId(generation),
                        generations = host.generations,
                        scope = scope,
                        connectionState = host.manager.state,
                        logger = Timber::w,
                    )
            }
        }
    }
    val chatViewModel = viewModel
    if (chatViewModel == null) {
        Column(
            modifier = modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
            Text(
                text = stringResource(R.string.dev_connecting),
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    } else {
        DisposableEffect(chatViewModel) {
            onDispose { chatViewModel.close() }
        }
        ChatScreen(viewModel = chatViewModel, onBack = onBack, modifier = modifier)
    }
}

/** Primer chat del listado o uno nuevo si el backend aún no tiene ninguno. */
private suspend fun resolveChatId(generation: ChatGeneration): String {
    runCatching { generation.repository.refreshList() }
    val cached =
        generation.repository.chats
            .first()
            .firstOrNull()
            ?.storedId
    return cached ?: generation.repository.create(title = null).storedId
}
