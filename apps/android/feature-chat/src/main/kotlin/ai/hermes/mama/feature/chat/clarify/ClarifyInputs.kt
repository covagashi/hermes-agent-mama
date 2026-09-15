package ai.hermes.mama.feature.chat.clarify

import ai.hermes.mama.core.ui.components.BigButton
import ai.hermes.mama.core.ui.components.MamaButtonVariant
import ai.hermes.mama.core.ui.theme.MamaDimens
import ai.hermes.mama.feature.chat.R
import ai.hermes.mama.feature.voice.SpeechErrorKind
import ai.hermes.mama.feature.voice.SpeechInput
import ai.hermes.mama.feature.voice.SpeechState
import ai.hermes.mama.feature.voice.humanMessage
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ai.hermes.mama.feature.voice.R as VoiceR

/**
 * Las entradas de la tarjeta de pregunta (C7): lo que la pregunta pide
 * (opciones, multi-select o sólo el campo libre) + la fila de texto/micro que
 * siempre está ("o responde con tu voz", mockup Pregunta.dc.html).
 */
@Composable
internal fun InputContent(
    state: ClarifyCardState,
    onAnswer: (String) -> Unit,
    onAnswerMulti: (List<String>) -> Unit,
    speech: SpeechInput?,
) {
    when (val input = state.input) {
        is ClarifyInput.Choices ->
            input.options.forEach { option ->
                ChoiceButton(option = option, onClick = { onAnswer(option.wire) })
            }
        is ClarifyInput.MultiSelect ->
            MultiSelectInput(
                state = state,
                options = input.options,
                onDone = onAnswerMulti,
            )
        ClarifyInput.FreeText -> Unit // El campo libre ya es toda la entrada.
    }
    // El campo + micro siempre están: "o responde con tu voz" (mockup).
    FreeTextRow(state = state, speech = speech, onAnswer = onAnswer)
}

/** Una opción `choices`: fila grande con borde (≥ 60 dp del mockup), un tap responde. */
@Composable
private fun ChoiceButton(
    option: ClarifyOption,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = MamaDimens.FieldHeight),
        shape = RoundedCornerShape(MamaDimens.CardCorner),
        color = scheme.surface,
        contentColor = scheme.onBackground,
        border = BorderStroke(OPTION_BORDER_DP, scheme.outline),
    ) {
        Column(
            modifier =
                Modifier
                    .heightIn(min = MamaDimens.FieldHeight)
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(text = option.display, style = MaterialTheme.typography.titleMedium)
            if (option.recommended) {
                Text(
                    text = stringResource(R.string.clarify_recommended),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * `multi_select`: las mismas filas grandes pero en modo casilla (TalkBack:
 * "casilla de verificación"), con el botón "Listo" que envía lo marcado en el
 * orden en que el servidor ofreció las opciones.
 */
@Composable
private fun MultiSelectInput(
    state: ClarifyCardState,
    options: List<ClarifyOption>,
    onDone: (List<String>) -> Unit,
) {
    // Las etiquetas wire marcadas; se reinician al cambiar de pregunta del lote.
    var selected by
        rememberSaveable(state.key, state.questionNumber) { mutableStateOf(setOf<String>()) }
    options.forEach { option ->
        MultiOptionButton(
            option = option,
            checked = option.wire in selected,
            onToggle = {
                selected =
                    if (option.wire in selected) {
                        selected - option.wire
                    } else {
                        selected + option.wire
                    }
            },
        )
    }
    BigButton(
        text = stringResource(R.string.clarify_done),
        onClick = {
            // Orden del servidor, no el de los taps: answers[qid] debe ser
            // predecible para quien lee el JSON.
            onDone(options.filter { it.wire in selected }.map { it.wire })
        },
        variant = MamaButtonVariant.Primary,
        icon = Icons.Outlined.Check,
        iconContentDescription = null,
        enabled = selected.isNotEmpty(),
    )
}

/** Fila de opción en modo casilla (selected = tinte verde + check). */
@Composable
private fun MultiOptionButton(
    option: ClarifyOption,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = MamaDimens.FieldHeight)
                // Role.Checkbox + "marcada/sin marcar" para TalkBack (en vez
                // del Role.Button que daría Surface(onClick=…)).
                .toggleable(
                    value = checked,
                    role = Role.Checkbox,
                    onValueChange = { onToggle() },
                ),
        shape = RoundedCornerShape(MamaDimens.CardCorner),
        color = if (checked) scheme.primaryContainer else scheme.surface,
        contentColor = if (checked) scheme.onPrimaryContainer else scheme.onBackground,
        border =
            BorderStroke(
                OPTION_BORDER_DP,
                if (checked) scheme.primary else scheme.outline,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .heightIn(min = MamaDimens.FieldHeight)
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector =
                    if (checked) {
                        Icons.Outlined.CheckCircle
                    } else {
                        Icons.Outlined.RadioButtonUnchecked
                    },
                // La casilla se describe sola ("marcada"/"sin marcar"); el icono es decorativo.
                contentDescription = null,
                tint = if (checked) scheme.primary else scheme.onSurfaceVariant,
                modifier = Modifier.size(MamaDimens.IconSizeLarge),
            )
            Column(verticalArrangement = Arrangement.Center) {
                Text(text = option.display, style = MaterialTheme.typography.titleMedium)
                if (option.recommended) {
                    Text(
                        text = stringResource(R.string.clarify_recommended),
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (checked) {
                                scheme.onPrimaryContainer
                            } else {
                                scheme.onSurfaceVariant
                            },
                    )
                }
            }
        }
    }
}

/**
 * Campo de texto + botón circular: 🎤 cuando el campo está vacío (tap para
 * dictar, otro tap para parar) o ➤ cuando hay texto escrito/dictado. [speech]
 * null deja el micrófono deshabilitado pero visible — la usuaria siempre puede
 * escribir.
 */
@Composable
private fun FreeTextRow(
    state: ClarifyCardState,
    speech: SpeechInput?,
    onAnswer: (String) -> Unit,
) {
    var text by rememberSaveable(state.key, state.questionNumber) { mutableStateOf("") }
    var isListening by remember(state.key, state.questionNumber) { mutableStateOf(false) }
    var voiceError by
        remember(state.key, state.questionNumber) { mutableStateOf<SpeechErrorKind?>(null) }

    speech?.let { input ->
        VoiceSyncEffect(
            speech = input,
            key = state.key,
            questionNumber = state.questionNumber,
            onListening = { isListening = it },
            onText = { text = it },
            onError = { voiceError = it },
        )
    }

    val submit = {
        val answer = text.trim()
        if (answer.isNotEmpty()) {
            // No se limpia el campo: si el envío falla (SendFailed) la
            // respuesta queda para reintentar; si va bien, la tarjeta se
            // oculta sola (y la siguiente pregunta reinicia el rememberSaveable).
            onAnswer(answer)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnswerField(
                text = text,
                listening = isListening,
                onTextChange = {
                    text = it
                    voiceError = null
                },
                onSubmit = { submit() },
                modifier = Modifier.weight(1f),
            )
            MicWithPermission(
                listening = isListening,
                hasText = text.isNotBlank(),
                speech = speech,
                onVoiceEvent = { voiceError = it },
                onSend = { submit() },
            )
        }
        voiceError?.let { kind -> VoiceErrorHint(kind) }
    }
}

/**
 * 🎤/➤ + la petición de RECORD_AUDIO. El permiso se pide desde esta
 * superficie (la dueña del micro): primero la explicación humana de D1 y
 * luego el request del sistema — concedido arranca el dictado, negado deja
 * el hint "puedes escribir". En una instalación limpia el micro ya no es un
 * callejón sin salida.
 */
@Composable
private fun MicWithPermission(
    listening: Boolean,
    hasText: Boolean,
    speech: SpeechInput?,
    onVoiceEvent: (SpeechErrorKind?) -> Unit,
    onSend: () -> Unit,
) {
    var rationaleVisible by remember { mutableStateOf(false) }
    val micPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                speech?.startListening()
            } else {
                onVoiceEvent(SpeechErrorKind.PermissionDenied)
            }
        }
    MicOrSendButton(
        listening = listening,
        hasText = hasText,
        speech = speech,
        onMicClick = {
            if (listening) {
                speech?.stopListening()
            } else if (speech != null) {
                onVoiceEvent(null)
                if (speech.hasAudioPermission()) {
                    speech.startListening()
                } else {
                    rationaleVisible = true
                }
            }
        },
        onSend = onSend,
    )
    if (rationaleVisible) {
        MicPermissionDialog(
            onAllow = {
                rationaleVisible = false
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            },
            onNotNow = { rationaleVisible = false },
        )
    }
}

/**
 * Explicación humana previa al request de RECORD_AUDIO (D1: permiso "con
 * explicación"): la usuaria decide sabiendo para qué sirve antes de que el
 * sistema pregunte. Misma familia visual que los diálogos de C3.
 */
@Composable
private fun MicPermissionDialog(
    onAllow: () -> Unit,
    onNotNow: () -> Unit,
) {
    val title = stringResource(VoiceR.string.voice_permission_rationale_title)
    Dialog(
        onDismissRequest = onNotNow,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .semantics {
                        paneTitle = title
                        liveRegion = LiveRegionMode.Polite
                    },
            shape = RoundedCornerShape(MamaDimens.SheetCorner),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            Column(
                modifier =
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(text = title, style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = stringResource(VoiceR.string.voice_permission_rationale),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BigButton(
                    text = stringResource(VoiceR.string.voice_permission_allow),
                    onClick = onAllow,
                    variant = MamaButtonVariant.Primary,
                    icon = Icons.Outlined.Mic,
                    iconContentDescription = null,
                )
                BigButton(
                    text = stringResource(VoiceR.string.voice_permission_not_now),
                    onClick = onNotNow,
                    variant = MamaButtonVariant.Neutral,
                )
            }
        }
    }
}

/** Mensaje humano del fallo de dictado bajo el campo (SpeechMessages de D1). */
@Composable
private fun VoiceErrorHint(kind: SpeechErrorKind) {
    Text(
        text = kind.humanMessage(LocalContext.current),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

/**
 * Sincroniza el dictado con el campo: los parciales en vivo y el resultado
 * final fijan el texto; el fallo sube como [SpeechErrorKind]. Al cambiar de
 * pregunta o desmontar la tarjeta con el micro abierto, la sesión se cancela.
 */
@Composable
private fun VoiceSyncEffect(
    speech: SpeechInput,
    key: String,
    questionNumber: Int,
    onListening: (Boolean) -> Unit,
    onText: (String) -> Unit,
    onError: (SpeechErrorKind) -> Unit,
) {
    // Parciales en vivo → el campo; el resultado final lo fija y limpia el
    // estado de voz (cancel() vuelve a Idle — no hay sesión que abortar: el
    // engine ya se liberó al emitir Done).
    LaunchedEffect(speech, key, questionNumber) {
        speech.state.collect { speechState ->
            when (speechState) {
                is SpeechState.Listening -> {
                    onListening(true)
                    if (speechState.partial.isNotEmpty()) {
                        onText(speechState.partial)
                    }
                }
                is SpeechState.Done -> {
                    onListening(false)
                    onText(speechState.text)
                    speech.cancel()
                }
                is SpeechState.Error -> {
                    onListening(false)
                    onError(speechState.kind)
                }
                SpeechState.Idle -> onListening(false)
            }
        }
    }
    DisposableEffect(speech, key, questionNumber) {
        onDispose { speech.cancel() }
    }
}

/** 🎤 mientras se dicta o el campo está vacío; ➤ cuando hay texto que enviar. */
@Composable
private fun MicOrSendButton(
    listening: Boolean,
    hasText: Boolean,
    speech: SpeechInput?,
    onMicClick: () -> Unit,
    onSend: () -> Unit,
) {
    if (listening || !hasText) {
        MicButton(listening = listening, enabled = speech != null, onClick = onMicClick)
    } else {
        SendButton(onClick = onSend)
    }
}

/** El campo de respuesta (píldora del mockup, 56+ dp, IME Send = enviar). */
@Composable
private fun AnswerField(
    text: String,
    listening: Boolean,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        modifier = modifier.heightIn(min = MamaDimens.MinTouchTarget),
        placeholder = {
            Text(
                text =
                    stringResource(
                        if (listening) {
                            R.string.clarify_field_hint_listening
                        } else {
                            R.string.clarify_field_hint
                        },
                    ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        textStyle = MaterialTheme.typography.bodyLarge,
        singleLine = true,
        shape = CircleShape, // píldora del mockup
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { onSubmit() }),
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            ),
    )
}

/** Botón circular del micrófono (56 dp): tap = empezar/parar el dictado. */
@Composable
private fun MicButton(
    listening: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(MamaDimens.MinTouchTarget),
        shape = CircleShape,
        color = if (enabled) scheme.primary else scheme.surfaceVariant,
        contentColor = if (enabled) scheme.onPrimary else scheme.onSurfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (listening) Icons.Filled.Mic else Icons.Outlined.Mic,
                contentDescription =
                    stringResource(
                        if (listening) {
                            R.string.clarify_mic_stop_cd
                        } else {
                            R.string.clarify_mic_cd
                        },
                    ),
                modifier = Modifier.size(MamaDimens.IconSizeLarge),
            )
        }
    }
}

/** Botón circular de enviar (56 dp), sólo visible con texto en el campo. */
@Composable
private fun SendButton(onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier.size(MamaDimens.MinTouchTarget),
        shape = CircleShape,
        color = scheme.primary,
        contentColor = scheme.onPrimary,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.Send,
                contentDescription = stringResource(R.string.clarify_send_cd),
                modifier = Modifier.size(MamaDimens.IconSizeLarge),
            )
        }
    }
}
