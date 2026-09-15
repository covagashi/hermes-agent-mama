package ai.hermes.mama.feature.chat.clarify

import ai.hermes.mama.core.ui.theme.MamaDimens
import ai.hermes.mama.core.ui.theme.MamaTheme
import ai.hermes.mama.feature.chat.R
import ai.hermes.mama.feature.voice.SpeechInput
import android.content.res.Configuration
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Tarjeta modal de pregunta (C7, mockup Pregunta.dc.html): velo a pantalla
 * completa + hoja inferior con "Hermes pregunta", el progreso "N de M" cuando
 * la petición trae lote, la pregunta en grande y las entradas posibles:
 *
 * - `choices` → una fila grande por opción (un tap = respuesta);
 * - `multi_select` → las mismas filas en modo casilla + botón "Listo";
 * - siempre → la fila de texto libre con micrófono ("o responde con tu voz").
 *
 * No se puede descartar sin responder (la clarify quedaría colgando el
 * backend): el velo traga los toques y no hay gesto de cierre — la tarjeta se
 * va sola al responder, al acabar el lote o con `request.cancel`.
 *
 * [state] ya viene listo para pintar (ver [ClarifyController]); los taps suben
 * como [onAnswer] (etiqueta o texto libre) y [onAnswerMulti] (selección
 * múltiple). [speech] es el `SpeechInput` de feature-voice — `null` deja el
 * micrófono deshabilitado (tests JVM, móvil sin servicio). Las entradas viven
 * en ClarifyInputs.kt.
 */
@Composable
fun ClarifyOverlay(
    state: ClarifyCardState,
    onAnswer: (String) -> Unit,
    onAnswerMulti: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    speech: SpeechInput? = null,
) {
    Box(modifier = modifier.fillMaxSize()) {
        // Velo que oscurece el chat y traga los toques (modal real, sin gesto
        // de cierre: la pregunta quedaría colgando el backend). detectTapGestures
        // consume el tap sin añadir semántica de click (TalkBack lo ignora).
        Surface(
            modifier =
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures(onTap = {}) },
            color = scrimColor(),
        ) {}
        ClarifyCard(
            state = state,
            onAnswer = onAnswer,
            onAnswerMulti = onAnswerMulti,
            speech = speech,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** La hoja inferior en sí, reusable en previews y capturas. */
@Composable
fun ClarifyCard(
    state: ClarifyCardState,
    onAnswer: (String) -> Unit,
    onAnswerMulti: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    speech: SpeechInput? = null,
) {
    val dialogTitle = stringResource(R.string.clarify_title)
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics {
                    // paneTitle hace que TalkBack anuncie la hoja al aparecer.
                    paneTitle = dialogTitle
                    liveRegion = LiveRegionMode.Polite
                },
        shape =
            RoundedCornerShape(
                topStart = MamaDimens.SheetCorner,
                topEnd = MamaDimens.SheetCorner,
            ),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            // Scroll si el contenido supera la pantalla (fuente ×2, muchas
            // opciones): la usuaria siempre llega al campo de respuesta.
            modifier =
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HeaderRow(progress = state.progress)
            Text(
                text = state.question,
                style = MaterialTheme.typography.headlineSmall,
            )
            StatusContent(
                state = state,
                onAnswer = onAnswer,
                onAnswerMulti = onAnswerMulti,
                speech = speech,
            )
        }
    }
}

/** "Hermes pregunta" en verde + el "N de M" del lote a la derecha (mockup). */
@Composable
private fun HeaderRow(progress: ClarifyProgress?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.clarify_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (progress != null) {
            Text(
                text = stringResource(R.string.clarify_progress, progress.current, progress.total),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Inputs (pendiente/fallo) o fila de estado (respondida) según el ciclo de vida. */
@Composable
private fun StatusContent(
    state: ClarifyCardState,
    onAnswer: (String) -> Unit,
    onAnswerMulti: (List<String>) -> Unit,
    speech: SpeechInput?,
) {
    when (state.status) {
        ClarifyStatus.Answered ->
            StatusRow(
                icon = Icons.Outlined.Check,
                text = stringResource(R.string.clarify_answered),
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        else -> {
            if (state.status == ClarifyStatus.SendFailed) {
                StatusRow(
                    icon = Icons.Outlined.ErrorOutline,
                    text = stringResource(R.string.clarify_send_failed),
                    container = MaterialTheme.colorScheme.errorContainer,
                    content = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            // Reintento tras fallo: la respuesta no salió; los inputs siguen vivos.
            InputContent(
                state = state,
                onAnswer = onAnswer,
                onAnswerMulti = onAnswerMulti,
                speech = speech,
            )
        }
    }
}

/** Fila de estado respondida/error: sustituye a los inputs (nada clickable). */
@Composable
internal fun StatusRow(
    icon: ImageVector,
    text: String,
    container: Color,
    content: Color,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        shape = RoundedCornerShape(MamaDimens.CardCorner),
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier =
                Modifier
                    .heightIn(min = MamaDimens.MinTouchTarget)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(MamaDimens.IconSizeLarge),
            )
            Text(text = text, style = MaterialTheme.typography.titleSmall)
        }
    }
}

/** Velo del mockup: `rgba(31,27,22,0.45)` = onBackground al 45 % (mismo que C6). */
@Composable
internal fun scrimColor() = MaterialTheme.colorScheme.onBackground.copy(alpha = SCRIM_ALPHA)

internal const val SCRIM_ALPHA = 0.45f
internal const val PREVIEW_KEY = "preview"
internal val OPTION_BORDER_DP = 1.5.dp

@Preview(name = "Pregunta con opciones", showBackground = true, widthDp = 390)
@Preview(
    name = "Pregunta con opciones oscuro",
    showBackground = true,
    widthDp = 390,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ClarifyOverlayChoicesPreview() {
    MamaTheme {
        ClarifyOverlay(
            state =
                ClarifyCardState(
                    key = PREVIEW_KEY,
                    question = stringResource(R.string.clarify_example_question),
                    input =
                        ClarifyInput.Choices(
                            options =
                                listOf(
                                    ClarifyOption(
                                        display = stringResource(R.string.clarify_example_choice_1),
                                        wire = stringResource(R.string.clarify_example_choice_1),
                                        recommended = true,
                                    ),
                                    ClarifyOption(
                                        display = stringResource(R.string.clarify_example_choice_2),
                                        wire = stringResource(R.string.clarify_example_choice_2),
                                    ),
                                    ClarifyOption(
                                        display = stringResource(R.string.clarify_example_choice_3),
                                        wire = stringResource(R.string.clarify_example_choice_3),
                                    ),
                                ),
                        ),
                    progress = ClarifyProgress(current = 1, total = 2),
                ),
            onAnswer = {},
            onAnswerMulti = {},
        )
    }
}

@Preview(name = "Pregunta selección múltiple", showBackground = true, widthDp = 390)
@Composable
private fun ClarifyCardMultiSelectPreview() {
    MamaTheme {
        ClarifyCard(
            state =
                ClarifyCardState(
                    key = PREVIEW_KEY,
                    question = stringResource(R.string.clarify_example_multi_question),
                    input =
                        ClarifyInput.MultiSelect(
                            options =
                                listOf(
                                    ClarifyOption(
                                        display = stringResource(R.string.clarify_example_multi_1),
                                        wire = stringResource(R.string.clarify_example_multi_1),
                                    ),
                                    ClarifyOption(
                                        display = stringResource(R.string.clarify_example_multi_2),
                                        wire = stringResource(R.string.clarify_example_multi_2),
                                    ),
                                    ClarifyOption(
                                        display = stringResource(R.string.clarify_example_multi_3),
                                        wire = stringResource(R.string.clarify_example_multi_3),
                                    ),
                                ),
                        ),
                    progress = ClarifyProgress(current = 2, total = 3),
                ),
            onAnswer = {},
            onAnswerMulti = {},
        )
    }
}

@Preview(name = "Pregunta de texto libre", showBackground = true, widthDp = 390)
@Composable
private fun ClarifyCardFreeTextPreview() {
    MamaTheme {
        ClarifyCard(
            state =
                ClarifyCardState(
                    key = PREVIEW_KEY,
                    question = stringResource(R.string.clarify_example_free_question),
                    input = ClarifyInput.FreeText,
                ),
            onAnswer = {},
            onAnswerMulti = {},
        )
    }
}

@Preview(name = "Pregunta respondida", showBackground = true, widthDp = 390)
@Composable
private fun ClarifyCardAnsweredPreview() {
    MamaTheme {
        ClarifyCard(
            state =
                ClarifyCardState(
                    key = PREVIEW_KEY,
                    question = stringResource(R.string.clarify_example_free_question),
                    input = ClarifyInput.FreeText,
                    status = ClarifyStatus.Answered,
                ),
            onAnswer = {},
            onAnswerMulti = {},
        )
    }
}

@Preview(name = "Pregunta fallo envío", showBackground = true, widthDp = 390)
@Composable
private fun ClarifyCardFailedPreview() {
    MamaTheme {
        ClarifyCard(
            state =
                ClarifyCardState(
                    key = PREVIEW_KEY,
                    question = stringResource(R.string.clarify_example_free_question),
                    input = ClarifyInput.FreeText,
                    status = ClarifyStatus.SendFailed,
                ),
            onAnswer = {},
            onAnswerMulti = {},
        )
    }
}
