package ai.hermes.mama.feature.chat.conversation

import ai.hermes.mama.core.storage.MamaDatabase
import ai.hermes.mama.core.storage.SessionGateway
import ai.hermes.mama.core.storage.SessionRepository
import ai.hermes.mama.gateway.ConnectParams
import ai.hermes.mama.gateway.ConnectionManager
import ai.hermes.mama.gateway.ConnectionState
import ai.hermes.mama.gateway.GatewayClient
import ai.hermes.mama.testing.FakeGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

/*
 * Soporte de los tests de [ChatViewModel] (C4): mismo cableado que `DevChatHost`
 * — `ConnectionManager` + WebSocket reales contra el FakeGateway en loopback,
 * `SessionRepository` sobre Room in-memory (Robolectric). Tiempo REAL: el
 * `TurnRunner` del fake duerme de verdad y Room escribe en su propio executor.
 */

/** Una [ChatGeneration] por canal `Connected` — la misma fusión que el host dev. */
internal fun chatGenerations(
    manager: ConnectionManager,
    db: MamaDatabase,
    scope: CoroutineScope,
): Flow<ChatGeneration> =
    manager.state
        .map { state -> (state as? ConnectionState.Connected)?.channel }
        .distinctUntilChanged()
        .filterNotNull()
        .map { channel ->
            val client = GatewayClient(channel = channel, scope = scope)
            ChatGeneration(
                repository =
                    SessionRepository(
                        gateway = SessionGateway.from(client),
                        db = db,
                        scope = scope,
                    ),
                client = client,
            )
        }

/** `ConnectionManager` producción contra el FakeGateway (sin ticket: modo dev del fake). */
internal fun CoroutineScope.newManager(gateway: FakeGateway): ConnectionManager =
    ConnectionManager(
        scope = this,
        client = OkHttpClient(),
        onBeforeConnect = { ConnectParams(url = gateway.wsUrl) },
    ).also { it.connect() }

/**
 * Mantiene vivos los `StateFlow` `WhileSubscribed` del VM en el test — sin
 * colector, `.value` se queda en el inicial.
 */
internal fun ChatViewModel.watchIn(scope: CoroutineScope) {
    scope.launch { items.collect {} }
    scope.launch { liveText.collect {} }
    scope.launch { liveStreaming.collect {} }
    scope.launch { header.collect {} }
    scope.launch { activity.collect {} }
}

/**
 * Sondea [probe] hasta no-null o agotar el tiempo — reloj REAL (los eventos
 * del fake y Room no corren en el scheduler virtual).
 */
internal suspend fun <T> eventually(
    timeoutMs: Long = EVENTUALLY_TIMEOUT_MS,
    probe: () -> T?,
): T =
    withTimeout(timeoutMs) {
        while (true) {
            probe()?.let { return@withTimeout it }
            withContext(Dispatchers.Default) { delay(25) }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

/** Nombres de método JSON-RPC recibidos por el fake (en orden de llegada). */
internal fun FakeGateway.receivedMethods(): List<String> =
    receivedCalls.map { call -> call["method"]?.jsonPrimitive?.content.orEmpty() }

/** `true` cuando el método llegó al fake al menos una vez. */
internal fun FakeGateway.received(method: String): Boolean = receivedMethods().contains(method)

internal const val EVENTUALLY_TIMEOUT_MS = 10_000L
