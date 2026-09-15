package ai.hermes.mama.feature.chat.approval

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** El humanizador `tool_name` → [ApprovalKind] (C6: título en lenguaje llano). */
class ApprovalKindTest {
    @Test
    fun `send_email y gmail_send prometen enviar correo`() {
        assertEquals(ApprovalKind.SendEmail, approvalKindFor("send_email"))
        assertEquals(ApprovalKind.SendEmail, approvalKindFor("gmail_send"))
        assertEquals(ApprovalKind.SendEmail, approvalKindFor("Send_Email"))
    }

    @Test
    fun `browser y web van a pagina web`() {
        assertEquals(ApprovalKind.BrowseWeb, approvalKindFor("browser_navigate"))
        assertEquals(ApprovalKind.BrowseWeb, approvalKindFor("browser_click"))
        assertEquals(ApprovalKind.BrowseWeb, approvalKindFor("web_search"))
        assertEquals(ApprovalKind.BrowseWeb, approvalKindFor("web_fetch"))
    }

    @Test
    fun `terminal y comandos van a ejecutar una orden`() {
        assertEquals(ApprovalKind.RunCommand, approvalKindFor("terminal"))
        assertEquals(ApprovalKind.RunCommand, approvalKindFor("run_command"))
        assertEquals(ApprovalKind.RunCommand, approvalKindFor("process_manage"))
    }

    @Test
    fun `herramientas de archivo van a tus archivos`() {
        assertEquals(ApprovalKind.Files, approvalKindFor("read_file"))
        assertEquals(ApprovalKind.Files, approvalKindFor("write_file"))
        assertEquals(ApprovalKind.Files, approvalKindFor("file_attach"))
    }

    @Test
    fun `resto del correo va a usar tu correo`() {
        assertEquals(ApprovalKind.Email, approvalKindFor("email"))
        assertEquals(ApprovalKind.Email, approvalKindFor("gmail"))
        assertEquals(ApprovalKind.Email, approvalKindFor("read_email"))
        assertEquals(ApprovalKind.Email, approvalKindFor("gmail_search"))
    }

    @Test
    fun `desconocido o ausente cae a generico`() {
        assertEquals(ApprovalKind.Generic, approvalKindFor(null))
        assertEquals(ApprovalKind.Generic, approvalKindFor(""))
        assertEquals(ApprovalKind.Generic, approvalKindFor("   "))
        assertEquals(ApprovalKind.Generic, approvalKindFor("sudo"))
        assertEquals(ApprovalKind.Generic, approvalKindFor("vault.unlock"))
    }
}
