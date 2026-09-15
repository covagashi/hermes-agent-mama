package ai.hermes.mama.feature.chat.attachments

import ai.hermes.mama.feature.chat.R
import android.content.Context

/**
 * Texto humano para cada fallo de [ImageAttacher] — sin códigos ni tecnicismos
 * (ROADMAP §3). Los textos viven en `res/values/strings_attachments.xml`.
 */
fun ImageAttachError.humanMessage(context: Context): String =
    context.getString(
        when (this) {
            ImageAttachError.Unreadable -> R.string.attach_image_error_unreadable
            ImageAttachError.NotAnImage -> R.string.attach_image_error_not_image
            ImageAttachError.TooLarge -> R.string.attach_image_error_too_large
            ImageAttachError.SendFailed -> R.string.attach_image_error_send_failed
        },
    )
