package ai.hermes.mama.gateway

import ai.hermes.mama.contract.ApprovalPendingParams
import ai.hermes.mama.contract.ApprovalPendingResult
import ai.hermes.mama.contract.ApprovalRespondParams
import ai.hermes.mama.contract.ApprovalRespondResult
import ai.hermes.mama.contract.AttachedImageResult
import ai.hermes.mama.contract.BrowserControllerDetachResult
import ai.hermes.mama.contract.BrowserControllerParams
import ai.hermes.mama.contract.BrowserControllerRegisterParams
import ai.hermes.mama.contract.BrowserControllerRegisterResult
import ai.hermes.mama.contract.BrowserControllerResultParams
import ai.hermes.mama.contract.BrowserControllerResultResult
import ai.hermes.mama.contract.FileAttachParams
import ai.hermes.mama.contract.FileAttachResult
import ai.hermes.mama.contract.GatewayCapabilitiesResult
import ai.hermes.mama.contract.ImageAttachBytesParams
import ai.hermes.mama.contract.OkResult
import ai.hermes.mama.contract.PingParams
import ai.hermes.mama.contract.PromptSubmitParams
import ai.hermes.mama.contract.PromptSubmitResult
import ai.hermes.mama.contract.RequestAnswerParams
import ai.hermes.mama.contract.RequestAnswerResult
import ai.hermes.mama.contract.RpcMethods
import ai.hermes.mama.contract.SessionCreateParams
import ai.hermes.mama.contract.SessionCreateResult
import ai.hermes.mama.contract.SessionDeleteParams
import ai.hermes.mama.contract.SessionDeleteResult
import ai.hermes.mama.contract.SessionEventsSinceParams
import ai.hermes.mama.contract.SessionEventsSinceResult
import ai.hermes.mama.contract.SessionHistoryParams
import ai.hermes.mama.contract.SessionHistoryResult
import ai.hermes.mama.contract.SessionInterruptParams
import ai.hermes.mama.contract.SessionInterruptResult
import ai.hermes.mama.contract.SessionListParams
import ai.hermes.mama.contract.SessionListResult
import ai.hermes.mama.contract.SessionResumeParams
import ai.hermes.mama.contract.SessionResumeResult
import ai.hermes.mama.contract.SessionTitleParams
import ai.hermes.mama.contract.SessionTitleResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Golden §5/B4: cada método de §2.3 serializa EXACTAMENTE los params de su
 * fixture en `testing/fixtures/rpc/` y decodifica el result de fixture a su
 * DTO. Los fixtures son sintéticos (`sess_*`, `usuario`, §7.2).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GatewayClientRpcTest {
    @Test
    fun `gateway ping - params vacios y result OkResult (forma WS)`() =
        assertRpcGolden(
            RpcMethods.GATEWAY_PING,
            "PingParams",
            "OkResult",
            serializer<PingParams>(),
            OkResult.serializer(),
        ) { ping() }

    @Test
    fun `gateway capabilities - params vacios y result tipado`() =
        assertRpcGolden(
            RpcMethods.GATEWAY_CAPABILITIES,
            "PingParams",
            "GatewayCapabilitiesResult",
            serializer<PingParams>(),
            GatewayCapabilitiesResult.serializer(),
        ) { capabilities() }

    @Test
    fun `session list - golden`() =
        assertRpcGolden(
            RpcMethods.SESSION_LIST,
            "SessionListParams",
            "SessionListResult",
            SessionListParams.serializer(),
            SessionListResult.serializer(),
        ) { params -> listSessions(params) }

    @Test
    fun `session create - golden`() =
        assertRpcGolden(
            RpcMethods.SESSION_CREATE,
            "SessionCreateParams",
            "SessionCreateResult",
            SessionCreateParams.serializer(),
            SessionCreateResult.serializer(),
        ) { params -> createSession(params) }

    @Test
    fun `session resume - golden`() =
        assertRpcGolden(
            RpcMethods.SESSION_RESUME,
            "SessionResumeParams",
            "SessionResumeResult",
            SessionResumeParams.serializer(),
            SessionResumeResult.serializer(),
        ) { params -> resumeSession(params) }

    @Test
    fun `session history - golden`() =
        assertRpcGolden(
            RpcMethods.SESSION_HISTORY,
            "SessionHistoryParams",
            "SessionHistoryResult",
            SessionHistoryParams.serializer(),
            SessionHistoryResult.serializer(),
        ) { params -> sessionHistory(params) }

    @Test
    fun `session title - golden`() =
        assertRpcGolden(
            RpcMethods.SESSION_TITLE,
            "SessionTitleParams",
            "SessionTitleResult",
            SessionTitleParams.serializer(),
            SessionTitleResult.serializer(),
        ) { params -> renameSession(params) }

    @Test
    fun `session delete - golden`() =
        assertRpcGolden(
            RpcMethods.SESSION_DELETE,
            "SessionDeleteParams",
            "SessionDeleteResult",
            SessionDeleteParams.serializer(),
            SessionDeleteResult.serializer(),
        ) { params -> deleteSession(params) }

    @Test
    fun `session interrupt - golden`() =
        assertRpcGolden(
            RpcMethods.SESSION_INTERRUPT,
            "SessionInterruptParams",
            "SessionInterruptResult",
            SessionInterruptParams.serializer(),
            SessionInterruptResult.serializer(),
        ) { params -> interruptSession(params) }

    @Test
    fun `session events since - golden`() =
        assertRpcGolden(
            RpcMethods.SESSION_EVENTS_SINCE,
            "SessionEventsSinceParams",
            "SessionEventsSinceResult",
            SessionEventsSinceParams.serializer(),
            SessionEventsSinceResult.serializer(),
        ) { params -> sessionEventsSince(params) }

    @Test
    fun `prompt submit - golden`() =
        assertRpcGolden(
            RpcMethods.PROMPT_SUBMIT,
            "PromptSubmitParams",
            "PromptSubmitResult",
            PromptSubmitParams.serializer(),
            PromptSubmitResult.serializer(),
        ) { params -> submitPrompt(params) }

    @Test
    fun `image attach bytes - golden`() =
        assertRpcGolden(
            RpcMethods.IMAGE_ATTACH_BYTES,
            "ImageAttachBytesParams",
            "AttachedImageResult",
            ImageAttachBytesParams.serializer(),
            AttachedImageResult.serializer(),
        ) { params -> attachImage(params) }

    @Test
    fun `file attach - golden`() =
        assertRpcGolden(
            RpcMethods.FILE_ATTACH,
            "FileAttachParams",
            "FileAttachResult",
            FileAttachParams.serializer(),
            FileAttachResult.serializer(),
        ) { params -> attachFile(params) }

    @Test
    fun `approval pending - golden`() =
        assertRpcGolden(
            RpcMethods.APPROVAL_PENDING,
            "ApprovalPendingParams",
            "ApprovalPendingResult",
            ApprovalPendingParams.serializer(),
            ApprovalPendingResult.serializer(),
        ) { params -> pendingApprovals(params) }

    @Test
    fun `approval respond - golden`() =
        assertRpcGolden(
            RpcMethods.APPROVAL_RESPOND,
            "ApprovalRespondParams",
            "ApprovalRespondResult",
            ApprovalRespondParams.serializer(),
            ApprovalRespondResult.serializer(),
        ) { params -> respondApproval(params) }

    @Test
    fun `request answer - golden`() =
        assertRpcGolden(
            RpcMethods.REQUEST_ANSWER,
            "RequestAnswerParams",
            "RequestAnswerResult",
            RequestAnswerParams.serializer(),
            RequestAnswerResult.serializer(),
        ) { params -> answerRequest(params) }

    @Test
    fun `browser controller register - golden`() =
        assertRpcGolden(
            RpcMethods.BROWSER_CONTROLLER_REGISTER,
            "BrowserControllerRegisterParams",
            "BrowserControllerRegisterResult",
            BrowserControllerRegisterParams.serializer(),
            BrowserControllerRegisterResult.serializer(),
        ) { params -> registerBrowserController(params) }

    @Test
    fun `browser controller result - golden`() =
        assertRpcGolden(
            RpcMethods.BROWSER_CONTROLLER_RESULT,
            "BrowserControllerResultParams",
            "BrowserControllerResultResult",
            BrowserControllerResultParams.serializer(),
            BrowserControllerResultResult.serializer(),
        ) { params -> sendBrowserControllerResult(params) }

    @Test
    fun `browser controller heartbeat - golden`() =
        assertRpcGolden(
            RpcMethods.BROWSER_CONTROLLER_HEARTBEAT,
            "BrowserControllerParams",
            "OkResult",
            BrowserControllerParams.serializer(),
            OkResult.serializer(),
        ) { params -> browserControllerHeartbeat(params) }

    @Test
    fun `browser controller detach - golden`() =
        assertRpcGolden(
            RpcMethods.BROWSER_CONTROLLER_DETACH,
            "BrowserControllerParams",
            "BrowserControllerDetachResult",
            BrowserControllerParams.serializer(),
            BrowserControllerDetachResult.serializer(),
        ) { params -> detachBrowserController(params) }

    @Test
    fun `result que no casa con el contrato produce ResultDecodeException`() =
        runTest {
            val transport = FakeTransport()
            val client = newGatewayClient(transport)

            val call = async { runCatching { client.listSessions() } }
            runCurrent()
            val id = idOf(transport.sentFrames().single())

            transport.emit("""{"id":$id,"result":{"sessions":"no-es-una-lista"}}""")
            val error = assertNotNull(call.await().exceptionOrNull())
            assertIs<ResultDecodeException>(error)
            assertEquals(RpcMethods.SESSION_LIST, error.method)
        }
}
