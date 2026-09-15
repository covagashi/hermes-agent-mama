package ai.hermes.mama.feature.browser

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Notificación del sistema al terminar una descarga (ROADMAP §5/G1).
 *
 * - Canal «Descargas» ([CHANNEL_ID]) creado al primer aviso.
 * - Tocarla abre el documento — sólo si alguna app resuelve el `ACTION_VIEW`
 *   (un `PendingIntent` hacia un intent sin receptor tumba el lanzador).
 * - Sin `POST_NOTIFICATIONS` concedido (API 33+) el aviso no sale: se anota y
 *   ya — la descarga en sí nunca depende del permiso.
 * - Best-effort total: ningún fallo aquí puede tumbar la descarga.
 */
class DownloadNotifier(
    context: Context,
    private val logger: (String) -> Unit = {},
) {
    private val appContext = context.applicationContext

    fun notifySaved(doc: DownloadedDoc) {
        val manager = NotificationManagerCompat.from(appContext)
        if (!manager.areNotificationsEnabled()) {
            warn("notificación de descarga omitida (permiso denegado)")
            return
        }
        runCatching {
            ensureChannel(manager)
            // Una notificación por documento (id derivado de la content-uri):
            // dos descargas seguidas no se pisan entre sí.
            manager.notify(doc.contentUri.hashCode(), buildNotification(doc))
        }.onFailure { warn("notificación de descarga falló (${it::class.simpleName})") }
    }

    private fun ensureChannel(manager: NotificationManagerCompat) {
        val channel =
            NotificationChannelCompat
                .Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(appContext.getString(R.string.download_notification_channel))
                .build()
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(doc: DownloadedDoc): android.app.Notification =
        NotificationCompat
            .Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(appContext.getString(R.string.download_notification_title))
            .setContentText(
                appContext.getString(R.string.download_notification_text, doc.fileName),
            ).setAutoCancel(true)
            .apply { openIntent(doc)?.let(::setContentIntent) }
            .build()

    /** `ACTION_VIEW` al `content://` de MediaStore — null si nadie lo resuelve. */
    private fun openIntent(doc: DownloadedDoc): PendingIntent? {
        val intent =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(android.net.Uri.parse(doc.contentUri), doc.mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (intent.resolveActivity(appContext.packageManager) == null) {
            return null
        }
        return PendingIntent.getActivity(
            appContext,
            REQUEST_OPEN,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun warn(message: String) {
        runCatching { logger(message) }
    }

    private companion object {
        const val CHANNEL_ID = "downloads"
        const val REQUEST_OPEN = 42
    }
}
