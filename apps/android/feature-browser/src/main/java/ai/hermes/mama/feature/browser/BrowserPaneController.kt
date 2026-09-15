package ai.hermes.mama.feature.browser

import ai.hermes.mama.contract.BrowserProgressPayload
import ai.hermes.mama.contract.EventTypes
import ai.hermes.mama.core.controller.ControllerSession
import ai.hermes.mama.core.controller.ControllerState
import ai.hermes.mama.core.controller.WebViewController
import ai.hermes.mama.gateway.GatewayClient
import ai.hermes.mama.gateway.JsonRpcChannel
import ai.hermes.mama.gateway.interruptSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Presentador de la pantalla Navegador (ROADMAP §5/F4): funde el estado del
 * [ControllerSession], el «comando en curso» del [WebViewController] y los
 * `browser.progress` del canal vigente en [uiState], y despacha las acciones
 * de la pantalla.
 *
 * - [start] (al abrir la pantalla): `session.attach(sessionId)` — abrir la
 *   pantalla ES querer el controlador — y colecta los `browser.progress` del
 *   client vigente. [clients] entrega un `GatewayClient` por generación de
 *   conexión, como en [ControllerSession]: tras un `Reconnected` el colector
 *   se re-suscribe solo (los `SharedFlow` del canal tienen `replay = 0`).
 * - [stop] (al salir de la pantalla): suelta los colectores — **sin detach**:
 *   §5/F4 el controlador sigue registrado en segundo plano hasta que se cierra
 *   el chat. El `detach`/`close` lo decide quien posee el [ControllerSession].
 * - [stopHermes] (botón **Parar**): `executor.cancelAll()` para abortar el
 *   comando en curso sin esperar al viaje del RPC, y `session.interrupt` por
 *   el client vivo — el servidor cancela el turno y retira los comandos
 *   pendientes con `browser.controller.cancel`.
 *
 * Ciclo de vida del llamador (C8): una instancia por sesión de chat; [start]/
 * [stop] al montar/desmontar la pantalla. Los logs llevan tipos de
 * evento/excepción — nunca payloads ni URLs (§8).
 */
class BrowserPaneController(
    private val clients: Flow<GatewayClient>,
    private val session: ControllerSession,
    private val executor: WebViewController,
    private val sessionId: String,
    private val scope: CoroutineScope,
    private val autoOpenNoticeMs: Long = AUTO_OPEN_NOTICE_MS,
    private val logger: (String) -> Unit = {},
    /**
     * Gestor de descargas de la sesión (§5/G1); `null` = WebView sin
     * `DownloadListener` funcional (tests que no lo ejercitan). La pantalla lo
     * enchufa al `downloadHandler` del [AndroidWebViewDriver].
     */
    val downloads: BrowserDownloadManager? = null,
) {
    private val started = AtomicBoolean(false)

    /** Último `browser.progress.message` no vacío de la sesión ligada. */
    private val progress = MutableStateFlow<String?>(null)

    private val noticeVisible = MutableStateFlow(false)

    /** El client de la generación vigente (para `session.interrupt`). */
    @Volatile
    private var currentClient: GatewayClient? = null

    private var clientsJob: Job? = null
    private var eventsJob: Job? = null
    private var noticeJob: Job? = null

    /**
     * Estado único para Compose (§5/F4 + §5/G1): fase del registro + último
     * progreso + comando en curso + aviso de auto-apertura + descarga pendiente
     * de mostrar en la hoja inferior.
     */
    val uiState: StateFlow<BrowserPaneState> =
        combine(
            session.state,
            executor.busy,
            progress,
            noticeVisible,
            downloads?.completed ?: EMPTY_DOWNLOAD,
            ::paneState,
        ).stateIn(scope, SharingStarted.WhileSubscribed(5_000), BrowserPaneState())

    /**
     * Abrir la pantalla: pide el controlador para [sessionId] (idempotente —
     * si el auto-registro de `tool.start browser_*` ya corrió, no re-registra)
     * y suscribe los eventos del canal. Con [autoOpenNotice] muestra el aviso
     * "Hermes va a usar el navegador" unos segundos (§5/F3).
     */
    fun start(autoOpenNotice: Boolean = false) {
        if (!started.compareAndSet(false, true)) {
            return
        }
        // Apertura limpia: ni progreso ni aviso heredados de una visita anterior.
        progress.value = null
        noticeVisible.value = autoOpenNotice
        if (autoOpenNotice) {
            noticeJob =
                scope.launch {
                    delay(autoOpenNoticeMs)
                    noticeVisible.value = false
                }
        }
        clientsJob = scope.launch { collectClients() }
        session.attach(sessionId)
    }

    /**
     * Salir de la pantalla: suelta colectores para que el usuario pueda
     * re-entrar con [start]. NUNCA hace `session.detach()` — §5/F4: el
     * controlador sigue registrado en segundo plano hasta cerrar el chat.
     */
    fun stop() {
        started.set(false)
        clientsJob?.cancel()
        clientsJob = null
        eventsJob?.cancel()
        eventsJob = null
        noticeJob?.cancel()
        noticeJob = null
        noticeVisible.value = false
        currentClient = null
    }

    /**
     * **Parar** (§5/F4): cancela el comando en curso en el acto — sin esperar
     * al viaje del RPC — y pide al servidor `session.interrupt` (que a su vez
     * retira los comandos pendientes con `browser.controller.cancel`). Sin
     * client vivo (canal caído) queda sólo la cancelación local — el siguiente
     * intento de registro ya corre sobre la nueva generación.
     */
    fun stopHermes() {
        executor.cancelAll()
        val client = currentClient ?: return
        scope.launch {
            try {
                client.interruptSession(sessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // ChannelException y cualquier otra sorpresa: «Parar» es
                // best-effort, jamás puede tumbar la pantalla (§8: sólo tipos).
                warn("session.interrupt no salió (${e::class.simpleName})")
            }
        }
    }

    // ------------------------------------------------- hoja de descarga (G1) --

    /** Cierra la hoja inferior (scrim, atrás o «ya la vi»). */
    fun dismissDownload() {
        downloads?.dismissSheet()
    }

    /** «Abrir» — `ACTION_VIEW` al `content://` de MediaStore. */
    fun openDownload() {
        downloads?.openCurrent()
    }

    /** «Compartir» — `ACTION_SEND` con chooser. */
    fun shareDownload() {
        downloads?.shareCurrent()
    }

    /** «Enviar a Hermes» — cableado ya; el botón está deshabilitado hasta G2. */
    fun sendDownloadToHermes() {
        downloads?.sendCurrentToHermes()
    }

    // ------------------------------------------------------------- interno --

    /**
     * Un `GatewayClient` por generación de conexión — el discriminante es el
     * [JsonRpcChannel], como en [ControllerSession]. Cada emisión re-suscribe
     * la colecta de `browser.progress` (UNDISPATCHED: `replay = 0`).
     */
    private suspend fun collectClients() {
        var lastChannel: JsonRpcChannel? = null
        clients.collect { client ->
            if (client.channel === lastChannel) {
                return@collect
            }
            lastChannel = client.channel
            currentClient = client
            eventsJob?.cancel()
            eventsJob =
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    collectProgress(client)
                }
            // Carrera con stop(): una emisión en vuelo puede lanzar el
            // colector DESPUÉS de eventsJob=null — abortarlo al instante.
            if (!started.get()) {
                eventsJob?.cancel()
                eventsJob = null
            }
        }
    }

    /** `browser.progress` de la sesión ligada → último mensaje no vacío. */
    private suspend fun collectProgress(client: GatewayClient) {
        client.eventsFor(sessionId).collect { event ->
            if (event.type != EventTypes.BROWSER_PROGRESS) {
                return@collect
            }
            val payload =
                client.decodePayload(event, BrowserProgressPayload.serializer())
                    ?: return@collect
            val message = payload.message.trim()
            if (message.isNotEmpty()) {
                progress.value = message
            }
        }
    }

    /** §8: el logger viene de fuera — uno que lanza no puede tumbar el panel. */
    private fun warn(message: String) {
        runCatching { logger(message) }
    }

    companion object {
        /** Segundos visibles del aviso "Hermes va a usar el navegador" (§5/F3). */
        const val AUTO_OPEN_NOTICE_MS: Long = 4_000
    }
}

/** Constructor posicional para el `combine` de 5 flows (nombres en el data class). */
private fun paneState(
    state: ControllerState,
    busy: Boolean,
    progressMessage: String?,
    notice: Boolean,
    download: DownloadedDoc?,
): BrowserPaneState =
    BrowserPaneState(
        phase = browserPhaseFor(state),
        progressMessage = progressMessage,
        commandInFlight = busy,
        showAutoOpenNotice = notice,
        download = download,
    )

/** `completed` del gestor cuando no hay gestor: siempre `null` (sin hoja). */
private val EMPTY_DOWNLOAD = MutableStateFlow<DownloadedDoc?>(null)
