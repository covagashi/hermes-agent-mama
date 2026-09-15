package ai.hermes.mama.gateway

import ai.hermes.mama.contract.ApprovalChoice
import ai.hermes.mama.contract.PromptSubmitStatus
import ai.hermes.mama.contract.RpcMethods
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Sobrecargas "forma de la app" (§2.3/§2.6): los argumentos sueltos fijan
 * `source`/`surface` = `android`, `protocol_version` = 1 y los ids correctos.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GatewayClientAppCallsTest {
    @Test
    fun `submitPrompt fija surface android text plano y voice_context`() =
        runTest {
            val transport = FakeTransport()
            val client = newGatewayClient(transport)

            val call = async { client.submitPrompt("sess-1", "hola mamá", voiceContext = "dictado") }
            runCurrent()

            val frame = transport.sentFrames().single()
            assertEquals(RpcMethods.PROMPT_SUBMIT, frame.getValue("method").jsonPrimitive.content)
            assertEquals(
                testJson.parseToJsonElement(
                    """{"session_id":"sess-1","text":"hola mamá","surface":"android","voice_context":"dictado"}""",
                ),
                frame.getValue("params"),
            )

            transport.emit("""{"id":${idOf(frame)},"result":{"status":"streaming"}}""")
            assertEquals(PromptSubmitStatus.STREAMING, call.await().status)
        }

    @Test
    fun `createSession y resumeSession fijan source android`() =
        runTest {
            val transport = FakeTransport()
            val client = newGatewayClient(transport)

            val create = async { client.createSession() }
            runCurrent()
            val createFrame = transport.sentFrames().single()
            assertEquals(
                testJson.parseToJsonElement("""{"source":"android"}"""),
                createFrame.getValue("params"),
            )
            transport.emit("""{"id":${idOf(createFrame)},"result":${fixtureText("SessionCreateResult")}}""")
            assertEquals("sess_7f2a1c", create.await().sessionId)
            assertEquals("stored_7f2a1c", create.await().storedSessionId)

            val resume = async { client.resumeSession("stored_7f2a1c") }
            runCurrent()
            val resumeFrame = transport.sentFrames().last()
            assertEquals(
                testJson.parseToJsonElement("""{"session_id":"stored_7f2a1c","source":"android"}"""),
                resumeFrame.getValue("params"),
            )
            transport.emit(
                """{"id":${idOf(resumeFrame)},"result":""" +
                    """{"session_id":"rt-9","message_count":0,"messages":[],"info":{}}}""",
            )
            assertEquals("rt-9", resume.await().sessionId)
        }

    @Test
    fun `respondApproval serializa la eleccion por su nombre de wire`() =
        runTest {
            val transport = FakeTransport()
            val client = newGatewayClient(transport)

            val call = async { client.respondApproval("sess-1", "req-42", ApprovalChoice.DENY) }
            runCurrent()

            val frame = transport.sentFrames().single()
            assertEquals(RpcMethods.APPROVAL_RESPOND, frame.getValue("method").jsonPrimitive.content)
            assertEquals(
                testJson.parseToJsonElement(
                    """{"session_id":"sess-1","request_id":"req-42","choice":"deny"}""",
                ),
                frame.getValue("params"),
            )
            transport.emit("""{"id":${idOf(frame)},"result":{"resolved":1}}""")
            assertEquals(1, call.await().resolved)
        }

    @Test
    fun `registerBrowserController fija protocol_version 1`() =
        runTest {
            val transport = FakeTransport()
            val client = newGatewayClient(transport)

            val call =
                async {
                    client.registerBrowserController(
                        sessionId = "sess-1",
                        controllerId = "android-3f6e1c9a-2b7d-4c1e-9f0a-1b2c3d4e5f6a",
                        browserProfileId = "mama-webview",
                        capabilities = listOf("browser_navigate", "browser_click"),
                    )
                }
            runCurrent()

            val frame = transport.sentFrames().single()
            assertEquals(
                RpcMethods.BROWSER_CONTROLLER_REGISTER,
                frame.getValue("method").jsonPrimitive.content,
            )
            assertEquals(
                testJson.parseToJsonElement(
                    """{"session_id":"sess-1","controller_id":"android-3f6e1c9a-2b7d-4c1e-9f0a-1b2c3d4e5f6a",""" +
                        """"browser_profile_id":"mama-webview","capabilities":["browser_navigate","browser_click"],""" +
                        """"protocol_version":1}""",
                ),
                frame.getValue("params"),
            )
            transport.emit("""{"id":${idOf(frame)},"result":${fixtureText("BrowserControllerRegisterResult")}}""")
            assertEquals("mama-webview", call.await().scope.browserProfileId)
        }

    @Test
    fun `atajos de una pieza serializan el session_id correcto`() =
        runTest {
            val transport = FakeTransport()
            val client = newGatewayClient(transport)

            val cases =
                listOf<suspend () -> Unit>(
                    { client.sessionHistory("sess-1") },
                    { client.renameSession("sess-1", "Facturas") },
                    { client.deleteSession("stored-9") },
                    { client.interruptSession("sess-1") },
                    { client.sessionEventsSince("sess-1", 42) },
                    { client.pendingApprovals("sess-1") },
                    { client.browserControllerHeartbeat("sess-1") },
                    { client.detachBrowserController("sess-1") },
                )
            val expected =
                listOf(
                    RpcMethods.SESSION_HISTORY to """{"session_id":"sess-1"}""",
                    RpcMethods.SESSION_TITLE to """{"session_id":"sess-1","title":"Facturas"}""",
                    RpcMethods.SESSION_DELETE to """{"session_id":"stored-9"}""",
                    RpcMethods.SESSION_INTERRUPT to """{"session_id":"sess-1"}""",
                    RpcMethods.SESSION_EVENTS_SINCE to """{"session_id":"sess-1","last_seen":42}""",
                    RpcMethods.APPROVAL_PENDING to """{"session_id":"sess-1"}""",
                    RpcMethods.BROWSER_CONTROLLER_HEARTBEAT to """{"session_id":"sess-1"}""",
                    RpcMethods.BROWSER_CONTROLLER_DETACH to """{"session_id":"sess-1"}""",
                )

            cases.forEach { async { runCatching { it() } } }
            runCurrent()

            transport.sentFrames().zip(expected).forEach { (frame, expectedPair) ->
                val (method, paramsJson) = expectedPair
                assertEquals(method, frame.getValue("method").jsonPrimitive.content)
                assertEquals(testJson.parseToJsonElement(paramsJson), frame.getValue("params"))
            }
        }
}
