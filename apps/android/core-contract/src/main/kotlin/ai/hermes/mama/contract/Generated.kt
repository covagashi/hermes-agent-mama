// GENERATED — do not edit
// Source: apps/shared/src/gateway-contract.openrpc.json
// Contract SHA-256: 345c771f67ecc4684aa941fdb7dfec11dfa750ce48fb4553205d78d044997e99
// Regenerate: python3 scripts/gen_android_contract.py

@file:Suppress("LargeClass")

package ai.hermes.mama.contract

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer

/** Métodos JSON-RPC cliente→servidor que usa la app (ROADMAP §2.3). */
object RpcMethods {
    /** Heartbeat a nivel WS (§2.2): lo responde ws.py antes del dispatch. */
    const val GATEWAY_PING = "gateway.ping"
    const val GATEWAY_CAPABILITIES = "gateway.capabilities"
    const val SESSION_LIST = "session.list"
    const val SESSION_CREATE = "session.create"
    const val SESSION_RESUME = "session.resume"
    const val SESSION_HISTORY = "session.history"
    const val SESSION_TITLE = "session.title"
    const val SESSION_DELETE = "session.delete"
    const val SESSION_INTERRUPT = "session.interrupt"
    const val SESSION_EVENTS_SINCE = "session.events.since"
    const val PROMPT_SUBMIT = "prompt.submit"
    const val IMAGE_ATTACH_BYTES = "image.attach_bytes"
    const val FILE_ATTACH = "file.attach"
    const val APPROVAL_PENDING = "approval.pending"
    const val APPROVAL_RESPOND = "approval.respond"
    const val REQUEST_ANSWER = "request.answer"
    const val BROWSER_CONTROLLER_REGISTER = "browser.controller.register"
    const val BROWSER_CONTROLLER_RESULT = "browser.controller.result"
    const val BROWSER_CONTROLLER_HEARTBEAT = "browser.controller.heartbeat"
    const val BROWSER_CONTROLLER_DETACH = "browser.controller.detach"
}

/** Tipos de evento servidor→cliente ``event.params.type`` (ROADMAP §2.4; los no listados se toleran sin fallar). */
object EventTypes {
    const val AGENT_TERMINAL_OUTPUT = "agent.terminal.output"
    const val BACKGROUND_COMPLETE = "background.complete"
    const val BILLING_STEP_UP_VERIFICATION = "billing.step_up.verification"
    const val BOT_RELAY_OUTBOX_PENDING = "bot_relay.outbox.pending"
    const val BROWSER_CONTROLLER_CANCEL = "browser.controller.cancel"
    const val BROWSER_CONTROLLER_COMMAND = "browser.controller.command"
    const val BROWSER_PROGRESS = "browser.progress"
    const val BTW_COMPLETE = "btw.complete"
    const val CRON_CHANGED = "cron.changed"
    const val ERROR = "error"
    const val GATEWAY_READY = "gateway.ready"
    const val LAYOUT_APPLY = "layout.apply"
    const val MESSAGE_COMPLETE = "message.complete"
    const val MESSAGE_DELTA = "message.delta"
    const val MESSAGE_INTERIM = "message.interim"
    const val MESSAGE_REACTION = "message.reaction"
    const val MESSAGE_START = "message.start"
    const val MOA_AGGREGATING = "moa.aggregating"
    const val MOA_PHASE = "moa.phase"
    const val MOA_PROGRESS = "moa.progress"
    const val MOA_REFERENCE = "moa.reference"
    const val NOTICE = "notice"
    const val NOTIFICATION_CLEAR = "notification.clear"
    const val NOTIFICATION_SHOW = "notification.show"
    const val PAIRING_CHANGED = "pairing.changed"
    const val PANE_REVEAL = "pane.reveal"
    const val PET_CHANGED = "pet.changed"
    const val PET_GENERATE_PROGRESS = "pet.generate.progress"
    const val PET_HATCH_PROGRESS = "pet.hatch.progress"
    const val PLATFORMS_CHANGED = "platforms.changed"
    const val PREVIEW_CLOSE = "preview.close"
    const val PREVIEW_OPEN = "preview.open"
    const val PREVIEW_RESTART_COMPLETE = "preview.restart.complete"
    const val PREVIEW_RESTART_PROGRESS = "preview.restart.progress"
    const val REACTION = "reaction"
    const val REASONING_AVAILABLE = "reasoning.available"
    const val REASONING_DELTA = "reasoning.delta"
    const val REQUEST_CANCEL = "request.cancel"
    const val REVIEW_SUMMARY = "review.summary"
    const val SESSION_CONTROL_UPDATE = "session.control.update"
    const val SESSION_INFO = "session.info"
    const val SESSION_RECLAIMED = "session.reclaimed"
    const val SESSION_RESUME_PROGRESS = "session.resume_progress"
    const val SESSION_TITLE = "session.title"
    const val SESSION_USAGE = "session.usage"
    const val SESSIONS_CHANGED = "sessions.changed"
    const val SETUP_READY = "setup.ready"
    const val SKIN_CHANGED = "skin.changed"
    const val STATUS_UPDATE = "status.update"
    const val SUBAGENT_COMPLETE = "subagent.complete"
    const val SUBAGENT_PROGRESS = "subagent.progress"
    const val SUBAGENT_SPAWN_REQUESTED = "subagent.spawn_requested"
    const val SUBAGENT_START = "subagent.start"
    const val SUBAGENT_THINKING = "subagent.thinking"
    const val SUBAGENT_TOOL = "subagent.tool"
    const val TERMINAL_CLOSE = "terminal.close"
    const val THINKING_DELTA = "thinking.delta"
    const val TIP_SHOW = "tip.show"
    const val TODO_UPDATED = "todo.updated"
    const val TOOL_COMPLETE = "tool.complete"
    const val TOOL_GENERATING = "tool.generating"
    const val TOOL_OUTPUT_RISK = "tool.output_risk"
    const val TOOL_START = "tool.start"
    const val VOICE_INTERRUPTED = "voice.interrupted"
    const val VOICE_STATUS = "voice.status"
    const val VOICE_TRANSCRIPT = "voice.transcript"
    const val WAKE_DETECTED = "wake.detected"
}

/**
 * Peticiones servidor→cliente que la app puede recibir (ROADMAP §2.5); sólo ``approval`` y ``clarify`` se atienden,
 * el resto → -32601.
 */
object ServerRequests {
    const val APPROVAL = "approval"
    const val CLARIFY = "clarify"
    const val MCP_SETUP = "mcp.setup"
    const val PREVIEW_ACT = "preview.act"
    const val PREVIEW_READ = "preview.read"
    const val SECRET = "secret"
    const val SUDO = "sudo"
    const val TERMINAL_READ = "terminal.read"
    const val TOUR = "tour"
    const val VAULT_CODE = "vault.code"
    const val VAULT_SAVE_LOGIN = "vault.save_login"
    const val VAULT_UNLOCK_PROMPT = "vault.unlock_prompt"
    const val WINDOW_READ = "window.read"
}

/**
 * Serializers por nombre de schema del contrato (B4 los usa para decodificar payloads tipados; el round-trip test los
 * recorre todos).
 */
object ContractSerializers {
    val bySchema: Map<String, KSerializer<*>> =
        mapOf(
            "PingParams" to serializer<PingParams>(),
            "PingResult" to serializer<PingResult>(),
            "OkResult" to serializer<OkResult>(),
            "GatewayCapabilitiesResult" to serializer<GatewayCapabilitiesResult>(),
            "SessionListParams" to serializer<SessionListParams>(),
            "SessionListResult" to serializer<SessionListResult>(),
            "SessionListRow" to serializer<SessionListRow>(),
            "SessionCreateParams" to serializer<SessionCreateParams>(),
            "SessionCreateResult" to serializer<SessionCreateResult>(),
            "SessionResumeParams" to serializer<SessionResumeParams>(),
            "SessionResumeResult" to serializer<SessionResumeResult>(),
            "SessionHistoryParams" to serializer<SessionHistoryParams>(),
            "SessionHistoryResult" to serializer<SessionHistoryResult>(),
            "SessionTitleParams" to serializer<SessionTitleParams>(),
            "SessionTitleResult" to serializer<SessionTitleResult>(),
            "SessionDeleteParams" to serializer<SessionDeleteParams>(),
            "SessionDeleteResult" to serializer<SessionDeleteResult>(),
            "SessionInterruptParams" to serializer<SessionInterruptParams>(),
            "SessionInterruptResult" to serializer<SessionInterruptResult>(),
            "SessionEventsSinceParams" to serializer<SessionEventsSinceParams>(),
            "SessionEventsSinceResult" to serializer<SessionEventsSinceResult>(),
            "PromptSubmitParams" to serializer<PromptSubmitParams>(),
            "PromptSubmitResult" to serializer<PromptSubmitResult>(),
            "ImageAttachBytesParams" to serializer<ImageAttachBytesParams>(),
            "AttachedImageResult" to serializer<AttachedImageResult>(),
            "FileAttachParams" to serializer<FileAttachParams>(),
            "FileAttachResult" to serializer<FileAttachResult>(),
            "ApprovalPendingParams" to serializer<ApprovalPendingParams>(),
            "ApprovalPendingResult" to serializer<ApprovalPendingResult>(),
            "ApprovalRespondParams" to serializer<ApprovalRespondParams>(),
            "ApprovalRespondResult" to serializer<ApprovalRespondResult>(),
            "ApprovalRequestParams" to serializer<ApprovalRequestParams>(),
            "ApprovalResult" to serializer<ApprovalResult>(),
            "ApprovalChoice" to serializer<ApprovalChoice>(),
            "PendingApproval" to serializer<PendingApproval>(),
            "ClarifyRequestParams" to serializer<ClarifyRequestParams>(),
            "ClarifyQuestion" to serializer<ClarifyQuestion>(),
            "ClarifyResult" to serializer<ClarifyResult>(),
            "ClarifyLockStatus" to serializer<ClarifyLockStatus>(),
            "RequestAnswerParams" to serializer<RequestAnswerParams>(),
            "RequestAnswerResult" to serializer<RequestAnswerResult>(),
            "SecretRequestParams" to serializer<SecretRequestParams>(),
            "EmptyRequestParams" to serializer<EmptyRequestParams>(),
            "VaultCodeRequestParams" to serializer<VaultCodeRequestParams>(),
            "VaultSaveLoginRequestParams" to serializer<VaultSaveLoginRequestParams>(),
            "VaultUnlockRequestParams" to serializer<VaultUnlockRequestParams>(),
            "McpSetupRequestParams" to serializer<McpSetupRequestParams>(),
            "PreviewActRequestParams" to serializer<PreviewActRequestParams>(),
            "ReadRangeRequestParams" to serializer<ReadRangeRequestParams>(),
            "TourRequestParams" to serializer<TourRequestParams>(),
            "TourStep" to serializer<TourStep>(),
            "ValueResult" to serializer<ValueResult>(),
            "BrowserControllerRegisterParams" to serializer<BrowserControllerRegisterParams>(),
            "BrowserControllerRegisterResult" to serializer<BrowserControllerRegisterResult>(),
            "BrowserControllerResultParams" to serializer<BrowserControllerResultParams>(),
            "BrowserControllerResultResult" to serializer<BrowserControllerResultResult>(),
            "BrowserControllerParams" to serializer<BrowserControllerParams>(),
            "BrowserControllerDetachResult" to serializer<BrowserControllerDetachResult>(),
            "BrowserControllerCommandPayload" to serializer<BrowserControllerCommandPayload>(),
            "BrowserControllerCancelPayload" to serializer<BrowserControllerCancelPayload>(),
            "BrowserProgressPayload" to serializer<BrowserProgressPayload>(),
            "ControllerScope" to serializer<ControllerScope>(),
            "GatewayReadyPayload" to serializer<GatewayReadyPayload>(),
            "SkinPayload" to serializer<SkinPayload>(),
            "StreamDeltaPayload" to serializer<StreamDeltaPayload>(),
            "MessageCompletePayload" to serializer<MessageCompletePayload>(),
            "MessageInterimPayload" to serializer<MessageInterimPayload>(),
            "ToolStartPayload" to serializer<ToolStartPayload>(),
            "ToolCompletePayload" to serializer<ToolCompletePayload>(),
            "StatusUpdatePayload" to serializer<StatusUpdatePayload>(),
            "SessionTitlePayload" to serializer<SessionTitlePayload>(),
            "ChangeSignalPayload" to serializer<ChangeSignalPayload>(),
            "SessionLiveInfo" to serializer<SessionLiveInfo>(),
            "ErrorPayload" to serializer<ErrorPayload>(),
            "NoticePayload" to serializer<NoticePayload>(),
            "RequestCancelPayload" to serializer<RequestCancelPayload>(),
            "VoiceTranscriptPayload" to serializer<VoiceTranscriptPayload>(),
            "TranscriptMessage" to serializer<TranscriptMessage>(),
            "SeedMessage" to serializer<SeedMessage>(),
            "OpenRequestEntry" to serializer<OpenRequestEntry>(),
            "InflightTurn" to serializer<InflightTurn>(),
            "QueuedPrompt" to serializer<QueuedPrompt>(),
            "Usage" to serializer<Usage>(),
            "TodoState" to serializer<TodoState>(),
            "AutoContinue" to serializer<AutoContinue>(),
            "ProjectRef" to serializer<ProjectRef>(),
            "McpServerStatus" to serializer<McpServerStatus>(),
            "InterruptStatus" to serializer<InterruptStatus>(),
            "PromptSubmitStatus" to serializer<PromptSubmitStatus>(),
            "TurnStatus" to serializer<TurnStatus>(),
            "BillingBlock" to serializer<BillingBlock>(),
            "ErrorSurface" to serializer<ErrorSurface>(),
        )
}

/** ``PingParams``: objeto vacío del contrato. */
@Serializable
object PingParams

@Serializable
data class PingResult(
    val pong: Boolean,
)

@Serializable
data class OkResult(
    val ok: Boolean = true,
)

@Serializable
data class GatewayCapabilitiesResult(
    @SerialName("per_session_exclusive_submit")
    val perSessionExclusiveSubmit: Boolean,
)

@Serializable
data class SessionListParams(
    val profile: String? = null,
    val title: String? = null,
    val limit: Long? = null,
    @SerialName("include_hidden")
    val includeHidden: Boolean = false,
)

@Serializable
data class SessionListResult(
    val sessions: List<SessionListRow>,
)

/**
 * ``methods_session._session_row_summary``; ``resolved_id`` only on a title lookup that followed a compression
 * lineage to its tip.
 */
@Serializable
data class SessionListRow(
    val id: String,
    @SerialName("resolved_id")
    val resolvedId: String? = null,
    val title: String = "",
    val preview: String = "",
    @SerialName("started_at")
    val startedAt: Double = 0.0,
    @SerialName("message_count")
    val messageCount: Long = 0L,
    val source: String = "",
)

@Serializable
data class SessionCreateParams(
    val profile: String? = null,
    val cols: Long? = null,
    val source: String? = null,
    val cwd: String? = null,
    val messages: List<SeedMessage>? = null,
    @SerialName("parent_session_id")
    val parentSessionId: String? = null,
    val title: String? = null,
    val model: String? = null,
    val provider: String? = null,
    @SerialName("reasoning_effort")
    val reasoningEffort: String? = null,
    val fast: Boolean? = null,
    @SerialName("close_on_disconnect")
    val closeOnDisconnect: Boolean = false,
    val hidden: Boolean = false,
    @SerialName("room_plumbing")
    val roomPlumbing: Boolean = false,
    @SerialName("follow_profile_config")
    val followProfileConfig: Boolean = false,
)

@Serializable
data class SessionCreateResult(
    @SerialName("session_id")
    val sessionId: String,
    @SerialName("stored_session_id")
    val storedSessionId: String,
    @SerialName("message_count")
    val messageCount: Long,
    val messages: List<TranscriptMessage>,
    val info: SessionLiveInfo,
)

/** ``session_id`` is the STORED id (or an exact title); the reply's ``session_id`` is the runtime id. */
@Serializable
data class SessionResumeParams(
    @SerialName("session_id")
    val sessionId: String,
    val profile: String? = null,
    val cols: Long? = null,
    val source: String? = null,
    val lazy: Boolean = false,
    @SerialName("defer_history")
    val deferHistory: Boolean = false,
    @SerialName("omit_messages")
    val omitMessages: Boolean = false,
    @SerialName("eager_build")
    val eagerBuild: Boolean = false,
    @SerialName("close_on_disconnect")
    val closeOnDisconnect: Boolean = false,
)

@Serializable
data class SessionResumeResult(
    @SerialName("session_id")
    val sessionId: String,
    @SerialName("message_count")
    val messageCount: Long,
    val messages: List<TranscriptMessage>,
    val info: SessionLiveInfo,
    @SerialName("stored_session_id")
    val storedSessionId: String? = null,
    val resumed: String? = null,
    @SerialName("session_key")
    val sessionKey: String? = null,
    @SerialName("messages_omitted")
    val messagesOmitted: Boolean? = null,
    val hydrating: Boolean? = null,
    val running: Boolean? = null,
    @SerialName("turn_started_at")
    val turnStartedAt: Double? = null,
    @SerialName("started_at")
    val startedAt: Double? = null,
    val status: String? = null,
    val inflight: InflightTurn? = null,
    val queued: QueuedPrompt? = null,
    @SerialName("pending_approval")
    val pendingApproval: PendingApproval? = null,
    @SerialName("open_requests")
    val openRequests: List<OpenRequestEntry>? = null,
    @SerialName("todo_state")
    val todoState: TodoState? = null,
    @SerialName("auto_continue")
    val autoContinue: AutoContinue? = null,
)

@Serializable
data class SessionHistoryParams(
    @SerialName("session_id")
    val sessionId: String,
    val profile: String? = null,
)

@Serializable
data class SessionHistoryResult(
    val count: Long,
    val messages: List<TranscriptMessage>,
)

@Serializable
data class SessionTitleParams(
    @SerialName("session_id")
    val sessionId: String,
    val profile: String? = null,
    val title: String? = null,
)

@Serializable
data class SessionTitleResult(
    val title: String,
    @SerialName("session_key")
    val sessionKey: String? = null,
    val pending: Boolean? = null,
)

/** ``session_id`` is the STORED id. */
@Serializable
data class SessionDeleteParams(
    @SerialName("session_id")
    val sessionId: String,
    val profile: String? = null,
)

@Serializable
data class SessionDeleteResult(
    val deleted: String,
)

@Serializable
data class SessionInterruptParams(
    @SerialName("session_id")
    val sessionId: String,
    val profile: String? = null,
    @SerialName("expected_hosted_task_id")
    val expectedHostedTaskId: String? = null,
)

@Serializable
data class SessionInterruptResult(
    val status: InterruptStatus,
    val interrupted: Boolean? = null,
    @SerialName("turn_isolation")
    val turnIsolation: Boolean? = null,
)

@Serializable
data class SessionEventsSinceParams(
    @SerialName("session_id")
    val sessionId: String,
    val profile: String? = null,
    @SerialName("last_seen")
    val lastSeen: Long? = null,
)

@Serializable
data class SessionEventsSinceResult(
    val events: List<JsonObject>,
    @SerialName("latest_seq")
    val latestSeq: Long,
    val truncated: Boolean,
    val count: Long,
    val epoch: String,
    @SerialName("open_requests")
    val openRequests: List<OpenRequestEntry>,
)

/**
 * ``text`` is normally a string; the relay / hosted paths may hand a structured (parts list) payload, and the busy
 * path renders it. Truncation (rewind / edit / regenerate) needs explicit consent: ``confirm_truncate`` plus one
 * durable target (``truncate_before_row_id`` preferred, ``truncate_before_message_id``, or the legacy
 * ``truncate_before_user_ordinal``).
 */
@Serializable
data class PromptSubmitParams(
    @SerialName("session_id")
    val sessionId: String,
    val profile: String? = null,
    val text: JsonElement = JsonPrimitive(""),
    @SerialName("display_kind")
    val displayKind: String? = null,
    val interrupted: Boolean? = null,
    val queued: Boolean? = null,
    val surface: String? = null,
    @SerialName("voice_context")
    val voiceContext: String? = null,
    @SerialName("truncate_before_user_ordinal")
    val truncateBeforeUserOrdinal: Long? = null,
    @SerialName("truncate_before_row_id")
    val truncateBeforeRowId: Long? = null,
    @SerialName("truncate_before_message_id")
    val truncateBeforeMessageId: String? = null,
    @SerialName("confirm_truncate")
    val confirmTruncate: Boolean? = null,
    @SerialName("confirm_empty_truncate")
    val confirmEmptyTruncate: Boolean? = null,
    @SerialName("rebind_survivor_row_ids")
    val rebindSurvivorRowIds: List<Long>? = null,
)

/**
 * ``status`` is absent only on the typed-stop-phrase reply (``voice_stopped``). After a truncation the survivor row
 * ids let the client rebind its cached ``rowId``s (``None`` map entries: drop the cached id). ``turn_isolation``
 * marks a compute-host dispatch.
 */
@Serializable
data class PromptSubmitResult(
    val status: PromptSubmitStatus? = null,
    @SerialName("voice_stopped")
    val voiceStopped: Boolean? = null,
    @SerialName("survivor_user_row_ids")
    val survivorUserRowIds: List<Long?>? = null,
    @SerialName("survivor_row_id_map")
    val survivorRowIdMap: Map<String, Long?>? = null,
    @SerialName("turn_isolation")
    val turnIsolation: Boolean? = null,
)

/**
 * ``content_base64`` (or the ``data`` alias) carries the bytes; ``filename`` / ``ext`` only hint the extension —
 * magic bytes decide.
 */
@Serializable
data class ImageAttachBytesParams(
    @SerialName("session_id")
    val sessionId: String,
    val profile: String? = null,
    @SerialName("content_base64")
    val contentBase64: String? = null,
    val data: String? = null,
    val filename: String? = null,
    val ext: String? = null,
)

/** ``methods_prompt.py::_attached_image_result``: the image is queued for the next turn. */
@Serializable
data class AttachedImageResult(
    val name: String? = null,
    val width: Long? = null,
    val height: Long? = null,
    @SerialName("token_estimate")
    val tokenEstimate: Long? = null,
    val attached: Boolean,
    val path: String? = null,
    val count: Long? = null,
    val remainder: String? = null,
    val text: String? = null,
    val bytes: Long? = null,
    val message: String? = null,
)

/** ``path`` when the file is gateway-visible, else ``data_url`` carries the bytes; ``name`` labels an uploaded file. */
@Serializable
data class FileAttachParams(
    @SerialName("session_id")
    val sessionId: String,
    val profile: String? = null,
    val path: String? = null,
    @SerialName("data_url")
    val dataUrl: String? = null,
    val name: String? = null,
)

@Serializable
data class FileAttachResult(
    val attached: Boolean,
    val name: String,
    val path: String,
    @SerialName("ref_path")
    val refPath: String,
    @SerialName("ref_text")
    val refText: String,
    val uploaded: Boolean,
)

@Serializable
data class ApprovalPendingParams(
    @SerialName("session_id")
    val sessionId: String,
    val profile: String? = null,
)

@Serializable
data class ApprovalPendingResult(
    val approvals: List<PendingApproval>,
)

/**
 * ``choice`` is one of the offered ``approval`` choices (once / session / always / deny); ``all`` resolves every
 * pending approval, ``request_id`` a specific one, neither the oldest.
 */
@Serializable
data class ApprovalRespondParams(
    @SerialName("session_id")
    val sessionId: String,
    val profile: String? = null,
    val choice: String? = null,
    val all: Boolean? = null,
    @SerialName("request_id")
    val requestId: String? = null,
)

@Serializable
data class ApprovalRespondResult(
    val resolved: Long,
)

/** ``tui_gateway/server.py::_approval_request_payload`` — the command is redacted server-side. */
@Serializable
data class ApprovalRequestParams(
    @SerialName("session_id")
    val sessionId: String,
    @SerialName("request_id")
    val requestId: String,
    val command: String = "",
    val description: String = "",
    val choices: List<ApprovalChoice>? = null,
    @SerialName("allow_permanent")
    val allowPermanent: Boolean? = null,
    @SerialName("allow_session")
    val allowSession: Boolean? = null,
    @SerialName("smart_denied")
    val smartDenied: Boolean? = null,
    @SerialName("tool_name")
    val toolName: String? = null,
    @SerialName("gateway_session_id")
    val gatewaySessionId: String? = null,
    /**
     * Claves de wire no modeladas (el schema es abierto:
     * ``additionalProperties: true``). ``@Transient``: kotlinx no las
     * captura al decodificar; queda como receptáculo documentado.
     */
    @Transient
    val extraKeys: JsonObject? = null,
)

@Serializable
data class ApprovalResult(
    val choice: ApprovalChoice,
    val all: Boolean? = null,
)

@Serializable
enum class ApprovalChoice {
    @SerialName("once")
    ONCE,

    @SerialName("session")
    SESSION,

    @SerialName("always")
    ALWAYS,

    @SerialName("deny")
    DENY,
}

/**
 * One unresolved ``tools/approval.py`` gateway queue entry as ``server._approval_request_payload`` renders it
 * (command redacted; ``choices`` precomputed). The key set is owned by the approval tool.
 */
@Serializable
data class PendingApproval(
    @SerialName("request_id")
    val requestId: String? = null,
    val command: String? = null,
    val description: String? = null,
    @SerialName("pattern_key")
    val patternKey: String? = null,
    @SerialName("pattern_keys")
    val patternKeys: List<String>? = null,
    @SerialName("allow_permanent")
    val allowPermanent: Boolean? = null,
    @SerialName("allow_session")
    val allowSession: Boolean? = null,
    @SerialName("smart_denied")
    val smartDenied: Boolean? = null,
    val choices: List<String>? = null,
    @SerialName("tool_name")
    val toolName: String? = null,
    /**
     * Claves de wire no modeladas (el schema es abierto:
     * ``additionalProperties: true``). ``@Transient``: kotlinx no las
     * captura al decodificar; queda como receptáculo documentado.
     */
    @Transient
    val extraKeys: JsonObject? = null,
)

/**
 * Single question: ``question`` / ``choices`` (/ ``multi_select``); batch: ``questions``. ``answers`` rides only on a
 * reconnect replay (locks the server already accepted).
 */
@Serializable
data class ClarifyRequestParams(
    @SerialName("session_id")
    val sessionId: String,
    val question: String? = null,
    val choices: List<String>? = null,
    @SerialName("multi_select")
    val multiSelect: Boolean? = null,
    val questions: List<ClarifyQuestion>? = null,
    val answers: Map<String, String>? = null,
)

@Serializable
data class ClarifyQuestion(
    val qid: String,
    val question: String,
    val choices: List<String>? = null,
    @SerialName("multi_select")
    val multiSelect: Boolean = false,
)

/**
 * Single: ``{answer}`` ('' = skip). Batch: ``{answers}`` for the whole set (early locks go through the
 * ``clarify.lock`` RPC); a response with neither is cancel-all.
 */
@Serializable
data class ClarifyResult(
    val answer: String? = null,
    val answers: Map<String, String>? = null,
)

@Serializable
enum class ClarifyLockStatus {
    @SerialName("ok")
    OK,

    @SerialName("expired")
    EXPIRED,
}

@Serializable
data class RequestAnswerParams(
    val id: String,
    val result: JsonObject,
    val profile: String? = null,
)

@Serializable
data class RequestAnswerResult(
    val status: ClarifyLockStatus,
)

@Serializable
data class SecretRequestParams(
    @SerialName("session_id")
    val sessionId: String,
    @SerialName("env_var")
    val envVar: String,
    val prompt: String,
    val metadata: JsonObject? = null,
)

@Serializable
data class EmptyRequestParams(
    @SerialName("session_id")
    val sessionId: String,
)

@Serializable
data class VaultCodeRequestParams(
    @SerialName("session_id")
    val sessionId: String,
    val site: String? = null,
    val hint: String? = null,
)

@Serializable
data class VaultSaveLoginRequestParams(
    @SerialName("session_id")
    val sessionId: String,
    val origin: String,
    val site: String,
)

@Serializable
data class VaultUnlockRequestParams(
    @SerialName("session_id")
    val sessionId: String,
    val backend: String,
    @SerialName("display_name")
    val displayName: String,
)

@Serializable
data class McpSetupRequestParams(
    @SerialName("session_id")
    val sessionId: String,
    val server: String? = null,
    val action: String? = null,
    val reason: String? = null,
)

/** ``tools/drive_preview_tool.py`` and ``tools/annotate_preview_tool.py`` field sets. */
@Serializable
data class PreviewActRequestParams(
    @SerialName("session_id")
    val sessionId: String,
    val action: String,
    val ref: String? = null,
    val selector: String? = null,
    val text: String? = null,
    val key: String? = null,
    val submit: Boolean? = null,
    val full: Boolean? = null,
    val to: String? = null,
    val amount: Long? = null,
    val max: Long? = null,
)

@Serializable
data class ReadRangeRequestParams(
    @SerialName("session_id")
    val sessionId: String,
    val start: Long? = null,
    val count: Long? = null,
)

/** ``tools/tour_tool.py`` field set. */
@Serializable
data class TourRequestParams(
    @SerialName("session_id")
    val sessionId: String,
    val action: String,
    val surface: String? = null,
    val selector: String? = null,
    val title: String? = null,
    val text: String? = null,
    val side: String? = null,
    val steps: List<TourStep>? = null,
    @SerialName("step_index")
    val stepIndex: Long? = null,
)

@Serializable
data class TourStep(
    val selector: String? = null,
    val title: String? = null,
    val text: String? = null,
    val side: String? = null,
    /**
     * Claves de wire no modeladas (el schema es abierto:
     * ``additionalProperties: true``). ``@Transient``: kotlinx no las
     * captura al decodificar; queda como receptáculo documentado.
     */
    @Transient
    val extraKeys: JsonObject? = null,
)

/**
 * The answer to any one-string prompt (sudo, secret, vault prompts, desktop bridges, mcp.setup): ``''`` means skipped
 * / declined.
 */
@Serializable
data class ValueResult(
    val value: String,
)

@Serializable
data class BrowserControllerRegisterParams(
    @SerialName("session_id")
    val sessionId: String,
    @SerialName("controller_id")
    val controllerId: String,
    @SerialName("browser_profile_id")
    val browserProfileId: String,
    val capabilities: List<String>? = null,
    @SerialName("protocol_version")
    val protocolVersion: JsonElement? = null,
    @SerialName("principal_id")
    val principalId: String? = null,
)

@Serializable
data class BrowserControllerRegisterResult(
    val scope: ControllerScope,
)

@Serializable
data class BrowserControllerResultParams(
    @SerialName("session_id")
    val sessionId: String,
    @SerialName("command_id")
    val commandId: String,
    val ok: JsonElement? = null,
    val result: JsonElement? = null,
    val error: JsonElement? = null,
)

@Serializable
data class BrowserControllerResultResult(
    val accepted: Boolean,
)

/** Every controller call names the session the controller is attached to. */
@Serializable
data class BrowserControllerParams(
    @SerialName("session_id")
    val sessionId: String,
)

@Serializable
data class BrowserControllerDetachResult(
    val detached: Boolean = true,
)

/** ``gateway/browser_control_broker.py`` FRAME_COMMAND params. */
@Serializable
data class BrowserControllerCommandPayload(
    @SerialName("command_id")
    val commandId: String,
    val action: String,
    val arguments: JsonObject,
    @SerialName("controller_id")
    val controllerId: String? = null,
    @SerialName("browser_profile_id")
    val browserProfileId: String? = null,
    @SerialName("tool_call_id")
    val toolCallId: String? = null,
)

/** ``gateway/browser_control_broker.py::_cancel_frame``. */
@Serializable
data class BrowserControllerCancelPayload(
    @SerialName("command_id")
    val commandId: String,
    @SerialName("tool_call_id")
    val toolCallId: String? = null,
)

/** ``methods_browser`` announce(); ``level``: info | warn | error. */
@Serializable
data class BrowserProgressPayload(
    val message: String,
    val level: String,
)

@Serializable
data class ControllerScope(
    @SerialName("principal_id")
    val principalId: String,
    @SerialName("profile_id")
    val profileId: String,
    @SerialName("session_id")
    val sessionId: String,
    @SerialName("controller_id")
    val controllerId: String,
    @SerialName("browser_profile_id")
    val browserProfileId: String,
    @SerialName("transport_family")
    val transportFamily: String,
    val capabilities: List<String>,
)

/** ``tui_gateway/entry.py`` (stdio) / ``tui_gateway/ws.py`` (WebSocket) first frame. */
@Serializable
data class GatewayReadyPayload(
    val skin: SkinPayload,
    @SerialName("change_events")
    val changeEvents: Boolean,
    @SerialName("replay_epoch")
    val replayEpoch: String,
    val heartbeat: Boolean? = null,
)

/**
 * ``tui_gateway/change_watcher.py::resolve_skin`` — the resolved active skin (``HermesSkin``). ``{}`` when the skin
 * engine failed to load. Colour maps are token → colour string.
 */
@Serializable
data class SkinPayload(
    val name: String = "",
    val description: String = "",
    val colors: Map<String, String>? = null,
    @SerialName("light_colors")
    val lightColors: Map<String, String>? = null,
    @SerialName("dark_colors")
    val darkColors: Map<String, String>? = null,
    val branding: Map<String, String>? = null,
    @SerialName("banner_logo")
    val bannerLogo: String = "",
    @SerialName("banner_hero")
    val bannerHero: String = "",
    @SerialName("tool_prefix")
    val toolPrefix: String = "",
    @SerialName("help_header")
    val helpHeader: String = "",
    /**
     * Claves de wire no modeladas (el schema es abierto:
     * ``additionalProperties: true``). ``@Transient``: kotlinx no las
     * captura al decodificar; queda como receptáculo documentado.
     */
    @Transient
    val extraKeys: JsonObject? = null,
)

/**
 * ``prompt_turn._invoke_agent._stream`` (message.delta: ``text`` + optional ``rendered``),
 * ``agent_callbacks._agent_cbs`` (reasoning.delta / thinking.delta), ``tool_progress._progress_reasoning``
 * (reasoning.available). ``verbose`` rides only when the session's verbose reasoning mode is on.
 */
@Serializable
data class StreamDeltaPayload(
    val text: String,
    val rendered: String? = null,
    val verbose: Boolean? = null,
)

/**
 * ``prompt_turn._complete_turn_payload`` / ``session_auto_continue._emit_terminal_turn_error`` /
 * ``agent_callbacks._mirror_subagent_to_child`` (child watch mirror: ``text`` only) / ``compute_host_bridge``
 * (``text`` + ``status``).
 */
@Serializable
data class MessageCompletePayload(
    val text: JsonElement = JsonPrimitive(""),
    val usage: Usage? = null,
    val status: TurnStatus? = null,
    val reasoning: String? = null,
    val warning: String? = null,
    @SerialName("response_previewed")
    val responsePreviewed: Boolean? = null,
    val billing: BillingBlock? = null,
    @SerialName("failure_reason")
    val failureReason: String? = null,
    val rendered: String? = null,
    val error: String? = null,
    val recoverable: Boolean? = null,
    @SerialName("error_surface")
    val errorSurface: ErrorSurface? = null,
    val partial: Boolean? = null,
)

/** ``prompt_turn._interim_assistant_cb`` / ``agent_callbacks`` interim_assistant_callback. */
@Serializable
data class MessageInterimPayload(
    val text: String,
    @SerialName("already_streamed")
    val alreadyStreamed: Boolean,
)

/**
 * ``tool_progress._on_tool_start`` (+ ``agent_callbacks._mirror_subagent_to_child`` rows with ``preview`` and empty
 * ``args``). ``todos``/``revision`` are NOT set by the emitter; kept optional because tool.start rows may pass
 * through connector redaction unchanged.
 */
@Serializable
data class ToolStartPayload(
    @SerialName("tool_id")
    val toolId: String,
    val name: String,
    val context: String? = null,
    val args: JsonObject? = null,
    @SerialName("args_text")
    val argsText: String? = null,
    val preview: String? = null,
)

/** ``tool_progress._on_tool_complete``; ``todos``/``revision`` merged in for the todo tools. */
@Serializable
data class ToolCompletePayload(
    @SerialName("tool_id")
    val toolId: String,
    val name: String,
    val args: JsonObject? = null,
    @SerialName("duration_s")
    val durationS: Double? = null,
    val result: JsonElement? = null,
    val summary: String? = null,
    @SerialName("result_text")
    val resultText: String? = null,
    @SerialName("inline_diff")
    val inlineDiff: String? = null,
    val todos: List<JsonElement>? = null,
    val revision: Long? = null,
)

/** ``server._status_update`` and the direct emitters (goal / loop / heartbeat / process). */
@Serializable
data class StatusUpdatePayload(
    val kind: String,
    val text: String,
)

/** ``prompt_turn._invoke_agent`` ``_on_session_title`` hook. */
@Serializable
data class SessionTitlePayload(
    @SerialName("session_id")
    val sessionId: String,
    val title: String,
)

/** ``change_watcher._CHANGE_WATCHES`` payload fn — ``{}`` for every watch except pet.changed. */
@Serializable
data class ChangeSignalPayload(
    /**
     * Claves de wire no modeladas (el schema es abierto:
     * ``additionalProperties: true``). ``@Transient``: kotlinx no las
     * captura al decodificar; queda como receptáculo documentado.
     */
    @Transient
    val extraKeys: JsonObject? = null,
)

/**
 * ``tui_gateway/server.py::_session_info`` — the ``session.info`` event and the ``info`` field of ``session.create``
 * / ``session.resume`` / ``session.activate`` results.
 */
@Serializable
data class SessionLiveInfo(
    val model: String = "",
    val provider: String = "",
    @SerialName("reasoning_effort")
    val reasoningEffort: String = "",
    @SerialName("service_tier")
    val serviceTier: String = "",
    val fast: Boolean = false,
    val yolo: Boolean = false,
    @SerialName("approval_mode")
    val approvalMode: String = "manual",
    val tools: Map<String, List<String>>? = null,
    val skills: Map<String, List<String>>? = null,
    val cwd: String = "",
    val branch: String? = null,
    val project: ProjectRef? = null,
    @SerialName("terminal_backend")
    val terminalBackend: String = "",
    val personality: String = "",
    val running: Boolean = false,
    @SerialName("turn_started_at")
    val turnStartedAt: Double? = null,
    val title: String = "",
    @SerialName("stored_session_id")
    val storedSessionId: String = "",
    @SerialName("desktop_contract")
    val desktopContract: JsonElement? = null,
    val version: String = "",
    @SerialName("release_date")
    val releaseDate: String = "",
    @SerialName("update_behind")
    val updateBehind: JsonElement? = null,
    @SerialName("update_command")
    val updateCommand: String = "",
    val usage: Usage? = null,
    @SerialName("profile_name")
    val profileName: String? = null,
    @SerialName("mcp_servers")
    val mcpServers: List<McpServerStatus>? = null,
    @SerialName("system_prompt")
    val systemPrompt: String? = null,
    @SerialName("credential_warning")
    val credentialWarning: String? = null,
    val lazy: Boolean? = null,
    /**
     * Claves de wire no modeladas (el schema es abierto:
     * ``additionalProperties: true``). ``@Transient``: kotlinx no las
     * captura al decodificar; queda como receptáculo documentado.
     */
    @Transient
    val extraKeys: JsonObject? = null,
)

/** Every ``_emit("error", …)`` site sets exactly ``message``. */
@Serializable
data class ErrorPayload(
    val message: String,
)

/** ``tui_gateway/model_switch.py`` capability-refresh notice. */
@Serializable
data class NoticePayload(
    val message: String,
)

@Serializable
data class RequestCancelPayload(
    val id: String,
    val method: String,
    val reason: String,
)

/** ``methods_voice._vr_transcript`` / ``_deliver_fd_transcript`` / typed stop phrase in methods_prompt. */
@Serializable
data class VoiceTranscriptPayload(
    val text: String? = null,
    @SerialName("stop_phrase")
    val stopPhrase: Boolean? = null,
    val typed: Boolean? = null,
    @SerialName("no_speech_limit")
    val noSpeechLimit: Boolean? = null,
)

/**
 * One transcript row as the gateway PROJECTS it for renderers (``session_history._project_history``): ``text`` (never
 * ``content``), display-only ``timestamp`` / ``display_kind`` / ``display_metadata``, the durable ``row_id`` rewind
 * targets, and for tool rows ``name`` + ``context`` preview + full ``args``. Assistant detail sidecars
 * (``reasoning``, …) ride as extra keys.
 */
@Serializable
data class TranscriptMessage(
    val role: String,
    val text: String? = null,
    val timestamp: Double? = null,
    @SerialName("row_id")
    val rowId: Long? = null,
    @SerialName("display_kind")
    val displayKind: String? = null,
    @SerialName("display_metadata")
    val displayMetadata: JsonElement? = null,
    val name: String? = null,
    val context: String? = null,
    val args: JsonObject? = null,
    val reasoning: String? = null,
    /**
     * Claves de wire no modeladas (el schema es abierto:
     * ``additionalProperties: true``). ``@Transient``: kotlinx no las
     * captura al decodificar; queda como receptáculo documentado.
     */
    @Transient
    val extraKeys: JsonObject? = null,
)

/**
 * One create-time transcript row (``session_history._coerce_seed_history``); ``text`` is the legacy alias of
 * ``content``; only ``display_kind: "hidden"`` is accepted from the wire. Clients forward stored rows verbatim
 * (``_row_id``, ``timestamp``, …) and the coercer drops what it does not use, so the row stays open.
 */
@Serializable
data class SeedMessage(
    val role: String,
    val content: String? = null,
    val text: String? = null,
    @SerialName("display_kind")
    val displayKind: String? = null,
    /**
     * Claves de wire no modeladas (el schema es abierto:
     * ``additionalProperties: true``). ``@Transient``: kotlinx no las
     * captura al decodificar; queda como receptáculo documentado.
     */
    @Transient
    val extraKeys: JsonObject? = null,
)

/**
 * One unanswered server→client request (``server_requests.Request.snapshot``); the reconnecting client re-delivers it
 * to its request handlers.
 */
@Serializable
data class OpenRequestEntry(
    val id: String,
    val method: String,
    val params: JsonObject,
)

/**
 * ``session_auto_continue._inflight_snapshot``: the live (or retained failed) turn a reconnecting client rebuilds its
 * bubbles from.
 */
@Serializable
data class InflightTurn(
    val assistant: String = "",
    val streaming: Boolean = false,
    val user: String = "",
    val corrections: List<String>? = null,
    @SerialName("correction_offsets")
    val correctionOffsets: List<Long>? = null,
    val error: String? = null,
    val status: String? = null,
    val recoverable: Boolean? = null,
    @SerialName("error_surface")
    val errorSurface: JsonObject? = null,
)

@Serializable
data class QueuedPrompt(
    val user: String,
)

/** ``tui_gateway/server.py::_get_usage`` + ``agent/context_breakdown.py::context_usage_fields``. */
@Serializable
data class Usage(
    val model: String = "",
    val input: Long = 0L,
    val output: Long = 0L,
    val reasoning: Long = 0L,
    val prompt: Long = 0L,
    val completion: Long = 0L,
    val total: Long = 0L,
    val calls: Long = 0L,
    val compressions: Long? = null,
    @SerialName("context_used")
    val contextUsed: Long? = null,
    @SerialName("context_max")
    val contextMax: Long? = null,
    @SerialName("context_percent")
    val contextPercent: Long? = null,
    @SerialName("context_source")
    val contextSource: String? = null,
    @SerialName("context_estimated")
    val contextEstimated: Boolean? = null,
    @SerialName("cache_hit_pct")
    val cacheHitPct: Long? = null,
    @SerialName("cache_read")
    val cacheRead: Long? = null,
    @SerialName("cache_write")
    val cacheWrite: Long? = null,
    @SerialName("avg_latency_s")
    val avgLatencyS: Double? = null,
    @SerialName("avg_tps")
    val avgTps: Double? = null,
    @SerialName("active_subagents")
    val activeSubagents: Long? = null,
    @SerialName("dev_credits_spent_micros")
    val devCreditsSpentMicros: Long? = null,
    @SerialName("cost_usd")
    val costUsd: Double? = null,
    @SerialName("cost_status")
    val costStatus: String? = null,
    /**
     * Claves de wire no modeladas (el schema es abierto:
     * ``additionalProperties: true``). ``@Transient``: kotlinx no las
     * captura al decodificar; queda como receptáculo documentado.
     */
    @Transient
    val extraKeys: JsonObject? = null,
)

/** ``tool_progress._normalize_todo_state``: the authoritative todo snapshot. */
@Serializable
data class TodoState(
    val todos: List<JsonObject>,
    val revision: Long,
)

/** A crash-interrupted turn was scheduled to continue right after this resume. */
@Serializable
data class AutoContinue(
    val attempt: Long,
    @SerialName("interrupted_at")
    val interruptedAt: Double,
)

/** ``tui_gateway/server.py::_project_info_for_cwd``. */
@Serializable
data class ProjectRef(
    val id: String,
    val slug: String,
    val name: String,
    @SerialName("primary_path")
    val primaryPath: String? = null,
)

@Serializable
data class McpServerStatus(
    val name: String = "",
    val status: String? = null,
    @SerialName("tool_count")
    val toolCount: Long? = null,
    val error: String? = null,
    /**
     * Claves de wire no modeladas (el schema es abierto:
     * ``additionalProperties: true``). ``@Transient``: kotlinx no las
     * captura al decodificar; queda como receptáculo documentado.
     */
    @Transient
    val extraKeys: JsonObject? = null,
)

@Serializable
enum class InterruptStatus {
    @SerialName("interrupted")
    INTERRUPTED,

    @SerialName("not_interrupted")
    NOT_INTERRUPTED,
}

@Serializable
enum class PromptSubmitStatus {
    @SerialName("streaming")
    STREAMING,

    @SerialName("queued")
    QUEUED,

    @SerialName("steered")
    STEERED,

    @SerialName("redirected")
    REDIRECTED,
}

/** ``prompt_turn._result_status``. */
@Serializable
enum class TurnStatus {
    @SerialName("complete")
    COMPLETE,

    @SerialName("error")
    ERROR,

    @SerialName("interrupted")
    INTERRUPTED,
}

/** ``agent/billing_links.py::BillingBlock.to_dict`` (+ ``unverified`` from conversation_loop). */
@Serializable
data class BillingBlock(
    val provider: String,
    @SerialName("provider_label")
    val providerLabel: String,
    val model: String,
    @SerialName("billing_url")
    val billingUrl: String?,
    @SerialName("is_nous")
    val isNous: Boolean,
    val message: String,
    val unverified: Boolean? = null,
)

/** ``agent/error_surface.py::_surface`` — advisory {layer, code, retryable} (+ identity, + auth hint). */
@Serializable
data class ErrorSurface(
    val layer: String,
    val code: String,
    val retryable: Boolean,
    val provider: String? = null,
    val model: String? = null,
    /**
     * Claves de wire no modeladas (el schema es abierto:
     * ``additionalProperties: true``). ``@Transient``: kotlinx no las
     * captura al decodificar; queda como receptáculo documentado.
     */
    @Transient
    val extraKeys: JsonObject? = null,
)
