package ai.hermes.mama.feature.chat.chats

import ai.hermes.mama.contract.SessionListResult
import ai.hermes.mama.contract.SessionListRow
import ai.hermes.mama.core.storage.MamaDatabase
import ai.hermes.mama.core.storage.SessionRepository
import ai.hermes.mama.core.ui.theme.MamaTheme
import ai.hermes.mama.gateway.ConnectionState
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Demo de la pantalla Chats para capturas de emulador (ROADMAP §7.1 / C3):
 * monta la pantalla real sobre un [SessionRepository] con Room en memoria y un
 * [FakeSessionGateway] que sirve los 4 chats del mockup `Main.dc.html`.
 *
 * No toca red ni credenciales — todo queda dentro del proceso del APK de
 * tests. Se lanza con:
 *
 * ```
 * adb shell am start -n ai.hermes.mama.feature.chat.test/.chats.ChatsDemoActivity
 * ```
 *
 * La navegación real llega en C8: abrir/crear un chat enseña un Toast con la
 * pareja de ids que la señal de navegación entregaría al NavHost.
 */
class ChatsDemoActivity : ComponentActivity() {
    private lateinit var db: MamaDatabase
    private lateinit var repository: SessionRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        db =
            Room
                .inMemoryDatabaseBuilder(applicationContext, MamaDatabase::class.java)
                .build()
        repository =
            SessionRepository(
                gateway = demoGateway(),
                db = db,
                scope = lifecycleScope,
            )

        // "Conectando" es el estado neutro disponible sin canal vivo: la franja
        // de fallo no se pinta (Connected exige un JsonRpcChannel real).
        val connection = MutableStateFlow<ConnectionState>(ConnectionState.Connecting)
        val viewModel = ChatsViewModel(repository = repository, connectionState = connection)

        setContent {
            MamaTheme {
                ChatsScreen(
                    viewModel = viewModel,
                    onOpenChat = { opened ->
                        Toast
                            .makeText(
                                this,
                                "Abrir chat ${opened.storedId} → ${opened.runtimeId}",
                                Toast.LENGTH_SHORT,
                            ).show()
                    },
                )
            }
        }
    }

    override fun onDestroy() {
        repository.close()
        db.close()
        super.onDestroy()
    }

    /**
     * El guion `chats_demo` en proceso: los 4 chats del mockup con horas
     * escalonadas para que se vean todas las etiquetas (hora, Ayer, día, fecha).
     */
    private fun demoGateway(): FakeSessionGateway {
        val nowSeconds = System.currentTimeMillis() / MILLIS_PER_SECOND
        return FakeSessionGateway().apply {
            onListSessions = {
                SessionListResult(
                    sessions =
                        listOf(
                            SessionListRow(
                                id = "demo_factura",
                                title = "Factura de la lavadora",
                                preview = "He guardado la factura en Descargas",
                                startedAt = nowSeconds - HOUR_SECONDS,
                                messageCount = 6,
                            ),
                            SessionListRow(
                                id = "demo_correo",
                                title = "Correo",
                                preview = "Tienes 2 correos nuevos de la farmacia",
                                startedAt = nowSeconds - DAY_SECONDS,
                                messageCount = 3,
                            ),
                            SessionListRow(
                                id = "demo_recetas",
                                title = "Recetas",
                                preview = "Lentejas con verduras: 40 minutos",
                                startedAt = nowSeconds - 3 * DAY_SECONDS,
                                messageCount = 12,
                            ),
                            SessionListRow(
                                id = "demo_medico",
                                title = "Cita del médico",
                                preview = "Miércoles 24 a las 9:30, lleva la tarjeta",
                                startedAt = nowSeconds - 10 * DAY_SECONDS,
                                messageCount = 4,
                            ),
                        ),
                )
            }
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000.0
        const val HOUR_SECONDS = 3_600.0
        const val DAY_SECONDS = 86_400.0
    }
}
