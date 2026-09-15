package ai.hermes.mama.core.storage

import ai.hermes.mama.contract.SessionCreateResult
import ai.hermes.mama.contract.SessionDeleteResult
import ai.hermes.mama.contract.SessionHistoryResult
import ai.hermes.mama.contract.SessionListResult
import ai.hermes.mama.contract.SessionResumeResult
import ai.hermes.mama.contract.SessionTitleResult
import ai.hermes.mama.gateway.GatewayClient
import ai.hermes.mama.gateway.GatewayEvent
import ai.hermes.mama.gateway.createSession
import ai.hermes.mama.gateway.deleteSession
import ai.hermes.mama.gateway.listSessions
import ai.hermes.mama.gateway.renameSession
import ai.hermes.mama.gateway.resumeSession
import ai.hermes.mama.gateway.sessionHistory
import kotlinx.coroutines.flow.Flow

/**
 * Lo que [SessionRepository] necesita del gateway (ROADMAP §2.3/§2.4, B6).
 *
 * Es una interfaz y no el [GatewayClient] concreto para que los tests JVM
 * falseen la red por completo (FakeSessionGateway): el repositorio jamás ve un
 * socket. La implementación real es [SessionGateway.from], un adaptador fino
 * sobre las extensiones `GatewayClient*`Rpc de B4 — ningún string de método ni
 * serialización vive aquí.
 *
 * Identificadores (§2.3): [resumeSession] y [deleteSession] reciben el *stored*
 * id; [sessionHistory] y [renameSession] el *runtime* id (ambos son
 * session-scoped en el backend: un stored id daría `4001 session not found`).
 * De esa traducción se encarga el repositorio.
 */
interface SessionGateway {
    /**
     * Todos los eventos del canal (§2.4), broadcasts incluidos
     * (`session_id == null`: `sessions.changed`, `gateway.ready`…).
     *
     * OJO: el `SharedFlow` subyacente tiene `replay = 0` — el repositorio se
     * suscribe al construirse (`UNDISPATCHED`) o los eventos se pierden.
     */
    val events: Flow<GatewayEvent>

    /** `session.list` — chats existentes por *stored* id, más reciente primero. */
    suspend fun listSessions(): SessionListResult

    /** `session.resume` — abre un chat por *stored* id; devuelve el *runtime* `session_id`. */
    suspend fun resumeSession(storedSessionId: String): SessionResumeResult

    /** `session.history` — transcript completo de una sesión viva (*runtime* id). */
    suspend fun sessionHistory(runtimeSessionId: String): SessionHistoryResult

    /** `session.create` — chat nuevo (`source:"android"` lo fija el client); devuelve ambos ids. */
    suspend fun createSession(title: String?): SessionCreateResult

    /** `session.delete` — borrado remoto por *stored* id (el backend lo rechaza si la sesión está viva). */
    suspend fun deleteSession(storedSessionId: String): SessionDeleteResult

    /** `session.title` — renombrar por *runtime* id; el result trae el título aplicado. */
    suspend fun renameSession(
        runtimeSessionId: String,
        title: String,
    ): SessionTitleResult

    companion object {
        /** Adaptador sobre [GatewayClient] (B4): la implementación de producción. */
        fun from(client: GatewayClient): SessionGateway = GatewayClientSessionGateway(client)
    }
}

private class GatewayClientSessionGateway(
    private val client: GatewayClient,
) : SessionGateway {
    override val events: Flow<GatewayEvent>
        get() = client.events

    override suspend fun listSessions(): SessionListResult = client.listSessions()

    override suspend fun resumeSession(storedSessionId: String): SessionResumeResult =
        client.resumeSession(storedSessionId)

    override suspend fun sessionHistory(runtimeSessionId: String): SessionHistoryResult =
        client.sessionHistory(runtimeSessionId)

    override suspend fun createSession(title: String?): SessionCreateResult = client.createSession(title)

    override suspend fun deleteSession(storedSessionId: String): SessionDeleteResult =
        client.deleteSession(storedSessionId)

    override suspend fun renameSession(
        runtimeSessionId: String,
        title: String,
    ): SessionTitleResult = client.renameSession(runtimeSessionId, title)
}
