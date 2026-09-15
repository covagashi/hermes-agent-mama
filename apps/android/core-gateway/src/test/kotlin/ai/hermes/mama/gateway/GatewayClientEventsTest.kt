package ai.hermes.mama.gateway

import ai.hermes.mama.contract.EventTypes
import ai.hermes.mama.contract.MessageInterimPayload
import ai.hermes.mama.contract.StreamDeltaPayload
import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Eventos del client (§2.4, B4): [GatewayClient.eventsFor] filtra por sesión
 * conservando los broadcasts y [GatewayClient.decodePayload] decodifica
 * tolerante a payloads que no casan.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GatewayClientEventsTest {
    @Test
    fun `eventsFor entrega la sesion pedida y los broadcasts pero no otras sesiones`() =
        runTest {
            val transport = FakeTransport()
            val client = newGatewayClient(transport)

            client.eventsFor("sess-1").test {
                transport.emit(
                    """{"method":"event","params":{"type":"message.delta",""" +
                        """"session_id":"sess-1","seq":1,"payload":{"text":"hola"}}}""",
                )
                transport.emit(
                    """{"method":"event","params":{"type":"message.delta",""" +
                        """"session_id":"sess-2","seq":2,"payload":{"text":"otra sesión"}}}""",
                )
                // Broadcast: el backend manda session_id "" → el canal lo normaliza a null.
                transport.emit(
                    """{"method":"event","params":{"type":"sessions.changed","session_id":"","seq":3,"payload":{}}}""",
                )

                val delta = awaitItem()
                assertEquals("sess-1", delta.sessionId)
                assertEquals(EventTypes.MESSAGE_DELTA, delta.type)

                // sess-2 se filtró fuera; el siguiente es el broadcast (sessionId null).
                val broadcast = awaitItem()
                assertEquals(EventTypes.SESSIONS_CHANGED, broadcast.type)
                assertNull(broadcast.sessionId)

                expectNoEvents()
            }
        }

    @Test
    fun `decodePayload decodifica al DTO del evento`() =
        runTest {
            val transport = FakeTransport()
            val client = newGatewayClient(transport)

            client.eventsFor("sess-1").test {
                transport.emit(
                    """{"method":"event","params":{"type":"message.delta",""" +
                        """"session_id":"sess-1","seq":7,"payload":{"text":"factura 📄","rendered":"<b>f</b>"}}}""",
                )
                val event = awaitItem()
                val payload = client.decodePayload(event, StreamDeltaPayload.serializer())
                assertEquals("factura 📄", payload?.text)
                assertEquals("<b>f</b>", payload?.rendered)
            }
        }

    @Test
    fun `decodePayload devuelve null si el payload no casa (y avisa)`() =
        runTest {
            val warnings = mutableListOf<String>()
            val transport = FakeTransport()
            val client = newGatewayClient(transport, logger = { warnings += it })

            val event =
                GatewayEvent(
                    type = EventTypes.MESSAGE_DELTA,
                    sessionId = "sess-1",
                    seq = 1,
                    payload = buildJsonObject { put("sin_text", true) },
                )
            // StreamDeltaPayload.text es requerido → null tolerante.
            assertNull(client.decodePayload(event, StreamDeltaPayload.serializer()))
            assertNull(client.decodePayload(event, MessageInterimPayload.serializer()))
            assertEquals(2, warnings.size)
        }
}
