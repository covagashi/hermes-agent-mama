package ai.hermes.mama.feature.chat.chats

import ai.hermes.mama.core.storage.ChatEntity
import ai.hermes.mama.gateway.ConnectionState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Un chat tal cual lo pinta la lista (C3): la capa de Compose no conoce
 * [ChatEntity] ni el contrato del gateway — sólo este modelo inmutable ya
 * listo para pintar (emoji, etiqueta de tiempo resuelta, flags).
 */
data class ChatRowUi(
    /** *Stored* id: identidad estable del chat (§2.3) — clave de la fila y del borrado. */
    val storedId: String,
    /** Título del servidor (o "Chat de <fecha>" hasta que Hermes renombre vía `session.title`). */
    val title: String,
    /** Última línea del chat (`preview` de `session.list`); vacío en un draft sin mensajes. */
    val preview: String,
    /** Emoji del avatar, derivado del título ([chatEmojiForTitle]). */
    val emoji: String,
    /** Etiqueta de tiempo de la columna derecha (del mockup: "10:24", "Ayer", "Lun", "3 sep"). */
    val timeLabel: ChatTimeLabel,
    /** `true` mientras Hermes trabaja en ese chat (punto verde del mockup). */
    val running: Boolean,
)

/** Etiqueta de tiempo de una fila (mockup Main.dc.html). */
sealed interface ChatTimeLabel {
    /** Mismo día: hora "10:24". */
    data class Today(
        val text: String,
    ) : ChatTimeLabel

    /** Día anterior: la UI escribe "Ayer" desde strings. */
    data object Yesterday : ChatTimeLabel

    /** Misma semana ISO: nombre corto del día ("Lun"). */
    data class Weekday(
        val text: String,
    ) : ChatTimeLabel

    /** Más atrás: "3 sep". */
    data class Date(
        val text: String,
    ) : ChatTimeLabel
}

/** Franja de conexión (ROADMAP §3: "sólo cuando falla"). La UI la traduce a texto humano. */
enum class ChatsBanner {
    /** [ConnectionState.Reconnecting]: "Sin conexión. Reintentando…". */
    Reconnecting,

    /** [ConnectionState.Failed]: fallo terminal (p. ej. credenciales rechazadas). */
    Failed,

    /** [ConnectionState.Disconnected]: sin socket y sin reintento en curso. */
    Disconnected,
}

/** Avisos de una sola vez que la pantalla muestra como snackbar (texto humano, sin códigos). */
enum class ChatsNotice {
    CreateFailed,
    OpenFailed,
    DeleteFailed,
    RefreshFailed,
}

/** Estado completo de la pantalla Chats. */
data class ChatsUiState(
    /** Filas ya mapeadas, en el orden del servidor (más reciente primero). */
    val chats: List<ChatRowUi> = emptyList(),
    /** Franja de conexión visible, o `null` (Connected / primer Connecting). */
    val banner: ChatsBanner? = null,
    /** `true` tras la primera emisión de la caché: distingue "cargando" de "vacío". */
    val loaded: Boolean = false,
    /** Pull-to-refresh en curso. */
    val refreshing: Boolean = false,
    /** Creación de chat en curso (desactiva el botón "Nuevo chat"). */
    val creating: Boolean = false,
    /** Apertura en curso de este *stored* id (desactiva la fila pulsada). */
    val openingStoredId: String? = null,
    /** Chat pendiente de confirmación de borrado (diálogo visible). */
    val pendingDelete: ChatRowUi? = null,
)

/** `session.list` → fila de UI: emoji por título y etiqueta de tiempo por `startedAt`. */
internal fun ChatEntity.toRowUi(nowMillis: Long): ChatRowUi =
    ChatRowUi(
        storedId = storedId,
        title = title,
        preview = preview,
        emoji = chatEmojiForTitle(title),
        timeLabel = chatTimeLabel(startedAt, nowMillis),
        running = running,
    )

// --- avatar ---

/**
 * Emoji del avatar por título de chat (ROADMAP C3):
 * correo→📧, compra/pedido/factura→🧾, receta→🍲, médico/cita→🩺,
 * familia→👨‍👩‍👧, otro→💬. La comparación es insensible a mayúsculas y tildes
 * ("Médico" y "medico" casan). El orden importa: gana la primera regla que
 * encuentra palabra clave.
 */
fun chatEmojiForTitle(title: String): String {
    val normalized = normalize(title)
    return when {
        KEYWORDS_EMAIL.any(normalized::contains) -> EMOJI_EMAIL
        KEYWORDS_RECEIPT.any(normalized::contains) -> EMOJI_RECEIPT
        KEYWORDS_RECIPE.any(normalized::contains) -> EMOJI_RECIPE
        KEYWORDS_HEALTH.any(normalized::contains) -> EMOJI_HEALTH
        KEYWORDS_FAMILY.any(normalized::contains) -> EMOJI_FAMILY
        else -> EMOJI_DEFAULT
    }
}

/** Minúsculas y sin tildes/diéresis para casar palabras clave ("médico"→"medico", "bebé"→"bebe"). */
private fun normalize(text: String): String {
    val lowered = text.lowercase(Locale.forLanguageTag("es"))
    val stripped = StringBuilder(lowered.length)
    for (ch in lowered) {
        stripped.append(
            when (ch) {
                'á', 'à', 'ä', 'â' -> 'a'
                'é', 'è', 'ë', 'ê' -> 'e'
                'í', 'ì', 'ï', 'î' -> 'i'
                'ó', 'ò', 'ö', 'ô' -> 'o'
                'ú', 'ù', 'ü', 'û' -> 'u'
                else -> ch
            },
        )
    }
    return stripped.toString()
}

private const val EMOJI_EMAIL = "\uD83D\uDCE7" // 📧
private const val EMOJI_RECEIPT = "\uD83E\uDDFE" // 🧾
private const val EMOJI_RECIPE = "\uD83C\uDF72" // 🍲
private const val EMOJI_HEALTH = "\uD83E\uDE7A" // 🩺
private const val EMOJI_FAMILY = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67" // 👨‍👩‍👧
private const val EMOJI_DEFAULT = "\uD83D\uDCAC" // 💬

private val KEYWORDS_EMAIL = listOf("correo", "email", "mail", "gmail")
private val KEYWORDS_RECEIPT = listOf("compra", "pedido", "factura", "recibo", "ticket")
private val KEYWORDS_RECIPE = listOf("receta", "cocina", "lentejas", "cena", "comida")
private val KEYWORDS_HEALTH = listOf("medico", "medic", "cita", "doctor", "salud", "farmacia")
private val KEYWORDS_FAMILY =
    listOf(
        "familia",
        "mama",
        "papa",
        "madre",
        "padre",
        "hijo",
        "hija",
        "abuelo",
        "abuela",
        "hermano",
        "hermana",
        "nieto",
        "nieta",
    )

// --- etiqueta de tiempo ---

private val SPANISH: Locale = Locale.forLanguageTag("es")
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", SPANISH)
private val WEEKDAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE", SPANISH)
private val DAY_MONTH_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", SPANISH)

/**
 * Regla del mockup para la hora de la fila: mismo día → "HH:mm"; ayer →
 * [ChatTimeLabel.Yesterday]; misma semana ISO (lunes–domingo) → nombre del día
 * ("Lun"); más atrás → "d MMM" ("3 sep").
 *
 * Usa `startedAt` (único timestamp real del wire: [ChatEntity.lastActive] es un
 * rank de ordenación, no una hora — ver §9.9 del ROADMAP).
 */
fun chatTimeLabel(
    startedAtSeconds: Double,
    nowMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): ChatTimeLabel {
    val instant = Instant.ofEpochMilli((startedAtSeconds * MILLIS_PER_SECOND).toLong())
    val date = instant.atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    return when {
        date == today -> ChatTimeLabel.Today(instant.atZone(zone).format(TIME_FORMAT))
        date == today.minusDays(1) -> ChatTimeLabel.Yesterday
        sameIsoWeek(date, today) ->
            ChatTimeLabel.Weekday(date.format(WEEKDAY_FORMAT).replaceFirstChar { it.titlecase(SPANISH) })
        else -> ChatTimeLabel.Date(date.format(DAY_MONTH_FORMAT))
    }
}

/** ¿Misma semana ISO (lunes como primer día)? Semana natural, no "últimos 7 días". */
private fun sameIsoWeek(
    date: LocalDate,
    today: LocalDate,
): Boolean {
    val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
    return !date.isBefore(monday) && !date.isAfter(today)
}

private const val MILLIS_PER_SECOND = 1000.0
