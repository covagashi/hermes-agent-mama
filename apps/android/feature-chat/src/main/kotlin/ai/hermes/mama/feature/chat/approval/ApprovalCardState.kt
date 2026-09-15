package ai.hermes.mama.feature.chat.approval

/**
 * Estado de la tarjeta de aprobación tal como la pinta la UI (C6, mockup
 * Aprobacion.dc.html). Es inmutable y self-contained: la capa de Compose no
 * conoce `ApprovalRequest` ni el contrato del gateway.
 */
data class ApprovalCardState(
    /** Familia de la acción (del `tool_name` humanizado) → título e icono. */
    val kind: ApprovalKind,
    /**
     * Qué quiere hacer Hermes, en lenguaje llano (`description` del servidor,
     * ya redactada; o `command` si no hay). `null` → la tarjeta no muestra
     * detalle, sólo el título.
     */
    val detail: String?,
    /** Ciclo de vida: pendiente → respondida / fallo de envío. */
    val status: ApprovalStatus = ApprovalStatus.Pending,
)

/** Ciclo de vida de la tarjeta (ROADMAP C6: pendiente → respondida, error visual). */
enum class ApprovalStatus {
    /** Botones Sí/No activos, esperando a la usuaria. */
    Pending,

    /** La usuaria dijo Sí — la tarjeta muestra la elección un momento. */
    Approved,

    /** La usuaria dijo No — ídem. */
    Denied,

    /**
     * La respuesta no salió al cable (socket muerto / timeout): aviso visual
     * y los botones siguen activos para reintentar.
     */
    SendFailed,
}

/**
 * Familias de acciones que la tarjeta sabe nombrar en español llano. El
 * `tool_name` del servidor es técnico (`send_email`, `browser_navigate`,
 * `terminal`…); la usuaria sólo ve "Hermes quiere enviar un correo".
 */
enum class ApprovalKind {
    /** `send_email`, `gmail_send`… — el caso del mockup. */
    SendEmail,

    /** Resto de herramientas de correo (leer, buscar, borradores…). */
    Email,

    /** `browser_*`, `web_search`, `web_fetch`… */
    BrowseWeb,

    /** `terminal`, `run_command`, `process*`… — "ejecutar una orden". */
    RunCommand,

    /** `read_file`, `write_file`, `file*`… */
    Files,

    /** Cualquier otra herramienta o `tool_name` ausente. */
    Generic,
}

private const val TOOL_SEND_EMAIL = "send_email"
private const val TOOL_GMAIL_SEND = "gmail_send"
private const val TOOL_WEB_SEARCH = "web_search"
private const val TOOL_WEB_FETCH = "web_fetch"
private const val TOOL_TERMINAL = "terminal"
private const val TOOL_RUN_COMMAND = "run_command"
private const val PREFIX_BROWSER = "browser_"
private const val PREFIX_FILE = "file_"
private const val TOKEN_FILE = "file"
private const val PREFIX_PROCESS = "process"
private const val TOKEN_EMAIL = "email"
private const val TOKEN_GMAIL = "gmail"
private const val TOKEN_MAIL = "mail"

/**
 * Humanizador de C6: `tool_name` técnico → [ApprovalKind]. Sin `tool_name` →
 * [ApprovalKind.Generic]. Conservador a propósito: ante la duda cae a
 * `Generic` antes que decir algo incorrecto ("send_email"/"gmail_send" son
 * los únicos que prometen *enviar* un correo; el resto de correo es "usar").
 */
fun approvalKindFor(toolName: String?): ApprovalKind {
    val tool = toolName?.trim()?.lowercase().orEmpty()
    return when {
        tool.isEmpty() -> ApprovalKind.Generic
        isSendEmailTool(tool) -> ApprovalKind.SendEmail
        isBrowserTool(tool) -> ApprovalKind.BrowseWeb
        isCommandTool(tool) -> ApprovalKind.RunCommand
        isFileTool(tool) -> ApprovalKind.Files
        isMailTool(tool) -> ApprovalKind.Email
        else -> ApprovalKind.Generic
    }
}

/** Los únicos que prometen *enviar* un correo. */
private fun isSendEmailTool(tool: String): Boolean = tool == TOOL_SEND_EMAIL || tool == TOOL_GMAIL_SEND

private fun isBrowserTool(tool: String): Boolean =
    tool.startsWith(PREFIX_BROWSER) || tool == TOOL_WEB_SEARCH || tool == TOOL_WEB_FETCH

private fun isCommandTool(tool: String): Boolean =
    tool == TOOL_TERMINAL || tool == TOOL_RUN_COMMAND || tool.startsWith(PREFIX_PROCESS)

/**
 * `file` como token (read_file, write_file, file_attach…); ojo con
 * `contains("file")` a secas: "profile" también lo contiene.
 */
private fun isFileTool(tool: String): Boolean =
    tool == TOKEN_FILE ||
        tool.startsWith(PREFIX_FILE) ||
        tool.endsWith("_$TOKEN_FILE") ||
        tool.contains("_${TOKEN_FILE}_")

/** Resto del correo (leer, buscar, borradores…). */
private fun isMailTool(tool: String): Boolean =
    tool.contains(TOKEN_EMAIL) || tool.contains(TOKEN_GMAIL) || tool.contains(TOKEN_MAIL)
