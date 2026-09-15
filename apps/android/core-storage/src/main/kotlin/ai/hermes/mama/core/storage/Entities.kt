package ai.hermes.mama.core.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Un chat tal cual lo pinta la lista (ROADMAP §2.3, `session.list`) y §5/B6.
 *
 * - [storedId]: id persistente del backend (`SessionListRow.id`,
 *   `stored_session_id`). Es la clave primaria: `session.resume`,
 *   `session.delete` y el deep link `hermes-mama://chat/<storedId>` lo usan.
 * - [runtimeId]: id vivo devuelto por `session.resume`/`session.create`; lo
 *   necesitan `session.history`, `session.title`, `prompt.submit` y los
 *   eventos `message.*`. Cambia al reabrir (un runtime id queda obsoleto si el
 *   gateway se reinicia) — nunca forma parte de la identidad del chat.
 * - [startedAt]: `started_at` del wire (epoch en segundos, con decimales).
 * - [lastActive]: **rank de ordenación, no un timestamp**: el wire no expone
 *   `effective_last_active` (el orden del servidor), así que `session.list`
 *   graba aquí su posición (la lista llega ya ordenada, más reciente primero).
 *   La actividad local (crear un chat, un `message.complete`) sube el rank.
 * - [running]: la sesión tiene un turno del agente en curso (mejor esfuerzo:
 *   `session.resume`, `session.info`, `message.start`/`message.complete`). Lo
 *   leen C4 («Hermes sigue con lo anterior») y C8 (ForegroundService).
 * - [localOnly]: el chat existe sólo en memoria del gateway — `session.create`
 *   NO persiste fila en `state.db` hasta el primer `prompt.submit`
 *   (`methods_session.py`: "No DB row here (drafts left 'Untitled' litter)"),
 *   así que `session.list` aún no lo devuelve y `refreshList` NO debe
 *   evictarlo. Se limpia en cuanto el storedId aparece en la lista.
 */
@Entity(
    tableName = "chats",
    indices = [Index(value = ["runtimeId"])],
)
data class ChatEntity(
    @PrimaryKey
    val storedId: String,
    val runtimeId: String? = null,
    val title: String = "",
    val preview: String = "",
    val startedAt: Double = 0.0,
    val lastActive: Double = 0.0,
    val messageCount: Long = 0L,
    val running: Boolean = false,
    val localOnly: Boolean = false,
)

/**
 * Una fila del transcript cacheado (§5/B6): lo que se muestra sin red.
 *
 * [rowId] es la PK local autogenerada: su orden es el orden de inserción, que
 * coincide con el del transcript (history/resume se reinsertan en orden; los
 * mensajes en streaming se añaden al final). El `row_id` duradero del backend
 * ([TranscriptMessage.rowId] — dirección de reacciones/truncado) se conserva
 * aparte en [remoteRowId].
 *
 * [kind] refleja `display_kind` del wire (`hidden`, `model_switch`…, `null` →
 * [MessageKind.TEXT]) salvo en filas escritas desde `message.complete`, donde
 * es [MessageKind.TEXT] o [MessageKind.ERROR].
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["storedId"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["chatId"]),
        Index(value = ["chatId", "remoteRowId"], unique = true),
    ],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val rowId: Long = 0L,
    val chatId: String,
    val role: String,
    val text: String,
    val ts: Double = 0.0,
    val kind: String = MessageKind.TEXT,
    val remoteRowId: Long? = null,
)

/** Valores de [MessageEntity.kind] que escribe la propia app (el resto viene de `display_kind`). */
object MessageKind {
    const val TEXT = "text"
    const val ERROR = "error"
}
