package ai.hermes.mama

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview

/**
 * Única Activity de la app (single-Activity + Compose, ROADMAP §0).
 *
 * En A1 sólo muestra el nombre de la app; las pantallas llegan en los hitos M2+.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DevGateway.onNewIntent(intent)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineLarge,
                        )
                    }
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
}

@Preview(showBackground = true)
@Composable
private fun HermesPreview() {
    MaterialTheme {
        Text(text = stringResource(R.string.app_name))
    }
}
