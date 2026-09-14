package ai.hermes.mama.feature.voice

import android.content.Context

/**
 * Texto humano para cada fallo del dictado — sin códigos ni tecnicismos
 * (ROADMAP §3: "sesión" → "chat", nada de jerga). Los textos viven en
 * `res/values/strings_voice.xml`.
 */
fun SpeechErrorKind.humanMessage(context: Context): String =
    context.getString(
        when (this) {
            SpeechErrorKind.PermissionDenied -> R.string.voice_error_permission_denied
            SpeechErrorKind.NotAvailable -> R.string.voice_error_not_available
            SpeechErrorKind.NoMatch -> R.string.voice_error_no_match
            SpeechErrorKind.Audio -> R.string.voice_error_audio
            SpeechErrorKind.Network -> R.string.voice_error_network
            SpeechErrorKind.Server -> R.string.voice_error_server
            SpeechErrorKind.Busy -> R.string.voice_error_busy
            SpeechErrorKind.LanguageUnavailable -> R.string.voice_error_language
            SpeechErrorKind.Unknown -> R.string.voice_error_unknown
        },
    )
