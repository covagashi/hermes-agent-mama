@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package ai.hermes.mama.gateway

import ai.hermes.mama.contract.ApprovalRequestParams
import ai.hermes.mama.contract.ClarifyRequestParams
import ai.hermes.mama.contract.ServerRequests
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Cliente tipado del gateway `hermes serve` (ROADMAP §2.3–§2.5, tarea B4):
 * envuelve un [JsonRpcChannel] ya conectado y añade la capa de contrato.
 *
 * - **RPC**: una función `suspend` por método de §2.3, repartidas por dominio en
 *   `GatewayClientSessionRpc.kt`, `GatewayClientPromptRpc.kt`,
 *   `GatewayClientRequestRpc.kt`, `GatewayClientBrowserRpc.kt` y
 *   `GatewayClientInfoRpc.kt`. Cada una serializa `params` con su DTO generado
 *   y decodifica el `result` al DTO de resultado; un result que no casa con el
 *   contrato produce [ResultDecodeException]. Las sobrecargas con argumentos
 *   sueltos fijan la forma que manda la app (`source`/`surface` = [APP_SOURCE]).
 *   Ningún string de método aparece fuera de `RpcMethods` (aceptación §5).
 * - **Eventos**: [events] es el passthrough del canal; [eventsFor] filtra por
 *   sesión conservando los broadcasts (`session_id == null`); [decodePayload]
 *   decodifica un payload a su DTO tolerando errores (`null` + log).
 * - **Peticiones servidor→cliente**: [serverRequests] las entrega ya
 *   clasificadas ([ApprovalRequest]/[ClarifyRequest]/[UnsupportedRequest]).
 *   El colector arranca `UNDISPATCHED` al construirse porque el `SharedFlow`
 *   del canal tiene `replay = 0` — así nada se pierde entre canal y client. Las
 *   `open_requests` re-entregadas por el canal tras una reconexión llegan por
 *   el mismo camino, marcadas `replayed` (no se duplican: la re-entrega es de B1).
 *
 * El [json] por defecto omite nulls y campos con su valor por defecto: el cable
 * sólo lleva lo que el llamador fijó explícitamente. El [logger] recibe avisos
 * sin contenido de wire (§8: tipos/tamaños/métodos, nunca payloads ni textos de
 * excepción, que pueden incrustar el input).
 *
 * El ciclo de vida es del llamador: [close] cancela el colector de peticiones
 * y cierra el canal (cancelar [scope] también lo detiene).
 */
class GatewayClient(
    /** Canal JSON-RPC envuelto (B1). Se expone para diagnóstico y cierre fino. */
    val channel: JsonRpcChannel,
    private val scope: CoroutineScope,
    internal val json: Json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        },
    private val logger: (message: String) -> Unit = {},
) {
    /** Todos los eventos del canal (§2.4), tal cual llegan. Suscribirse pronto: `replay = 0`. */
    val events: SharedFlow<GatewayEvent> = channel.events

    // Cola FIFO acotada, no SharedFlow: las peticiones esperan al colector en
    // vez de descartarse — una approval/clarify perdida dejaría el backend
    // colgado. Si se llena (nadie colecta nunca), dispatch responde -32603.
    private val _serverRequests = Channel<TypedServerRequest>(capacity = SERVER_REQUEST_QUEUE_CAPACITY)

    /**
     * Peticiones servidor→cliente ya clasificadas (§2.5):
     * [ApprovalRequest] y [ClarifyRequest] esperan respuesta de la usuaria;
     * [UnsupportedRequest] llega con su `-32601` ya enviado y sólo queda
     * mostrar el aviso humano ("Hermes necesita algo que esta app no puede dar").
     *
     * Cada petición se entrega a UN colector (cola FIFO): consumir una sola vez,
     * p. ej. desde el coordinador de peticiones de la UI.
     *
     * OJO: el `serverRequests` del canal es observador — una petición reclamada
     * por un `addServerRequestHandler` puede llegar aquí igualmente; se descarta
     * si ya está respondida, pero un handler que reclame y responda DESPUÉS vería
     * su respuesta pisoteada por la clasificación. No combinar ambos mecanismos
     * sobre un mismo canal.
     */
    val serverRequests: Flow<TypedServerRequest> = _serverRequests.receiveAsFlow()

    /** `true` cuando el canal envuelto está cerrado/muerto. */
    val isClosed: Boolean
        get() = channel.isClosed

    // `channel.serverRequests` es SharedFlow(replay = 0): suscribirse ya
    // (UNDISPATCHED corre el collect hasta la primera suspensión) para no
    // perder peticiones que lleguen entre la creación del canal y la del
    // client — la regla que B1 documenta para sus flujos. El job se guarda
    // para cancelarlo en [close]: sin eso cada generación de client muerta
    // quedaría suscrita al SharedFlow del canal (que nunca completa).
    internal val collectorJob =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            channel.serverRequests.collect { request -> dispatch(request) }
        }

    /**
     * Eventos dirigidos a [sessionId] **más** los broadcasts: el canal
     * normaliza el `session_id ""` del backend a `null` y esos eventos
     * (`sessions.changed`, `gateway.ready`, `skin.changed`…) interesan a todas
     * las pantallas — no se filtran fuera.
     */
    fun eventsFor(sessionId: String): Flow<GatewayEvent> =
        events.filter { event -> event.sessionId == null || event.sessionId == sessionId }

    /**
     * Decodifica el payload de un evento a su DTO generado (p. ej.
     * `decodePayload(event, StreamDeltaPayload.serializer())`). Devuelve `null`
     * — y deja aviso en el log — si el payload no casa; nunca lanza.
     */
    fun <T> decodePayload(
        event: GatewayEvent,
        deserializer: DeserializationStrategy<T>,
    ): T? =
        try {
            json.decodeFromJsonElement(deserializer, event.payload)
        } catch (e: SerializationException) {
            warn("payload de '${event.type.take(MAX_WIRE_TAG_CHARS)}' no decodifica (${e::class.simpleName})")
            null
        }

    /** Cierra el colector, la cola de peticiones y el canal (idempotente vía [JsonRpcChannel.close]). */
    suspend fun close() {
        collectorJob.cancel()
        _serverRequests.close()
        channel.close()
    }

    // --- RPC: implementación compartida de las extensiones GatewayClient*Rpc ---

    /** Llamada sin params (`gateway.ping`, `gateway.capabilities` → `{}`). */
    internal suspend fun <R> rpc(
        method: String,
        resultDeserializer: DeserializationStrategy<R>,
    ): R = decodeResult(method, channel.call(method), resultDeserializer)

    /** Llamada con params tipados: serializa el DTO, correlaciona y decodifica el result. */
    internal suspend fun <P, R> rpc(
        method: String,
        params: P,
        paramsSerializer: SerializationStrategy<P>,
        resultDeserializer: DeserializationStrategy<R>,
    ): R =
        decodeResult(
            method,
            channel.call(method, json.encodeToJsonElement(paramsSerializer, params)),
            resultDeserializer,
        )

    private fun <R> decodeResult(
        method: String,
        result: JsonElement,
        resultDeserializer: DeserializationStrategy<R>,
    ): R =
        try {
            json.decodeFromJsonElement(resultDeserializer, result)
        } catch (e: SerializationException) {
            throw ResultDecodeException(method, e)
        }

    // --- peticiones servidor→cliente ---

    @Suppress("TooGenericExceptionCaught")
    private fun dispatch(request: ServerRequest) {
        // El flujo del canal es observador: una petición ya respondida (p. ej.
        // por un addServerRequestHandler reclamante) llega igualmente — no
        // clasificar ni -32601 encima de la respuesta que ya salió.
        if (request.isAnswered) {
            return
        }
        val typed =
            try {
                classify(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warn("clasificación de '${request.method.take(MAX_WIRE_TAG_CHARS)}' falló (${e::class.simpleName})")
                if (!request.isAnswered) {
                    request.fail(
                        JSON_RPC_INTERNAL_ERROR,
                        "client error while handling ${request.method.take(MAX_WIRE_TAG_CHARS)}",
                    )
                }
                null
            } ?: return
        if (_serverRequests.trySend(typed).isFailure) {
            // Cola llena o cerrada: la petición no debe quedar colgando el backend.
            warn("petición '${request.method.take(MAX_WIRE_TAG_CHARS)}' sin entregar (cola llena/cerrada)")
            if (!request.isAnswered) {
                request.fail(JSON_RPC_INTERNAL_ERROR, "client request queue closed")
            }
        }
    }

    /**
     * `approval` y `clarify` se tipan con su DTO; cualquier otro método — o uno
     * conocido con params que no decodifican — cae a [UnsupportedRequest], que
     * envía `-32601` al construirse (§2.5).
     */
    private fun classify(request: ServerRequest): TypedServerRequest =
        when (request.method) {
            ServerRequests.APPROVAL ->
                decodeParams(request, ApprovalRequestParams.serializer())
                    ?.let { params -> ApprovalRequest(request, params, json) }

            ServerRequests.CLARIFY ->
                decodeParams(request, ClarifyRequestParams.serializer())
                    ?.let { params -> ClarifyRequest(request, params, json) }

            else -> null
        } ?: UnsupportedRequest(request)

    private fun <T> decodeParams(
        request: ServerRequest,
        deserializer: DeserializationStrategy<T>,
    ): T? =
        try {
            json.decodeFromJsonElement(deserializer, request.params)
        } catch (e: SerializationException) {
            warn("params de '${request.method.take(MAX_WIRE_TAG_CHARS)}' no decodifican (${e::class.simpleName})")
            null
        }

    /** §8: el logger viene de fuera — un logger que lanza no puede tumbar el client. */
    private fun warn(message: String) {
        runCatching { logger(message) }
    }

    companion object {
        /**
         * `source`/`surface` que identifica a este cliente en `session.create`,
         * `session.resume` y `prompt.submit` (§2.3).
         */
        const val APP_SOURCE = "android"

        /** JSON-RPC "internal error" — fallback si clasificar una petición lanza o la cola no la acepta. */
        internal const val JSON_RPC_INTERNAL_ERROR = -32603

        /**
         * Capacidad de la cola de peticiones servidor→cliente: las approval/
         * clarify esperan al colector en vez de descartarse, pero sin cota una
         * ráfaga crecería sin límite (y al llenarse se responde -32603).
         */
        private const val SERVER_REQUEST_QUEUE_CAPACITY = 256

        /**
         * §8: `method`/`type` vienen del servidor y no tienen cota — se truncan
         * antes de llegar al logger (que no debe recibir wire de tamaño libre).
         */
        private const val MAX_WIRE_TAG_CHARS = 64
    }
}
