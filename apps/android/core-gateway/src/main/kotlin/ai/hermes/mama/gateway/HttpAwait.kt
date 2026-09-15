package ai.hermes.mama.gateway

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/**
 * `Call.execute()` versión corrutina (tarea B3): `enqueue` + continuación
 * cancelable. Si el caller se cancela, el `Call` de OkHttp se cancela también —
 * nunca queda una petición de auth huérfana en vuelo. Si la cancelación llega
 * con la respuesta ya recibida, ésta se cierra aquí (sin fugas de conexión).
 */
internal suspend fun Call.await(): Response =
    suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    if (cont.isActive) {
                        cont.resumeWithException(e)
                    }
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    // Miembro de CancellableContinuation: si la corrutina ya estaba
                    // cancelada, el handler cierra la respuesta (sin fuga de conexión).
                    cont.resume(response) { response.close() }
                }
            },
        )
    }

/** Cliente HTTP por defecto de [BasicAuthSession] cuando el caller no pasa uno. */
internal fun defaultAuthHttpClient(): OkHttpClient =
    OkHttpClient
        .Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

private const val CONNECT_TIMEOUT_SECONDS = 10L
private const val READ_TIMEOUT_SECONDS = 15L
private const val WRITE_TIMEOUT_SECONDS = 15L
