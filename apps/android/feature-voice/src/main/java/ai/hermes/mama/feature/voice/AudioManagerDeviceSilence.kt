package ai.hermes.mama.feature.voice

import android.content.Context
import android.media.AudioManager

/**
 * [DeviceSilenceChecker] real sobre `AudioManager` (ROADMAP §5, D2).
 *
 * El móvil "está en silencio" cuando el modo de timbre no es el normal (silencio o
 * vibración: la usuaria pidió que el teléfono no haga ruido) o cuando el stream de
 * música está silenciado. En ese estado la app nunca habla sola.
 */
class AudioManagerDeviceSilence(
    context: Context,
) : DeviceSilenceChecker {
    private val audioManager =
        context.applicationContext.getSystemService(AudioManager::class.java)

    override fun isDeviceSilent(): Boolean {
        val manager = audioManager ?: return false
        val quietRinger = manager.ringerMode != AudioManager.RINGER_MODE_NORMAL
        val mutedMedia = manager.isStreamMute(AudioManager.STREAM_MUSIC)
        return quietRinger || mutedMedia
    }
}
