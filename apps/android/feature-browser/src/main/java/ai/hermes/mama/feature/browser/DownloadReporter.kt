package ai.hermes.mama.feature.browser

import ai.hermes.mama.core.controller.WebViewController
import ai.hermes.mama.gateway.GatewayClient
import ai.hermes.mama.gateway.JsonRpcChannel
import ai.hermes.mama.gateway.submitPrompt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Avisa a la sesión cuando una descarga termina (ROADMAP §5/G1).
 *
 * Por cada descarga:
 * 1. Si hay un comando §2.6 en curso, la nota del modelo (en inglés, formato
 *    exacto del roadmap) viaja en su `browser.controller.result` vía
 *    [WebViewController.queueResultNote] — es el canal preferido del roadmap
 *    y entonces el submit lleva sólo el aviso visible en español (la nota no
 *    se duplica).
 * 2. Siempre, `prompt.submit` con `display_kind:"system"`: con comando en
 *    curso lleva sólo [DownloadNotice.userLine]; sin él lleva
 *    [DownloadNotice.sessionMessage] (aviso + línea del modelo — el fallback
 *    que el roadmap permite cuando no hay resultado al que anotarla).
 *
 * Ciclo de vida: lo decide quien crea la sesión de chat (una descarga puede
 * terminar con la pantalla Navegador cerrada — [start] NO va ligado a la
 * pantalla). [clients] entrega un `GatewayClient` por generación de conexión,
 * como en [BrowserPaneController]: tras un `Reconnected` el siguiente aviso
 * sale por el canal nuevo.
 */
class DownloadReporter(
    private val clients: Flow<GatewayClient>,
    private val sessionId: String,
    private val executor: WebViewController,
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit = {},
) {
    private val started = AtomicBoolean(false)

    /** El client de la generación vigente (para `prompt.submit`). */
    @Volatile
    private var currentClient: GatewayClient? = null

    private var clientsJob: Job? = null

    fun start() {
        if (started.compareAndSet(false, true)) {
            clientsJob = scope.launch { collectClients() }
        }
    }

    fun stop() {
        started.set(false)
        clientsJob?.cancel()
        clientsJob = null
        currentClient = null
    }

    /**
     * Llamado al completarse una descarga — desde cualquier hilo (el manager
     * corre en su propio scope). Nunca lanza.
     */
    fun onDownloadSaved(doc: DownloadedDoc) {
        val modelLine = DownloadNotice.modelLine(doc.fileName, doc.mimeType, doc.sizeBytes)
        // Con comando en vuelo la nota del modelo va en su resultado — el
        // submit de abajo lleva entonces sólo el aviso visible (sin duplicar).
        val inFlight = executor.busy.value
        if (inFlight) {
            executor.queueResultNote(modelLine)
        }
        val client = currentClient ?: return warn("aviso de descarga sin gateway conectado")
        scope.launch {
            try {
                client.submitPrompt(
                    sessionId = sessionId,
                    text =
                        if (inFlight) {
                            DownloadNotice.userLine(doc.fileName)
                        } else {
                            DownloadNotice.sessionMessage(doc.fileName, doc.mimeType, doc.sizeBytes)
                        },
                    displayKind = DISPLAY_KIND_SYSTEM,
                    // El aviso es automático, no urgente: cola pura si la sesión
                    // está ocupada — nunca steer ni interrupt del turno vivo.
                    queued = true,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // §8: sólo el tipo — jamás payload ni URL.
                warn("prompt.submit de la descarga no salió (${e::class.simpleName})")
            }
        }
    }

    /** Un `GatewayClient` por [JsonRpcChannel] — mismo patrón que el panel. */
    private suspend fun collectClients() {
        var lastChannel: JsonRpcChannel? = null
        clients.collect { client ->
            if (client.channel === lastChannel) {
                return@collect
            }
            lastChannel = client.channel
            currentClient = client
        }
    }

    private fun warn(message: String) {
        runCatching { logger(message) }
    }

    companion object {
        /** `display_kind` del aviso: mensaje de sistema, no prompt de la usuaria (§2.3). */
        const val DISPLAY_KIND_SYSTEM = "system"
    }
}
