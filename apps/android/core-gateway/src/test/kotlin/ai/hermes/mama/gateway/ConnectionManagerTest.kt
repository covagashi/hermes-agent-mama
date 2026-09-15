package ai.hermes.mama.gateway

import ai.hermes.mama.contract.RpcMethods
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private fun readyFrame(epoch: String?) =
    if (epoch == null) {
        """{"method":"event","params":{"type":"gateway.ready","payload":{"skin":{},"change_events":true}}}"""
    } else {
        """{"method":"event","params":{"type":"gateway.ready","payload":{"skin":{},""" +
            """"change_events":true,"replay_epoch":"$epoch"}}}"""
    }

/**
 * Contrato de [ConnectionManager] (ROADMAP §2.1/§2.2, tarea B2).
 *
 * Los tests de comportamiento usan transports en memoria + tiempo virtual
 * (`TestScope` + `advanceTimeBy`); los de integración usan `MockWebServer`
 * con sockets reales.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Timeout(30)
class ConnectionManagerTest {
    private val servers = mutableListOf<WsTestServer>()

    private fun newServer(): WsTestServer =
        WsTestServer()
            .also {
                it.start()
                servers += it
            }

    @AfterEach
    fun tearDown() {
        servers.forEach { it.close() }
        servers.clear()
    }

    /** Canales sin heartbeat: el tiempo virtual gobierna sólo el backoff de B2. */
    private fun noHeartbeatChannelFactory() =
        ChannelFactory { transport, scope, onDead ->
            JsonRpcChannel(
                transport = transport,
                scope = scope,
                heartbeatInterval = Duration.ZERO,
                onDead = onDead,
            )
        }

    /** Fábrica de [FakeTransport]: emite `gateway.ready` al crear (epoch `e-N` por generación). */
    private class FakeFactory : TransportFactory {
        val attempts = AtomicInteger(0)
        val created = CopyOnWriteArrayList<FakeTransport>()

        /** Si no es null, cada `connect` lanza esto (simula servidor caído). */
        @Volatile
        var failWith: Throwable? = null

        /** `false` → el socket abre pero nunca llega `gateway.ready`. */
        @Volatile
        var emitReady: Boolean = true

        /** Frame de ready por generación (1 = primera). */
        var readyFrameFor: (Int) -> String = { generation -> readyFrame("e-$generation") }

        override suspend fun connect(params: ConnectParams): Transport {
            attempts.incrementAndGet()
            failWith?.let { throw it }
            val transport = FakeTransport()
            created += transport
            if (emitReady) {
                transport.emit(readyFrameFor(created.size))
            }
            return transport
        }
    }

    private fun TestScope.manager(
        factory: TransportFactory,
        config: ReconnectConfig = ReconnectConfig(jitterFraction = 0.0),
        onBeforeConnect: suspend () -> ConnectParams = {
            ConnectParams("wss://hermes.example.invalid/api/ws?ticket=t")
        },
    ): ConnectionManager =
        ConnectionManager(
            scope = backgroundScope,
            transportFactory = factory,
            onBeforeConnect = onBeforeConnect,
            config = config,
            channelFactory = noHeartbeatChannelFactory(),
        )

    private fun TestScope.collectEvents(manager: ConnectionManager): MutableList<ConnectionEvent> {
        val events = mutableListOf<ConnectionEvent>()
        backgroundScope.launch { manager.events.collect { events += it } }
        return events
    }

    @Test
    fun `conexion inicial pasa de Connecting a Connected con replayEpoch`() =
        runTest {
            val factory = FakeFactory()
            val manager = manager(factory)

            manager.connect()
            runCurrent()

            val connected = assertIs<ConnectionState.Connected>(manager.state.value)
            assertEquals("e-1", connected.replayEpoch)
            assertFalse(connected.channel.isClosed)
        }

    @Test
    fun `muerte del socket produce Reconnecting con backoff y luego Connected`() =
        runTest {
            val factory = FakeFactory()
            val manager = manager(factory)
            manager.connect()
            runCurrent()
            assertIs<ConnectionState.Connected>(manager.state.value)

            factory.created[0].failIncoming(IOException("socket reset"))
            runCurrent()

            val retry = assertIs<ConnectionState.Reconnecting>(manager.state.value)
            assertEquals(1, retry.attempt)
            assertEquals(1.seconds, retry.retryIn)
            assertEquals(1, factory.created.size)

            advanceTimeBy(999)
            runCurrent()
            assertEquals(1, factory.created.size, "el backoff aún no venció: no hay socket nuevo")

            advanceTimeBy(2)
            runCurrent()
            assertEquals(2, factory.created.size)
            val connected = assertIs<ConnectionState.Connected>(manager.state.value)
            assertEquals("e-2", connected.replayEpoch)
        }

    @Test
    fun `backoff exponencial con intentos fallidos es 1s 2s 4s 8s`() =
        runTest {
            val factory = FakeFactory()
            factory.failWith = IOException("connection refused")
            val manager = manager(factory)

            manager.connect()
            runCurrent()

            val expected = listOf(1.seconds, 2.seconds, 4.seconds, 8.seconds)
            expected.forEachIndexed { index, wait ->
                val retry = assertIs<ConnectionState.Reconnecting>(manager.state.value)
                assertEquals(index + 1, retry.attempt)
                assertEquals(wait, retry.retryIn)
                assertIs<IOException>(retry.lastError)
                advanceTimeBy(wait.inWholeMilliseconds)
                runCurrent()
            }
            // Intento inicial + uno tras cada espera agotada.
            assertEquals(expected.size + 1, factory.attempts.get())
        }

    @Test
    fun `onBeforeConnect se invoca en cada intento y produce un ticket nuevo`() =
        runTest {
            val factory = FakeFactory()
            factory.failWith = IOException("down")
            val tickets = CopyOnWriteArrayList<String>()
            val manager =
                manager(factory) {
                    val ticket = "t-${tickets.size + 1}"
                    tickets += ticket
                    ConnectParams("wss://hermes.example.invalid/api/ws?ticket=$ticket")
                }

            manager.connect()
            runCurrent()
            assertEquals(1, tickets.size)

            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(2, tickets.size)

            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(listOf("t-1", "t-2", "t-3"), tickets.toList())
        }

    @Test
    fun `cierre explicito con disconnect NO reconecta`() =
        runTest {
            val factory = FakeFactory()
            val manager = manager(factory)
            manager.connect()
            runCurrent()
            assertIs<ConnectionState.Connected>(manager.state.value)

            manager.disconnect()
            runCurrent()

            assertEquals(ConnectionState.Disconnected, manager.state.value)
            assertTrue(factory.created[0].closed, "el transport quedó cerrado")
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(1, factory.created.size, "disconnect no debe provocar reconexión")
        }

    @Test
    fun `disconnect en mitad del backoff tambien detiene el bucle`() =
        runTest {
            val factory = FakeFactory()
            factory.failWith = IOException("down")
            val manager = manager(factory)
            manager.connect()
            runCurrent()
            assertIs<ConnectionState.Reconnecting>(manager.state.value)

            manager.disconnect()
            advanceTimeBy(60_000)
            runCurrent()

            assertEquals(ConnectionState.Disconnected, manager.state.value)
            assertEquals(1, factory.attempts.get(), "no hubo más intentos tras disconnect")
        }

    @Test
    fun `nunca hay dos sockets abiertos a la vez`() =
        runTest {
            val factory = FakeFactory()
            val manager = manager(factory)
            manager.connect()
            runCurrent()

            repeat(3) { index ->
                assertIs<ConnectionState.Connected>(manager.state.value)
                factory.created[index].failIncoming(IOException("caída"))
                runCurrent()
                assertIs<ConnectionState.Reconnecting>(manager.state.value)
                advanceTimeBy(1_000)
                runCurrent()
            }

            assertEquals(4, factory.created.size)
            // Cada generación murió antes de que la siguiente abriera su socket.
            assertTrue(factory.created.dropLast(1).all { it.closed })
            assertFalse(factory.created.last().closed)
        }

    @Test
    fun `Reconnected lleva el replayEpoch del ready de cada generacion`() =
        runTest {
            val factory = FakeFactory()
            val manager = manager(factory)
            val events = collectEvents(manager)
            manager.connect()
            runCurrent()

            assertIs<ConnectionState.Connected>(manager.state.value)
            assertTrue(events.isEmpty(), "la primera conexión no emite Reconnected")

            factory.created[0].failIncoming(IOException("corte"))
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(listOf<ConnectionEvent>(ConnectionEvent.Reconnected("e-2")), events)

            factory.created[1].failIncoming(IOException("otro corte"))
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(
                listOf<ConnectionEvent>(
                    ConnectionEvent.Reconnected("e-2"),
                    ConnectionEvent.Reconnected("e-3"),
                ),
                events,
            )
        }

    @Test
    fun `ready sin replay_epoch produce Connected y Reconnected con null`() =
        runTest {
            val factory = FakeFactory()
            factory.readyFrameFor = { readyFrame(null) }
            val manager = manager(factory)
            val events = collectEvents(manager)
            manager.connect()
            runCurrent()

            val connected = assertIs<ConnectionState.Connected>(manager.state.value)
            assertEquals(null, connected.replayEpoch)

            factory.created[0].failIncoming(IOException("corte"))
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(listOf<ConnectionEvent>(ConnectionEvent.Reconnected(null)), events)
        }

    @Test
    fun `socket que abre pero no envia gateway-ready se reintenta tras readyTimeout`() =
        runTest {
            val factory = FakeFactory()
            factory.emitReady = false
            val manager =
                manager(
                    factory,
                    config = ReconnectConfig(jitterFraction = 0.0, readyTimeout = 5.seconds),
                )
            manager.connect()
            runCurrent()

            assertEquals(1, factory.created.size)
            // Socket abierto pero sin ready: todavía NO es Connected (§2.4).
            assertIs<ConnectionState.Connecting>(manager.state.value)

            advanceTimeBy(5_000)
            runCurrent()

            val retry = assertIs<ConnectionState.Reconnecting>(manager.state.value)
            assertEquals(1, retry.attempt)
            assertTrue(factory.created[0].closed, "el socket sin ready se cerró")
        }

    @Test
    fun `onBeforeConnect que lanza se trata como fallo reintentable`() =
        runTest {
            val factory = FakeFactory()
            var calls = 0
            val manager =
                manager(factory) {
                    calls++
                    throw IOException("ticket mint falló")
                }
            manager.connect()
            runCurrent()

            assertEquals(1, calls)
            assertEquals(0, factory.attempts.get(), "nunca llegó a abrir socket")
            assertIs<ConnectionState.Reconnecting>(manager.state.value)

            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(2, calls)
        }

    @Test
    fun `attemptTimeout cubre un factory-connect que nunca vuelve`() =
        runTest {
            val hung = TransportFactory { CompletableDeferred<Transport>().await() }
            val manager =
                manager(
                    hung,
                    config = ReconnectConfig(jitterFraction = 0.0, attemptTimeout = 3.seconds),
                )
            manager.connect()
            runCurrent()
            assertIs<ConnectionState.Connecting>(manager.state.value)

            advanceTimeBy(3_000)
            runCurrent()
            assertIs<ConnectionState.Reconnecting>(manager.state.value)
        }

    @Test
    fun `cerrar el canal desde fuera provoca reconexion`() =
        runTest {
            val factory = FakeFactory()
            val manager = manager(factory)
            manager.connect()
            runCurrent()
            val connected = assertIs<ConnectionState.Connected>(manager.state.value)

            // Un consumidor cierra el canal directamente (p. ej. GatewayClient.close
            // de B4): el flujo entrante termina y el manager reconecta — nunca un
            // `Connected` eterno sobre un socket muerto (revisión media #1).
            connected.channel.close()
            runCurrent()

            val retry = assertIs<ConnectionState.Reconnecting>(manager.state.value)
            assertEquals(1, retry.attempt)
            assertIs<ChannelClosedException>(retry.lastError)

            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(2, factory.created.size)
            assertIs<ConnectionState.Connected>(manager.state.value)
        }

    @Test
    fun `onBeforeConnect fatal corta el bucle en Failed y permite reintentar`() =
        runTest {
            val factory = FakeFactory()
            var calls = 0
            val manager =
                manager(factory) {
                    calls++
                    if (calls == 1) {
                        // B3 lanzará esto cuando el gateway rechace las credenciales.
                        throw ConnectionFatalException("ticket rechazado")
                    }
                    ConnectParams("wss://hermes.example.invalid/api/ws?ticket=t-$calls")
                }
            manager.connect()
            runCurrent()

            val failed = assertIs<ConnectionState.Failed>(manager.state.value)
            assertIs<ConnectionFatalException>(failed.cause)
            assertEquals(1, calls)
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(1, calls, "un fallo fatal no se reintenta")

            // connect() arranca un bucle nuevo tras Failed (credenciales nuevas, B3).
            manager.connect()
            runCurrent()
            assertEquals(2, calls)
            assertIs<ConnectionState.Connected>(manager.state.value)
        }

    @Test
    fun `disconnect y connect rearranca el bucle`() =
        runTest {
            val factory = FakeFactory()
            val manager = manager(factory)
            manager.connect()
            runCurrent()
            assertIs<ConnectionState.Connected>(manager.state.value)

            manager.disconnect()
            manager.connect()
            runCurrent()

            assertEquals(2, factory.created.size)
            assertIs<ConnectionState.Connected>(manager.state.value)
        }

    @Test
    fun `connect es idempotente un solo bucle`() =
        runTest {
            val factory = FakeFactory()
            val manager = manager(factory)
            manager.connect()
            manager.connect()
            runCurrent()

            assertEquals(1, factory.created.size)
        }

    @Test
    fun `el channel del estado Connected responde llamadas`() =
        runTest {
            val factory = FakeFactory()
            val manager = manager(factory)
            manager.connect()
            runCurrent()

            val connected = assertIs<ConnectionState.Connected>(manager.state.value)
            val call = async { connected.channel.call(RpcMethods.SESSION_LIST) }
            runCurrent()

            val sent = factory.created[0].sentFrames().single()
            val id = sent.getValue("id").jsonPrimitive.long
            factory.created[0].emit("""{"id":$id,"result":{"sessions":[{"id":"s-1"}]}}""")

            val sessions = call.await().jsonObject.getValue("sessions")
            assertTrue(sessions.toString().contains("s-1"))
        }

    // ── Integración con sockets reales (MockWebServer + WebSocketListener) ──
    //
    // OJO con el tiempo: el scheduler de `runTest` adelanta el reloj virtual
    // hasta el próximo `delay` encolado en cuanto el cuerpo suspende — un
    // manager sobre `backgroundScope` dispararía `attemptTimeout` y el backoff
    // al instante durante IO real. Por eso estos tests inyectan un scope con
    // dispatcher real (mismo Job que `backgroundScope`: muere con el test) y
    // esperan con [awaitReal], que corre el `withTimeout` en tiempo real.

    /** Scope con dispatcher real (delays reales) atado al ciclo del test. */
    @Suppress("InjectDispatcher")
    private fun TestScope.realScope(): CoroutineScope =
        CoroutineScope(backgroundScope.coroutineContext + Dispatchers.Default)

    /** Espera real sobre IO de verdad (sockets); el timeout es de reloj, no virtual. */
    @Suppress("InjectDispatcher")
    private suspend fun <T> awaitReal(
        timeoutMillis: Long = REAL_WAIT_MS,
        block: suspend CoroutineScope.() -> T,
    ): T = withContext(Dispatchers.IO) { withTimeout(timeoutMillis) { block() } }

    /** [TransportFactory] sobre sockets reales que registra cada transport creado. */
    private fun realFactory(
        srv: WsTestServer,
        live: MutableList<WebSocketTransport>,
        attempts: AtomicInteger,
    ): TransportFactory =
        TransportFactory { params ->
            attempts.incrementAndGet()
            val transport = WebSocketTransport(params.url, okHttpClient = srv.client)
            live += transport
            try {
                transport.awaitOpen()
            } catch (e: Throwable) {
                transport.close()
                throw e
            }
            transport
        }

    @Test
    fun `cierre real del servidor provoca reconexion real con un solo socket vivo`() =
        runTest {
            val srv = newServer()
            srv.onSocketOpen = { ws -> ws.send(readyFrame("e-${srv.serverSockets.size}")) }

            val live = CopyOnWriteArrayList<WebSocketTransport>()
            val attempts = AtomicInteger(0)
            var tickets = 0
            val manager =
                ConnectionManager(
                    scope = realScope(),
                    transportFactory = realFactory(srv, live, attempts),
                    onBeforeConnect = {
                        tickets++
                        ConnectParams(srv.wsUrl("/api/ws", "ticket=t-$tickets"))
                    },
                    config =
                        ReconnectConfig(
                            initialDelay = 50.milliseconds,
                            maxDelay = 200.milliseconds,
                            jitterFraction = 0.0,
                        ),
                    channelFactory = noHeartbeatChannelFactory(),
                )
            val events = collectEvents(manager)
            manager.connect()

            awaitReal { manager.state.first { it is ConnectionState.Connected } }
            assertEquals(1, srv.serverSockets.size)

            // Suscripciones activas antes del corte para no perder transiciones
            // (StateFlow está conflado: un colector tardío sólo ve lo último).
            val sawReconnecting =
                backgroundScope.async {
                    manager.state.first { it is ConnectionState.Reconnecting }
                }
            val sawReconnected =
                backgroundScope.async {
                    manager.state.first {
                        it is ConnectionState.Connected && it.replayEpoch == "e-2"
                    }
                }
            runCurrent()

            // El servidor cierra el socket: el cliente reconecta solo (backoff real).
            srv.awaitSocket().close(NORMAL_CLOSURE, "bye")
            awaitReal { sawReconnecting.await() }
            srv.awaitSocket() // el segundo socket fue aceptado por el servidor
            awaitReal { sawReconnected.await() }

            assertEquals(2, srv.serverSockets.size)
            assertEquals(1, live.count { it.isOpen }, "nunca dos sockets abiertos")
            assertFalse(live[0].isOpen, "el socket viejo quedó cerrado")
            runCurrent()
            assertEquals(listOf<ConnectionEvent>(ConnectionEvent.Reconnected("e-2")), events)
            // Ticket nuevo por intento, verificado en el servidor (§2.1 paso 3).
            assertEquals(listOf("t-1", "t-2"), srv.tickets.toList())
        }

    @Test
    fun `sobrevive a un corte largo y vuelve solo con un solo socket`() =
        runTest {
            val srv = newServer()
            srv.onSocketOpen = { ws -> ws.send(readyFrame("e-${srv.serverSockets.size}")) }

            val live = CopyOnWriteArrayList<WebSocketTransport>()
            val attempts = AtomicInteger(0)
            val manager =
                ConnectionManager(
                    scope = realScope(),
                    transportFactory = realFactory(srv, live, attempts),
                    onBeforeConnect = {
                        ConnectParams(srv.wsUrl("/api/ws", "ticket=t-${attempts.get() + 1}"))
                    },
                    config =
                        ReconnectConfig(
                            initialDelay = 50.milliseconds,
                            maxDelay = 100.milliseconds,
                            jitterFraction = 0.0,
                        ),
                    channelFactory = noHeartbeatChannelFactory(),
                )
            val events = collectEvents(manager)
            manager.connect()
            awaitReal { manager.state.first { it is ConnectionState.Connected } }

            // "Corte": el socket muere y el servidor rechaza upgrades (HTTP 503).
            srv.acceptUpgrades = false
            srv.awaitSocket().close(NORMAL_CLOSURE, "bye")

            // El corte dura muchos ciclos de backoff (~1 s real aquí; la
            // aceptación de B2 habla de 60 s — la semántica es la misma:
            // reintentos indefinidos con backoff acotado hasta que la red vuelve).
            awaitReal {
                while (attempts.get() < OUTAGE_MIN_ATTEMPTS) {
                    delay(OUTAGE_POLL_MS)
                }
            }
            assertEquals(0, live.count { it.isOpen }, "ningún socket quedó abierto")

            // La red "vuelve": el siguiente reintento conecta y emite Reconnected.
            srv.acceptUpgrades = true
            awaitReal {
                manager.state.first { it is ConnectionState.Connected && it.replayEpoch == "e-2" }
            }

            assertEquals(1, live.count { it.isOpen })
            runCurrent()
            assertTrue(events.contains(ConnectionEvent.Reconnected("e-2")))
            assertEquals(srv.tickets.size, srv.tickets.toSet().size, "ticket único por intento")
        }

    private companion object {
        const val NORMAL_CLOSURE = 1000
        const val REAL_WAIT_MS = 10_000L
        const val OUTAGE_MIN_ATTEMPTS = 4
        const val OUTAGE_POLL_MS = 25L
    }
}
