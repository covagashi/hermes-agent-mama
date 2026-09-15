package ai.hermes.mama.feature.chat.conversation

import ai.hermes.mama.core.storage.MessageEntity
import ai.hermes.mama.core.storage.MessageKind
import ai.hermes.mama.core.ui.components.ChatBubbleAuthor
import ai.hermes.mama.gateway.GatewayEvent
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/*
 * Modelos de UI de la pantalla Chat (ROADMAP C4): lo que [ChatViewModel]
 * expone ya listo para pintar — la pantalla no conoce ids de sesión, `seq`
 * ni payloads del contrato.
 */

/** Una burbuja del transcript (Room u optimista). */
data class ChatMessage(
    /** Clave estable para la `LazyColumn` (`msg-<rowId>` o `pend-<n>`). */
    val key: String,
    val author: ChatBubbleAuthor,
    val text: String,
    /** `true` si el turno acabó en error (`display_kind`/`message.complete`). */
    val isError: Boolean = false,
    /** Envío optimista que aún no vuelve del servidor (burbuja provisional). */
    val pending: Boolean = false,
    /** El envío falló: la fila muestra el aviso y permite reintentar. */
    val failed: Boolean = false,
    /** `ts` del wire/creación (epoch segundos) — para los separadores de día. */
    val ts: Double = 0.0,
)

/** Cabecera: título + qué está haciendo Hermes ahora (una sola línea de estado). */
data class ChatHeader(
    val title: String = "",
    /** Texto del último `status.update` con contenido (prioritario sobre el resto). */
    val statusText: String? = null,
    /** Turno del asistente en vuelo con texto fluyendo (`message.delta`). */
    val streaming: Boolean = false,
    /** El chat tiene turno vivo según el backend (`ChatEntity.running`). */
    val running: Boolean = false,
    /** El último `prompt.submit` quedó encolado detrás de otro turno. */
    val queued: Boolean = false,
)

/** Aviso puntual para la franja de la pantalla (no es una burbuja). */
sealed interface ChatNotice {
    /** `prompt.submit` no salió (socket caído, error RPC). */
    data object SendFailed : ChatNotice

    /** `session.interrupt` no salió. */
    data object InterruptFailed : ChatNotice

    /** Evento `error` del wire: se muestra el texto genérico amable. */
    data object GatewayError : ChatNotice

    /** Evento `notice` del wire: texto humano del backend, se muestra tal cual. */
    data class Info(
        val text: String,
    ) : ChatNotice
}

/** Actividad visible de Hermes (chip) — traducción del `name` de `tool.start` (§C4). */
enum class ActivityKind {
    SearchWeb,
    Browse,
    Files,
    ReadEmail,
    SendEmail,
    Working,
}

/** `tool.start {name}` → actividad en lenguaje llano; lo desconocido cae a [ActivityKind.Working]. */
fun activityKindFor(toolName: String): ActivityKind =
    when {
        toolName == "web_search" -> ActivityKind.SearchWeb
        toolName.startsWith("browser_") -> ActivityKind.Browse
        toolName == "read_file" || toolName == "terminal" -> ActivityKind.Files
        toolName == "email" || toolName == "gmail" -> ActivityKind.ReadEmail
        toolName == "send_email" -> ActivityKind.SendEmail
        else -> ActivityKind.Working
    }

/** Un renglón de la `LazyColumn`: burbuja o separador de día. */
sealed interface ChatListItem {
    val key: String

    data class Message(
        val message: ChatMessage,
    ) : ChatListItem {
        override val key: String
            get() = message.key
    }

    data class DayHeader(
        override val key: String,
        /** Epoch segundos del día que encabeza (la pantalla lo formatea: Hoy/Ayer/fecha). */
        val ts: Double,
    ) : ChatListItem
}

/** Envío optimista interno del VM: vive en memoria hasta que `history` lo confirma. */
internal data class PendingMessage(
    val key: String,
    val text: String,
    val failed: Boolean = false,
)

internal const val ROLE_USER = "user"
internal const val ROLE_ASSISTANT = "assistant"

/** `display_kind` del wire que la app no pinta (ROADMAP C4: "filtra display_kind=hidden"). */
internal const val KIND_HIDDEN = "hidden"

/** [MessageEntity] → burbuja (sólo se pintan `user`/`assistant`; el resto se descarta). */
internal fun MessageEntity.toChatMessage(): ChatMessage? {
    val author =
        when (role) {
            ROLE_USER -> ChatBubbleAuthor.User
            ROLE_ASSISTANT -> ChatBubbleAuthor.Hermes
            else -> null
        }
    return if (author == null || kind == KIND_HIDDEN) {
        null
    } else {
        ChatMessage(
            key = "msg-$rowId",
            author = author,
            text = text,
            isError = kind == MessageKind.ERROR,
            ts = ts,
        )
    }
}

/**
 * Funde el transcript cacheado con los envíos optimistas: un pendiente cuyo
 * texto ya aparece como fila `user` en Room queda "consumido" (el servidor ya
 * lo persistió — el fake lo hace al aceptar `prompt.submit` y el refresco de
 * `history` lo trae de vuelta). Cada fila user de Room consume un pendiente
 * como máximo, en orden (dos textos iguales enviados dos veces no se comen).
 */
internal fun mergePending(
    room: List<ChatMessage>,
    pending: List<PendingMessage>,
): List<ChatMessage> {
    val consumable = room.filter { it.author == ChatBubbleAuthor.User }.map { it.text }.toMutableList()
    val stillPending = pending.filter { !consumable.remove(it.text) }
    return room +
        stillPending.map { p ->
            ChatMessage(
                key = p.key,
                author = ChatBubbleAuthor.User,
                text = p.text,
                pending = !p.failed,
                failed = p.failed,
            )
        }
}

/** Renglones cronológicos para la `LazyColumn`: inserta un separador de día ante el primer mensaje de cada día. */
internal fun buildChatItems(
    messages: List<ChatMessage>,
    nowEpochSeconds: () -> Double,
): List<ChatListItem> {
    val items = ArrayList<ChatListItem>(messages.size + messages.size / DAY_GROUP_GUESS + 1)
    var previousDay = Long.MIN_VALUE
    for (message in messages) {
        val day = dayBucket(message.ts, nowEpochSeconds)
        if (day != previousDay) {
            // Clave estable por día civil: una cabecera por día, sin importar la posición.
            items += ChatListItem.DayHeader(key = "day-$day", ts = message.ts)
            previousDay = day
        }
        items += ChatListItem.Message(message)
    }
    return items
}

/** Día civil del timestamp (epoch s → días); los mensajes sin ts caen al saco "hoy". */
internal fun dayBucket(
    ts: Double,
    nowEpochSeconds: () -> Double,
): Long = (if (ts > 0) ts else nowEpochSeconds()).toLong() / SECONDS_PER_DAY

/** `params` de `events[]` de `session.events.since` (misma forma que el `params` del frame `event`, §2.2). */
internal fun JsonObject.toGatewayEvent(): GatewayEvent? {
    val type = (this["type"] as? JsonPrimitive)?.contentOrNull ?: return null
    return GatewayEvent(
        type = type,
        sessionId =
            (this["session_id"] as? JsonPrimitive)
                ?.contentOrNull
                ?.takeIf { it.isNotEmpty() },
        seq = (this["seq"] as? JsonPrimitive)?.longOrNull,
        payload = this["payload"] ?: JsonNull,
    )
}

private const val SECONDS_PER_DAY = 86_400L

/** Tamaño aproximado de un grupo de día (sólo para pre-reservar la lista). */
private const val DAY_GROUP_GUESS = 20
