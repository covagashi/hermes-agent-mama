package ai.hermes.mama.feature.settings

import ai.hermes.mama.gateway.BasicAuthSession
import ai.hermes.mama.gateway.SecureStore
import ai.hermes.mama.gateway.SessionIdentity
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/**
 * Prueba del flujo §2.1 para la pantalla Conexión (C2): `login` + `me`.
 *
 * Interfaz funcional para que `ConnectionViewModel` sea testeable en JVM con un
 * fake que devuelve identidad o lanza `AuthException`/`IOException`; la
 * implementación real es [BasicAuthConnectionVerifier].
 *
 * Contrato: devuelve la [SessionIdentity] de `GET /api/auth/me` si las
 * credenciales sirven; lanza el error real (AuthException tipada, IOException
 * de transporte) si no — el ViewModel lo traduce a mensaje humano.
 */
fun interface ConnectionVerifier {
    suspend fun verify(
        baseUrl: HttpUrl,
        username: String,
        password: String,
    ): SessionIdentity
}

/**
 * [ConnectionVerifier] sobre la [BasicAuthSession] real de B3.
 *
 * Cada `verify` crea una sesión NUEVA sobre el mismo [secureStore]: el jar
 * compartido queda con las cookies del último login OK, así tras "Guardar" la
 * sesión de la app (C8) ya arranca autenticada sin re-login. Las credenciales
 * guardadas que C8 reinyecte con `setCredentials` cubren el re-login de §2.1.5.
 *
 * [allowCleartext] viene del flavor (`true` sólo en `dev`): en `mama` una base
 * `http://` fuera de loopback la rechaza la propia sesión con
 * `AuthException.CleartextForbidden` — la contraseña nunca viaja en claro.
 */
class BasicAuthConnectionVerifier(
    private val secureStore: SecureStore? = null,
    private val allowCleartext: Boolean = false,
    private val client: OkHttpClient? = null,
    private val logger: (String) -> Unit = {},
) : ConnectionVerifier {
    override suspend fun verify(
        baseUrl: HttpUrl,
        username: String,
        password: String,
    ): SessionIdentity {
        val session =
            BasicAuthSession(
                baseUrl = baseUrl,
                secureStore = secureStore,
                client = client,
                allowCleartext = allowCleartext,
                logger = logger,
            )
        session.login(username, password)
        return session.me()
    }
}
