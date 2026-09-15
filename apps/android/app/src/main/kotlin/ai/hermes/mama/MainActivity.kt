package ai.hermes.mama

import ai.hermes.mama.core.ui.theme.MamaTheme
import ai.hermes.mama.feature.settings.BasicAuthConnectionVerifier
import ai.hermes.mama.feature.settings.ConnectionScreen
import ai.hermes.mama.feature.settings.ConnectionViewModel
import ai.hermes.mama.feature.settings.DataStoreConnectionSettings
import ai.hermes.mama.feature.settings.connectionUnlockGesture
import ai.hermes.mama.gateway.SecureStore
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import timber.log.Timber

/**
 * Única Activity de la app (single-Activity + Compose, ROADMAP §0).
 *
 * Wiring de C2: sin credenciales guardadas la app abre en la pantalla
 * **Conexión**; con ellas va al placeholder de Chats (el Main real llega con
 * C3+). En `mama` la Conexión también se abre con la pulsación larga de 3 s
 * sobre el logo ([connectionUnlockGesture]) — aquí el placeholder lo ejerce;
 * C8 lo trasladará al logo real de Chats cuando exista la navegación.
 *
 * En `dev` con `fake_script` (DevGateway) no hace falta Conexión: el endpoint
 * del FakeGateway sustituye a las credenciales (runbook B5).
 */
class MainActivity : ComponentActivity() {
    // Dispatcher como propiedad (InjectDispatcher): el scope vive lo que viva
    // la activity; los tests lo podrían reemplazar por un TestDispatcher.
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
    private val appScope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private lateinit var secureStore: SecureStore
    private lateinit var settings: DataStoreConnectionSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DevGateway.onNewIntent(intent)
        // Un solo SecureStore cifrado compartido por ajustes (credenciales C2)
        // y la BasicAuthSession del verifier (cookies B3) — vive en la
        // Application para que C8 lo reutilice en la sesión real.
        secureStore = (application as HermesMamaApp).secureStore
        settings = DataStoreConnectionSettings(applicationContext, appScope, secureStore)
        enableEdgeToEdge()
        setContent {
            MamaTheme {
                // null = comprobando credenciales; true = pantalla Conexión.
                var showConnection by remember { mutableStateOf<Boolean?>(null) }
                LaunchedEffect(Unit) {
                    showConnection =
                        DevGateway.endpointOverride == null && !settings.hasCredentials()
                }
                when (showConnection) {
                    null -> ChatsPlaceholder(onUnlock = {})
                    true ->
                        ConnectionEntry(
                            onDone = {
                                // "Guardar y empezar" OK: a Chats (C3+ pondrá la real).
                                showConnection = false
                            },
                        )
                    false -> ChatsPlaceholder(onUnlock = { showConnection = true })
                }
            }
        }
    }

    // Si en el futuro la activity pasa a singleTask/singleTop, el arranque
    // llega por aquí: releyendo el intent no se pierde el extra fake_script.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        DevGateway.onNewIntent(intent)
    }

    /**
     * La pantalla Conexión con su ViewModel real: verifier `BasicAuthSession`
     * sobre el SecureStore compartido. `allowCleartext` sólo en flavor `dev`
     * (§8: en `mama` una base `http://` fuera de loopback se rechaza sola).
     */
    @Composable
    private fun ConnectionEntry(onDone: () -> Unit) {
        val vm: ConnectionViewModel =
            viewModel(
                factory =
                    viewModelFactory {
                        initializer {
                            ConnectionViewModel(
                                settings = settings,
                                verifier =
                                    BasicAuthConnectionVerifier(
                                        secureStore = secureStore,
                                        allowCleartext = BuildConfig.FAKE_GATEWAY_ENDPOINT.isNotEmpty(),
                                        logger = { Timber.d(it) },
                                    ),
                            )
                        }
                    },
            )
        ConnectionScreen(viewModel = vm, onNavigateToChats = onDone)
    }
}

/**
 * Placeholder de Chats hasta C3+: logo + nombre de la app. El logo lleva el
 * gesto de desbloqueo de C2 (pulsación larga 3 s → ajustes de conexión) para
 * que el acceso oculto del flavor `mama` ya sea real.
 */
@Composable
private fun ChatsPlaceholder(onUnlock: () -> Unit) {
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(64.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .connectionUnlockGesture(onUnlock = onUnlock),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "H",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HermesPreview() {
    MamaTheme {
        Text(text = stringResource(R.string.app_name))
    }
}
