package ai.hermes.mama.core.controller

import ai.hermes.mama.contract.BrowserControllerCancelPayload
import ai.hermes.mama.contract.BrowserControllerCommandPayload
import ai.hermes.mama.contract.EventTypes
import ai.hermes.mama.contract.ToolStartPayload
import ai.hermes.mama.gateway.ChannelClosedException
import ai.hermes.mama.gateway.ChannelException
import ai.hermes.mama.gateway.GatewayClient
import ai.hermes.mama.gateway.GatewayEvent
import ai.hermes.mama.gateway.JsonRpcChannel
import ai.hermes.mama.gateway.JsonRpcException
import ai.hermes.mama.gateway.browserControllerHeartbeat
import ai.hermes.mama.gateway.detachBrowserController
import ai.hermes.mama.gateway.registerBrowserController
import ai.hermes.mama.gateway.sendBrowserControllerResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Estado del controlador de navegador expuesto a la UI (ROADMAP §5/F3, §2.6).
 */
public sealed interface ControllerState {
    /** Sin controlador: ni pedido ni registrado (estado inicial y tras [ControllerSession.detach]). */
    public data object Idle : ControllerState

    /**
     * `browser.controller.register` en vuelo, o pendiente de reintento porque el
     * socket murió a mitad del intento — la siguiente generación de client lo
     * retoma sola (mientras la intención siga viva).
     */
    public data object Registering : ControllerState

    /** Registrado en el servidor: comandos enrutados y heartbeat corriendo. */
    public data object Attached : ControllerState

    /**
     * El servidor respondió 4403 al registro (`browser.extension_control.enabled`
     * apagado, protocolo distinto de 1 o identidad no autenticada). La UI muestra
     * `browser_server_not_enabled` ("El servidor no tiene activado el navegador
     * compartido"); un `attach` explícito o una reconexión reintentan.
     */
    public data object ServerNotEnabled : ControllerState

    /** Cualquier otro fallo de registro (red, -32000…); [cause] es la excepción del canal. */
    public data class RegistrationFailed(
        val cause: Throwable,
    ) : ControllerState
}

/**
 * Señal de una sola vez hacia la UI (F3 la emite; F4/C8 navegan).
 */
public sealed interface ControllerSignal {
    /**
     * Primer `tool.start` `browser_*` de la sesión con el controlador aún no
     * pedido: la app debe navegar a la pantalla Navegador y mostrar el aviso
     * `browser_auto_open_notice` ("Hermes va a usar el navegador").
     */
    public data class NavigateToBrowser(
        val sessionId: String,
    ) : ControllerSignal
}

/**
 * Ciclo de vida del controlador de navegador de §2.6 (ROADMAP §5/F3): registra
 * UN controlador por app sobre el [GatewayClient] de cada generación de
 * conexión, ejecuta los `browser.controller.command` del broker en el
 * [WebViewController] y devuelve `browser.controller.result`.
 *
 * - **Registro**: [attach] (la pantalla Navegador se abre) o auto-registro al
 *   primer `tool.start` cuyo `name` empiece por `browser_` — en ese caso se
 *   emite [ControllerSignal.NavigateToBrowser] por [signals] **antes** de
 *   registrar (la UI navega y muestra el aviso; la navegación es de F4/C8).
 * - **Heartbeat**: `browser.controller.heartbeat` cada [heartbeatInterval]
 *   (20 s, §2.6) mientras el registro esté vivo ([ControllerState.Attached]).
 * - **Reconexión**: [clients] emite el `GatewayClient` de cada generación de
 *   conexión (uno por `Connected` del `ConnectionManager`); cada emisión nueva
 *   re-suscribe eventos — los `SharedFlow` del canal tienen `replay = 0` — y
 *   re-registra si la intención sigue viva. Eso cubre
 *   `ConnectionEvent.Reconnected` sin consumirlo aparte: re-registrar debe ir
 *   por el canal NUEVO.
 * - **Ruteo**: `controller_id`/`browser_profile_id`/`tool_call_id` del payload
 *   se leen ANTES de `BrowserCommand.from` (la conversión los descarta); un
 *   comando dirigido a otro controlador/perfil se ignora (un `result` para él
 *   sería rechazado por el broker de todos modos).
 * - **Resultados**: `ok:true` → `params.result` = el string JSON del outcome;
 *   `ok:false` → `params.error` = el mismo string `{"success":false,…}` — el
 *   broker lee `error` cuando `ok` es falso (methods_browser_control.py), así
 *   el fallo nunca viaja en `result`.
 * - **Cancelación**: `browser.controller.cancel {command_id}` →
 *   [WebViewController.cancel]; [detach] aborta todo lo en curso.
 *
 * Ciclo de vida del llamador (C8): [start] una vez al crearlo; [attach]/[detach]
 * al abrir/cerrar la pantalla Navegador; [close] al soltar la sesión. El dueño
 * del [clients] Flow es quien crea y cierra cada `GatewayClient` por generación
 * — esta clase nunca crea un segundo client sobre el mismo canal (duplicaría
 * la cola de `serverRequests`).
 *
 * [controllerId] debe venir con la forma §2.6 `android-<installation-uuid>`
 * (un UUID estable por instalación, persistido por la capa de ajustes — C8).
 *
 * §8: los logs llevan tipos de evento/excepción — nunca payloads ni URLs.
 */
public class ControllerSession(
    private val scope: CoroutineScope,
    private val clients: Flow<GatewayClient>,
    private val executor: WebViewController,
    val controllerId: String,
    val browserProfileId: String = DEFAULT_BROWSER_PROFILE_ID,
    private val heartbeatInterval: Duration = DEFAULT_HEARTBEAT_INTERVAL,
    private val logger: (String) -> Unit = {},
) {
    /**
     * Estado mutable compartido: las lecturas sueltas van por `@Volatile`; las
     * transiciones compuestas bajo [stateMutex]. `registeredOn` es el client
     * donde el registro está VIVO en el servidor (muere con su socket).
     */
    @Volatile
    private var boundSessionId: String? = null

    /** Intención de registro (attach llamado y sin detach): sobrevive a las reconexiones. */
    @Volatile
    private var wantsController = false

    @Volatile
    private var currentClient: GatewayClient? = null

    @Volatile
    private var registeredOn: GatewayClient? = null

    @Volatile
    private var closed = false

    private val started = AtomicBoolean(false)
    private val stateMutex = Mutex()

    /** Serializa los `browser.controller.register`: nunca dos RPC de registro a la vez. */
    private val registerMutex = Mutex()

    private var collectorJob: Job? = null
    private var eventsJob: Job? = null
    private var heartbeatJob: Job? = null

    /**
     * `command_id` recibidos cuyo resultado aún no salió por el wire. Se
     * rellena al despachar el evento — ANTES de que la corrutina llame a
     * `execute` (que es cuando el executor registra el job en su `inflight`).
     * Sin este set, un `cancel` que llega en esa ventana no encuentra el job
     * y el comando ejecutaría su side-effect pese a estar cancelado.
     */
    private val pendingCommands = ConcurrentHashMap.newKeySet<String>()

    /** `command_id` cancelados antes de entrar en `inflight` del executor. */
    private val cancelledCommands = ConcurrentHashMap.newKeySet<String>()

    private val mutableState = MutableStateFlow<ControllerState>(ControllerState.Idle)

    /** Estado del controlador para la UI ([ControllerState]). */
    public val state: StateFlow<ControllerState> = mutableState.asStateFlow()

    private val mutableSignals = MutableSharedFlow<ControllerSignal>(extraBufferCapacity = SIGNAL_BUFFER)

    /**
     * Señales de una sola vez ([ControllerSignal.NavigateToBrowser]). Sin
     * `replay`: suscribirse al abrir el chat, igual que `ApprovalController`.
     */
    public val signals: SharedFlow<ControllerSignal> = mutableSignals.asSharedFlow()

    init {
        require(controllerId.isNotBlank()) { "controllerId no puede estar vacío" }
        require(browserProfileId.isNotBlank()) { "browserProfileId no puede estar vacío" }
    }

    /**
     * Empieza a colectar [clients] (idempotente): hay que llamarlo al crear la
     * sesión — los eventos del canal tienen `replay = 0` y un suscriptor
     * tardío los pierde.
     */
    public fun start() {
        if (!started.compareAndSet(false, true)) {
            return
        }
        collectorJob = scope.launch { collectClients() }
    }

    /**
     * El controlador se quiere para [sessionId] (la pantalla Navegador se abre
     * — F4 — o cualquier otro disparador explícito). Idempotente por sesión:
     * repetir con la misma no re-registra; con otra hace `detach` de la
     * anterior y re-liga. No espera al RPC: el progreso sale por [state].
     */
    public fun attach(sessionId: String) {
        require(sessionId.isNotBlank()) { "sessionId no puede estar vacío" }
        scope.launch {
            var stale: Pair<GatewayClient, String>? = null
            val proceed =
                stateMutex.withLock {
                    when {
                        closed -> false
                        // Ya registrado para ESTA sesión: nada que hacer. Si el
                        // registro está en vuelo o falló (registeredOn == null)
                        // el attach actúa como «asegurar»: el registerMutex del
                        // intento absorbe el duplicado o reintenta tras el fallo.
                        boundSessionId == sessionId && wantsController && registeredOn != null -> false
                        else -> {
                            val oldClient = registeredOn
                            val oldSession = boundSessionId
                            if (oldClient != null && oldSession != null) {
                                stale = oldClient to oldSession
                            }
                            boundSessionId = sessionId
                            wantsController = true
                            registeredOn = null
                            heartbeatJob?.cancel()
                            heartbeatJob = null
                            true
                        }
                    }
                }
            // El registro anterior era de OTRA sesión: detach de cortesía. Va
            // bajo registerMutex para no adelantar a un register en vuelo por
            // el wire (sendMutex serializa la escritura, no el orden entre
            // corrutinas): detach→register mal ordenado dejaría el scope
            // borrado en el servidor con la app creyéndose Attached.
            stale?.let { (client, oldSession) ->
                registerMutex.withLock {
                    runCatching { client.detachBrowserController(oldSession) }
                        .onFailure { warn("browser.controller.detach de la sesión anterior no salió") }
                }
            }
            if (proceed) {
                executor.cancelAll()
                ensureRegistered()
            }
        }
    }

    /**
     * Dejar de querer el controlador (al salir de la pantalla Navegador o del
     * chat): `browser.controller.detach` de cortesía, heartbeat parado y
     * comandos en curso abortados. La sesión queda desligada; un `tool.start`
     * `browser_*` posterior puede auto-registrar de nuevo.
     */
    public suspend fun detach() {
        val target =
            stateMutex.withLock {
                wantsController = false
                val previous = registeredOn?.let { it to boundSessionId }
                registeredOn = null
                boundSessionId = null
                heartbeatJob?.cancel()
                heartbeatJob = null
                mutableState.value = ControllerState.Idle
                previous
            }
        executor.cancelAll()
        val (client, sessionId) = target ?: return
        if (sessionId != null) {
            // Bajo registerMutex: el detach espera a un register en vuelo en
            // vez de adelantarlo por el wire (orden invertido = scope borrado
            // en servidor tras registrarse — ver attach()).
            registerMutex.withLock {
                runCatching { client.detachBrowserController(sessionId) }
                    .onFailure { warn("browser.controller.detach no salió") }
            }
        }
    }

    /**
     * Cierre total: [detach] y fin de la colecta — ningún client nuevo vuelve a
     * registrar después. Idempotente.
     */
    public suspend fun close() {
        closed = true
        collectorJob?.cancel()
        eventsJob?.cancel()
        detach()
    }

    // ----------------------------------------------------------- clients ----

    /**
     * Un `GatewayClient` por generación de conexión. El discriminante es el
     * [JsonRpcChannel] (un canal == una generación): si la app emitiera dos
     * clients sobre el mismo canal no hay re-registro redundante.
     */
    private suspend fun collectClients() {
        var lastChannel: JsonRpcChannel? = null
        clients.collect { client ->
            if (client.channel === lastChannel || closed) {
                return@collect
            }
            lastChannel = client.channel
            onNewClient(client)
        }
    }

    /**
     * Nueva generación: el registro viejo murió con su socket — se limpia, se
     * re-suscribe el colector de eventos (UNDISPATCHED por `replay = 0`) y se
     * re-registra si la intención sigue viva.
     */
    private suspend fun onNewClient(client: GatewayClient) {
        stateMutex.withLock {
            if (closed) {
                return
            }
            currentClient = client
            registeredOn = null
            heartbeatJob?.cancel()
            heartbeatJob = null
            // Los comandos del socket muerto ya no pueden entregar resultado:
            // el broker los resuelve al ver la desconexión — se abortan aquí.
            executor.cancelAll()
            eventsJob?.cancel()
            eventsJob =
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    collectEvents(client)
                }
            if (wantsController) {
                boundSessionId?.let { sessionId ->
                    // El Attached anterior murió con el socket: reflejar el
                    // re-registro en vez de dejar el estado stale.
                    mutableState.value = ControllerState.Registering
                    scope.launch { registerOn(client, sessionId) }
                }
            }
        }
    }

    // ----------------------------------------------------------- eventos ----

    private suspend fun collectEvents(client: GatewayClient) {
        client.events.collect { event ->
            when (event.type) {
                EventTypes.BROWSER_CONTROLLER_COMMAND -> onCommandEvent(client, event)
                EventTypes.BROWSER_CONTROLLER_CANCEL -> onCancelEvent(client, event)
                EventTypes.TOOL_START -> onToolStart(client, event)
            }
        }
    }

    /**
     * `browser.controller.command` para la sesión ligada: valida los ids de
     * ruteo ANTES de `BrowserCommand.from` (que los descarta), convierte y
     * ejecuta en una corrutina aparte — el colector debe seguir leyendo (un
     * `cancel` no puede esperar a un `navigate` de 10 s).
     */
    private fun onCommandEvent(
        client: GatewayClient,
        event: GatewayEvent,
    ) {
        val sessionId = boundSessionId
        if (!wantsController || sessionId == null || event.sessionId != sessionId) {
            return
        }
        val payload = client.decodePayload(event, BrowserControllerCommandPayload.serializer())
        if (payload == null || !isForThisController(payload)) {
            return
        }
        val command = BrowserCommand.from(payload)
        pendingCommands += command.commandId
        scope.launch { executeAndReport(client, sessionId, command) }
    }

    /**
     * §2.6: `controller_id`/`browser_profile_id`/`tool_call_id` sólo existen en
     * el payload — `BrowserCommand.from` no los conserva, así que se validan
     * AQUÍ, antes de convertir. `tool_call_id` no viaja de vuelta: el resultado
     * se correlaciona por `command_id` (methods_browser_control.py). Un comando
     * dirigido a otro controlador/perfil se ignora — un `result` para él sería
     * rechazado por el broker de todos modos.
     */
    private fun isForThisController(payload: BrowserControllerCommandPayload): Boolean {
        val controllerMatch = payload.controllerId == null || payload.controllerId == controllerId
        val profileMatch = payload.browserProfileId == null || payload.browserProfileId == browserProfileId
        if (!controllerMatch || !profileMatch) {
            warn("comando para otro controlador/perfil ignorado (tool_call_id: ${payload.toolCallId != null})")
            return false
        }
        return true
    }

    /** `browser.controller.cancel {command_id}` → aborta el comando si sigue vivo. */
    private fun onCancelEvent(
        client: GatewayClient,
        event: GatewayEvent,
    ) {
        if (event.sessionId != boundSessionId) {
            return
        }
        val payload =
            client.decodePayload(event, BrowserControllerCancelPayload.serializer()) ?: return
        if (!executor.cancel(payload.commandId)) {
            // El job no está en el inflight del executor. Si el comando se
            // recibió pero su corrutina aún no llamó a execute(), tombstone:
            // executeAndReport lo lee antes de ejecutar y contesta cancelado
            // sin side-effect. (Si el executor ya lo registró, cancel() lo
            // coge aunque el job esté en New — ver WebViewController.cancel.)
            if (pendingCommands.contains(payload.commandId)) {
                cancelledCommands += payload.commandId
            } else {
                warn("browser.controller.cancel de un comando no vivo (${payload.commandId.take(MAX_TAG_CHARS)})")
            }
        }
    }

    /**
     * Primer `tool.start` `browser_*` con el controlador no pedido: emite
     * [ControllerSignal.NavigateToBrowser] (F4 navega + aviso) y auto-registra.
     * Los eventos de turno sólo llegan al transport que envió el prompt
     * (methods_prompts.py / §2.4), así que la `session_id` del tool.start ES la
     * sesión de chat activa — se liga a ella.
     */
    private fun onToolStart(
        client: GatewayClient,
        event: GatewayEvent,
    ) {
        val sessionId = event.sessionId ?: return
        val payload = client.decodePayload(event, ToolStartPayload.serializer()) ?: return
        if (payload.name.startsWith(BROWSER_TOOL_PREFIX)) {
            scope.launch { autoAttach(sessionId) }
        }
    }

    private suspend fun autoAttach(sessionId: String) {
        val trigger =
            stateMutex.withLock {
                if (closed || wantsController) {
                    false
                } else {
                    boundSessionId = sessionId
                    wantsController = true
                    true
                }
            }
        if (!trigger) {
            return
        }
        mutableSignals.emit(ControllerSignal.NavigateToBrowser(sessionId))
        ensureRegistered()
    }

    // ---------------------------------------------------------- registro ----

    /** Lanza el registro sobre el client actual si hace falta (intención viva, sin registro vigente). */
    private suspend fun ensureRegistered() {
        val target =
            stateMutex.withLock {
                val sessionId = boundSessionId
                val live = registeredOn
                when {
                    closed || !wantsController || sessionId == null -> null
                    live != null && !live.isClosed -> null // ya registrado en esta generación
                    else -> currentClient?.takeUnless { it.isClosed }?.let { it to sessionId }
                }
            } ?: return
        val (client, sessionId) = target
        registerOn(client, sessionId)
    }

    /**
     * Un intento de `browser.controller.register` bajo [registerMutex] (nunca
     * dos a la vez; el re-check bajo lock absorbe el attach repetido). Si la
     * intención cambió durante el RPC (detach/close/otra generación) se deshace
     * con un `detach` — nunca un registro huérfano en el servidor.
     */
    private suspend fun registerOn(
        client: GatewayClient,
        sessionId: String,
    ) {
        // El RPC va bajo registerMutex; el outcome se procesa FUERA — el
        // `detach` de cortesía del undo también pasa por registerMutex y un
        // Mutex no es reentrante.
        val outcome =
            registerMutex.withLock {
                val skip =
                    stateMutex.withLock {
                        closed || !wantsController || boundSessionId != sessionId || registeredOn === client
                    }
                if (skip) {
                    return
                }
                mutableState.value = ControllerState.Registering
                runCatching {
                    client.registerBrowserController(
                        sessionId = sessionId,
                        controllerId = controllerId,
                        browserProfileId = browserProfileId,
                        capabilities = BrowserCommand.Actions.CAPABILITIES,
                    )
                }
            }
        onRegisterOutcome(client, sessionId, outcome.exceptionOrNull())
    }

    /**
     * Resultado del intento de registro: éxito → [onRegistered]; los fallos se
     * clasifican — 4403 = el servidor no tiene el flag (ServerNotEnabled),
     * canal muerto a mitad de RPC = la próxima generación reintenta (no es un
     * fallo de registro), cualquier otro → [ControllerState.RegistrationFailed].
     */
    private suspend fun onRegisterOutcome(
        client: GatewayClient,
        sessionId: String,
        failure: Throwable?,
    ) {
        when {
            failure == null -> onRegistered(client, sessionId)

            failure is CancellationException -> throw failure

            failure is JsonRpcException && failure.code == BROWSER_DISABLED_CODE -> {
                warn("browser.controller.register rechazado 4403 (flag apagado/protocolo/identidad)")
                // Un fallo en vuelo que llega tras detach() no debe pisar Idle
                // con un error: sólo se escribe si la intención sigue viva.
                // (`closed` no hace falta: close()→detach() apaga wantsController.)
                stateMutex.withLock {
                    if (wantsController && boundSessionId == sessionId && client === currentClient) {
                        mutableState.value = ControllerState.ServerNotEnabled
                    }
                }
            }

            failure is ChannelClosedException -> {
                // Socket muerto a mitad del intento: NO es un fallo de
                // registro — la próxima generación reintentará; el estado
                // queda Registering mientras la intención siga viva.
                warn("socket muerto durante browser.controller.register")
            }

            else ->
                stateMutex.withLock {
                    if (wantsController && boundSessionId == sessionId && client === currentClient) {
                        mutableState.value = ControllerState.RegistrationFailed(failure)
                    }
                }
        }
    }

    /**
     * Registro aceptado por el servidor. Si la intención o la generación
     * cambiaron durante el RPC se deshace con un `detach` de cortesía — nunca
     * un registro huérfano —; si no, fija [ControllerState.Attached] y arranca
     * el heartbeat sobre el client vivo.
     */
    private suspend fun onRegistered(
        client: GatewayClient,
        sessionId: String,
    ) {
        val undo =
            stateMutex.withLock {
                val stale = closed || !wantsController || boundSessionId != sessionId || client !== currentClient
                if (stale) {
                    true
                } else {
                    registeredOn = client
                    mutableState.value = ControllerState.Attached
                    heartbeatJob?.cancel()
                    heartbeatJob = scope.launch { heartbeatLoop(client, sessionId) }
                    false
                }
            }
        if (undo) {
            // registerMutex ya no lo tenemos (registerOn lo suelta antes de
            // procesar el outcome): el undo se encola tras cualquier RPC de
            // registro en curso — el orden por el wire queda garantizado.
            registerMutex.withLock {
                runCatching { client.detachBrowserController(sessionId) }
                    .onFailure { warn("detach del registro deshecho no salió") }
            }
        }
    }

    /** `browser.controller.heartbeat` cada [heartbeatInterval] mientras el registro viva (§2.6). */
    private suspend fun heartbeatLoop(
        client: GatewayClient,
        sessionId: String,
    ) {
        // `delay` es el punto de cancelación: cancelar el job mata el bucle.
        while (true) {
            delay(heartbeatInterval)
            try {
                client.browserControllerHeartbeat(sessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ChannelClosedException) {
                // Canal muerto: el re-registro de la próxima generación abre otro bucle.
                warn("heartbeat sobre canal muerto (${e::class.simpleName})")
                return
            } catch (e: JsonRpcException) {
                if (e.code == BROWSER_DISABLED_CODE) {
                    // 4403 con socket vivo: el servidor olvidó el scope (wipe
                    // de estado). Reintentar heartbeat no sirve — el registro
                    // está muerto: se limpia y se re-registra por este canal.
                    warn("heartbeat 4403 — scope caído en servidor, re-registrando")
                    stateMutex.withLock {
                        if (registeredOn === client) {
                            registeredOn = null
                            mutableState.value = ControllerState.Registering
                        }
                    }
                    scope.launch { ensureRegistered() }
                    return
                }
                warn("browser.controller.heartbeat falló (${e::class.simpleName})")
            } catch (
                @Suppress("TooGenericExceptionCaught") e: ChannelException,
            ) {
                // Error respondido por el servidor o timeout del RPC: el socket
                // sigue vivo — se reintenta al siguiente intervalo (autocorrección).
                warn("browser.controller.heartbeat falló (${e::class.simpleName})")
            }
        }
    }

    // --------------------------------------------------------- ejecución ----

    /**
     * Ejecuta el comando y devuelve `browser.controller.result`: `ok:true` →
     * `params.result` con el JSON tal cual; `ok:false` → `params.error` con el
     * `{"success":false,…}` (§2.6 — el broker lee `error`, no `result`, cuando
     * `ok` es falso). El envío va por el MISMO client que entregó el comando.
     */
    private suspend fun executeAndReport(
        client: GatewayClient,
        sessionId: String,
        command: BrowserCommand,
    ) {
        try {
            val outcome =
                if (cancelledCommands.remove(command.commandId)) {
                    // Cancelado en la ventana evento→execute: resultado §2.6
                    // sin tocar el WebView.
                    BrowserCommandOutcome.failure("Command cancelled")
                } else {
                    executor.execute(command)
                }
            runCatching {
                client.sendBrowserControllerResult(
                    sessionId = sessionId,
                    commandId = command.commandId,
                    ok = outcome.ok,
                    resultJson = outcome.resultJson.takeIf { outcome.ok },
                    error = outcome.resultJson.takeUnless { outcome.ok },
                )
            }.onFailure {
                if (it is CancellationException) throw it
                warn("browser.controller.result no salió (${command.action.take(MAX_TAG_CHARS)})")
            }
        } finally {
            pendingCommands -= command.commandId
            cancelledCommands -= command.commandId
        }
    }

    /** §8: el logger viene de fuera — uno que lanza no puede tumbar la sesión. */
    private fun warn(message: String) {
        runCatching { logger(message) }
    }

    public companion object {
        /** `browser_profile_id` del WebView de la app (§2.6). */
        public const val DEFAULT_BROWSER_PROFILE_ID: String = "mama-webview"

        /** Intervalo de heartbeat §2.6. */
        public val DEFAULT_HEARTBEAT_INTERVAL: Duration = 20.seconds

        /** Prefijo `browser_*` de los tools que disparan el auto-registro (§2.6). */
        private const val BROWSER_TOOL_PREFIX = "browser_"

        /** Código del backend para el controlador deshabilitado (flag/protocolo/identidad). */
        private const val BROWSER_DISABLED_CODE = 4403

        private const val SIGNAL_BUFFER = 8
        private const val MAX_TAG_CHARS = 64
    }
}
