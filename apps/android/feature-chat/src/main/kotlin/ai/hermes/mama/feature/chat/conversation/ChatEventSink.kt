package ai.hermes.mama.feature.chat.conversation

import ai.hermes.mama.contract.EventTypes
import ai.hermes.mama.contract.MessageCompletePayload
import ai.hermes.mama.contract.NoticePayload
import ai.hermes.mama.contract.StatusUpdatePayload
import ai.hermes.mama.contract.ToolCompletePayload
import ai.hermes.mama.contract.ToolStartPayload
import ai.hermes.mama.gateway.GatewayClient
import ai.hermes.mama.gateway.GatewayEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Traductor de eventos del wire → estado visible de la actividad de Hermes
 * (ROADMAP C4): el chip de `tool.start`/`tool.complete`, el subtítulo de
 * `status.update` y los avisos de `error`/`notice`.
 *
 * Lo separa del [ChatViewModel] porque no toca transcript ni Room: sólo
 * mantiene [activeTools], [statusLine] y [queuedSubmit]. El VM le pasa
 * [emitNotice] (su `SharedFlow` de avisos, compartido con send/stop) y
 * [onAssistantStart] (lo que el VM hace al ver `message.start`: bajar el
 * flag de encolado y refrescar el transcript).
 */
internal class ChatEventSink(
    private val emitNotice: (ChatNotice) -> Unit,
    private val onAssistantStart: () -> Unit,
) {
    private val activeTools = LinkedHashMap<String, ActivityKind>()

    private val _activity = MutableStateFlow<ActivityKind?>(null)

    /** Chip de actividad visible (mapa `tool.start` → [ActivityKind]); `null` = oculto. */
    val activity: StateFlow<ActivityKind?> = _activity.asStateFlow()

    /** Texto del último `status.update` con contenido (subtítulo de la cabecera). */
    val statusLine = MutableStateFlow<String?>(null)

    /** `true` si el último `prompt.submit` quedó encolado detrás de otro turno. */
    val queuedSubmit = MutableStateFlow(false)

    /** Nueva generación de conexión: chip, subtítulo y encolado vuelven a cero. */
    fun reset() {
        activeTools.clear()
        _activity.value = null
        statusLine.value = null
        queuedSubmit.value = false
    }

    /** Despacha un evento de ESTA sesión: chip, subtítulo o aviso (el transcript va por Room). */
    fun onEvent(
        client: GatewayClient,
        event: GatewayEvent,
    ) {
        when (event.type) {
            EventTypes.TOOL_START -> onToolStart(client, event)
            EventTypes.TOOL_COMPLETE -> onToolComplete(client, event)
            EventTypes.STATUS_UPDATE -> onStatusUpdate(client, event)
            // El servidor arrancó el turno: el submit encolado ya despegó.
            EventTypes.MESSAGE_START -> {
                queuedSubmit.value = false
                onAssistantStart()
            }
            EventTypes.MESSAGE_COMPLETE -> onMessageComplete(client, event)
            EventTypes.ERROR -> emitNotice(ChatNotice.GatewayError)
            EventTypes.NOTICE -> onNotice(client, event)
        }
    }

    private fun onToolStart(
        client: GatewayClient,
        event: GatewayEvent,
    ) {
        val payload = client.decodePayload(event, ToolStartPayload.serializer()) ?: return
        activeTools[payload.toolId] = activityKindFor(payload.name)
        _activity.value = activeTools.values.lastOrNull()
    }

    private fun onToolComplete(
        client: GatewayClient,
        event: GatewayEvent,
    ) {
        val payload = client.decodePayload(event, ToolCompletePayload.serializer()) ?: return
        activeTools.remove(payload.toolId)
        _activity.value = activeTools.values.lastOrNull()
    }

    private fun onStatusUpdate(
        client: GatewayClient,
        event: GatewayEvent,
    ) {
        val payload = client.decodePayload(event, StatusUpdatePayload.serializer()) ?: return
        statusLine.value = payload.text.ifBlank { null }
    }

    private fun onMessageComplete(
        client: GatewayClient,
        event: GatewayEvent,
    ) {
        val payload = client.decodePayload(event, MessageCompletePayload.serializer())
        if (payload?.error != null || payload?.failureReason != null) {
            emitNotice(ChatNotice.GatewayError)
        }
        activeTools.clear()
        _activity.value = null
        statusLine.value = null
    }

    private fun onNotice(
        client: GatewayClient,
        event: GatewayEvent,
    ) {
        val message =
            client
                .decodePayload(event, NoticePayload.serializer())
                ?.message
                ?.takeIf { it.isNotBlank() }
        if (message != null) {
            emitNotice(ChatNotice.Info(message))
        }
    }
}
