package com.peek.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.peek.app.R
import com.peek.app.overlay.OverlayWindowService
import com.peek.app.ui.MainActivity

/**
 * Bildirimleri yönetir.
 *
 * İki kanal:
 *  - peek_foreground_channel (LOW): OverlayWindowService foreground bildirimi
 *  - peek_request_channel (HIGH): Görüntü istek bildirimi (heads-up, sesli uyarı)
 *
 * Görüntü istek bildirimi, kullanıcı Activity kapalıyken bile görüntü isteğini
 * fark etmesi için tasarlandı. Kabul/Reddet action butonları içerir
 * (ViewRequestActionReceiver'a PendingIntent ile gider).
 */
object NotificationHelper {

    private const val CHANNEL_ID = "peek_foreground_channel"
    private const val NOTIFICATION_ID = 1001

    const val CHANNEL_ID_REQUEST = "peek_request_channel"
    const val REQUEST_NOTIFICATION_ID = 2001

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)

            // Foreground service kanalı (LOW)
            val fgChannel = NotificationChannel(
                CHANNEL_ID,
                "Peek Aktif",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Peek kamera paylaşımı sürüyor"
            }
            manager.createNotificationChannel(fgChannel)

            // Görüntü istek kanalı (HIGH — heads-up + ses)
            val reqChannel = NotificationChannel(
                CHANNEL_ID_REQUEST,
                "Görüntü İstekleri",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Karşı taraftan gelen görüntü istekleri"
                enableVibration(true)
            }
            manager.createNotificationChannel(reqChannel)
        }
    }

    fun buildForegroundNotification(context: Context): android.app.Notification {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // "Kapat" action: OverlayWindowService'e ACTION_STOP gönder
        val stopIntent = Intent(context, OverlayWindowService::class.java).apply {
            action = OverlayWindowService.ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            context, 10, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notif_foreground_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_launcher_foreground, context.getString(R.string.notif_action_stop), stopPending)
            .build()
    }

    fun getNotificationId(): Int = NOTIFICATION_ID

    /**
     * Görüntü istek bildirimi göster.
     *
     * - Heads-up notification (IMPORTANCE_HIGH)
     * - "Kabul" action → ViewRequestActionReceiver.ACTION_ACCEPT
     * - "Reddet" action → ViewRequestActionReceiver.ACTION_REJECT
     * - Tıklayınca MainActivity aç
     * - Auto-cancel: false (kullanıcı action seçene kadar kalsın)
     */
    fun showViewRequestNotification(context: Context, requesterName: String? = null) {
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPending = PendingIntent.getActivity(
            context, 0, contentIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val acceptIntent = Intent(context, ViewRequestActionReceiver::class.java).apply {
            action = ViewRequestActionReceiver.ACTION_ACCEPT
        }
        val acceptPending = PendingIntent.getBroadcast(
            context, 1, acceptIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val rejectIntent = Intent(context, ViewRequestActionReceiver::class.java).apply {
            action = ViewRequestActionReceiver.ACTION_REJECT
        }
        val rejectPending = PendingIntent.getBroadcast(
            context, 2, rejectIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = context.getString(R.string.notif_view_request_title)
        val text = requesterName?.let { "$it ${context.getString(R.string.notif_view_request_text)}" }
            ?: context.getString(R.string.notif_view_request_text)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_REQUEST)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(contentPending)
            .addAction(R.drawable.ic_launcher_foreground, context.getString(R.string.dialog_accept), acceptPending)
            .addAction(R.drawable.ic_launcher_foreground, context.getString(R.string.dialog_reject), rejectPending)
            .setAutoCancel(false)
            .setOngoing(true)
            .build()

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(REQUEST_NOTIFICATION_ID, notification)
    }

    /** İstek yanıtlandıktan sonra bildirimi kapat. */
    fun cancelViewRequestNotification(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(REQUEST_NOTIFICATION_ID)
    }
}
