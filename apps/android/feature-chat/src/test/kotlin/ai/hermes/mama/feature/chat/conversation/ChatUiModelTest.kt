package ai.hermes.mama.feature.chat.conversation

import ai.hermes.mama.core.storage.MessageEntity
import ai.hermes.mama.core.storage.MessageKind
import ai.hermes.mama.core.ui.components.ChatBubbleAuthor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests del modelo de UI de C4: mapa de actividad, fusión optimista y
 * traducción `MessageEntity` → burbuja.
 */
class ChatUiModelTest {
    // --- mapa tool.start → ActivityKind (ROADMAP C4) ---

    @Test
    fun `mapa de actividad por nombre de herramienta`() {
        assertEquals(ActivityKind.SearchWeb, activityKindFor("web_search"))
        assertEquals(ActivityKind.Browse, activityKindFor("browser_navigate"))
        assertEquals(ActivityKind.Browse, activityKindFor("browser_click"))
        assertEquals(ActivityKind.Files, activityKindFor("read_file"))
        assertEquals(ActivityKind.Files, activityKindFor("terminal"))
        assertEquals(ActivityKind.ReadEmail, activityKindFor("email"))
        assertEquals(ActivityKind.ReadEmail, activityKindFor("gmail"))
        assertEquals(ActivityKind.SendEmail, activityKindFor("send_email"))
        assertEquals(ActivityKind.Working, activityKindFor("cualquier_otra"))
        assertEquals(ActivityKind.Working, activityKindFor(""))
    }

    // --- MessageEntity → ChatMessage ---

    @Test
    fun `entity user y assistant pintan y el resto se descarta`() {
        val user = entity(role = "user", text = "hola")
        val hermes = entity(role = "assistant", text = "buenas")
        val system = entity(role = "system", text = "interno")

        assertEquals(ChatBubbleAuthor.User, user.toChatMessage()?.author)
        assertEquals(ChatBubbleAuthor.Hermes, hermes.toChatMessage()?.author)
        assertNull(system.toChatMessage())
    }

    @Test
    fun `kind error marca isError y hidden se descarta`() {
        val error = entity(role = "assistant", kind = MessageKind.ERROR)
        val hidden = entity(role = "assistant", kind = "hidden")

        assertEquals(true, error.toChatMessage()?.isError)
        assertNull(hidden.toChatMessage())
    }

    @Test
    fun `la clave de burbuja es estable por rowId`() {
        assertEquals("msg-42", entity(rowId = 42).toChatMessage()?.key)
    }

    // --- mergePending (burbuja optimista ↔ fila user del servidor) ---

    @Test
    fun `el pendiente se consume cuando el servidor devuelve la misma fila`() {
        val room = listOf(chatMessage(key = "msg-1", text = "hola"))
        val pending = listOf(PendingMessage(key = "pend-0", text = "hola"))

        val merged = mergePending(room, pending)
        assertEquals(1, merged.size)
        assertEquals("msg-1", merged[0].key)
    }

    @Test
    fun `el pendiente distinto sobrevive como burbuja optimista`() {
        val room = listOf(chatMessage(key = "msg-1", text = "otro"))
        val pending = listOf(PendingMessage(key = "pend-0", text = "hola"))

        val merged = mergePending(room, pending)
        assertEquals(2, merged.size)
        val optimistic = merged[1]
        assertEquals("pend-0", optimistic.key)
        assertEquals(true, optimistic.pending)
    }

    @Test
    fun `dos envios del mismo texto consumen dos pendientes`() {
        val room =
            listOf(
                chatMessage(key = "msg-1", text = "hola"),
                chatMessage(key = "msg-2", text = "hola"),
            )
        val pending =
            listOf(
                PendingMessage(key = "pend-0", text = "hola"),
                PendingMessage(key = "pend-1", text = "hola"),
                PendingMessage(key = "pend-2", text = "hola"),
            )

        val merged = mergePending(room, pending)
        // Dos filas reales consumen dos pendientes; queda uno (el tercero).
        assertEquals(3, merged.size)
        assertEquals("pend-2", merged[2].key)
    }

    @Test
    fun `el pendiente fallido sigue visible con su marca`() {
        val pending = listOf(PendingMessage(key = "pend-0", text = "boom", failed = true))

        val merged = mergePending(emptyList(), pending)
        assertEquals(1, merged.size)
        assertEquals(true, merged[0].failed)
        assertEquals(false, merged[0].pending)
    }

    // --- helpers ---

    private fun entity(
        rowId: Long = 1,
        role: String = "assistant",
        text: String = "texto",
        kind: String = MessageKind.TEXT,
        ts: Double = 100.0,
    ) = MessageEntity(rowId = rowId, chatId = "chat", role = role, text = text, kind = kind, ts = ts)

    private fun chatMessage(
        key: String,
        text: String,
        author: ChatBubbleAuthor = ChatBubbleAuthor.User,
    ) = ChatMessage(key = key, author = author, text = text)
}
