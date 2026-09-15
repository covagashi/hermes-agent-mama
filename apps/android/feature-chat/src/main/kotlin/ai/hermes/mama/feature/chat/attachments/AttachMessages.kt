package ai.hermes.mama.feature.chat.attachments

import ai.hermes.mama.feature.chat.R
import android.content.Context

/**
 * Texto humano para cada fallo al adjuntar un archivo — sin códigos ni
 * tecnicismos (ROADMAP §3: la usuaria es una persona mayor; "gateway" →
 * "Hermes"). Los textos viven en `res/values/strings_attachments.xml`.
 */
fun FileAttachError.humanMessage(context: Context): String =
    context.getString(
        when (this) {
            FileAttachError.Unreadable -> R.string.attach_error_unreadable
            FileAttachError.Empty -> R.string.attach_error_empty
            FileAttachError.TooLarge -> R.string.attach_error_too_large
            FileAttachError.NotAttached -> R.string.attach_error_not_attached
            FileAttachError.SendFailed -> R.string.attach_error_send_failed
        },
    )
