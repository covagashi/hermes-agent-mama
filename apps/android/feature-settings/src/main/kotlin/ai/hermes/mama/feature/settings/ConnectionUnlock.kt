package ai.hermes.mama.feature.settings

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Acceso oculto a los ajustes de conexión del flavor `mama` (ROADMAP §5/C2):
 * **mantener pulsado el logo 3 segundos** en la pantalla Chats abre Conexión.
 *
 * Uso (C8 lo aplica sobre el logo de la pantalla Chats):
 *
 * ```kotlin
 * Image(logo, Modifier.connectionUnlockGesture(onUnlock = { nav.navigate("conexion") }))
 * ```
 *
 * Por qué 3 s y no el long-press estándar: el gesto habitual (~500 ms) salta
 * con cualquier pulsación un poco larga — para una usuaria mayor eso sería un
 * ajuste "fantasma". 3 s es deliberado y difícil de disparar por accidente.
 *
 * Accesibilidad: se registra además como `customActions` de TalkBack con la
 * etiqueta "Abrir ajustes de conexión" — el gesto no es un secreto inaccesible.
 */
fun Modifier.connectionUnlockGesture(onUnlock: () -> Unit): Modifier =
    composed {
        val actionLabel = stringResource(R.string.conexion_unlock_action)
        this
            .semantics {
                customActions =
                    listOf(
                        CustomAccessibilityAction(label = actionLabel) {
                            onUnlock()
                            true
                        },
                    )
            }.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Espera el levantar con un límite de 3 s. `waitForUpOrCancellation`
                    // devuelve null tanto si el gesto se cancela como si expira el
                    // timeout: se distingue mirando el tiempo real transcurrido.
                    val upOrCancel =
                        withTimeoutOrNull(UNLOCK_HOLD_MS) {
                            waitForUpOrCancellation()
                        }
                    val heldLongEnough =
                        upOrCancel == null &&
                            SystemClock.uptimeMillis() - down.uptimeMillis >= UNLOCK_HOLD_MS
                    if (heldLongEnough) {
                        onUnlock()
                        // Se traga el levantar para que no llegue como click.
                        waitForUpOrCancellation()?.consume()
                    }
                }
            }
    }

/** Duración deliberada de la pulsación que abre los ajustes de conexión. */
const val UNLOCK_HOLD_MS = 3_000L
