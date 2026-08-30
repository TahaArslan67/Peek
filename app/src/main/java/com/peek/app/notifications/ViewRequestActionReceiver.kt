package com.peek.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.peek.app.PeekSessionManager

/**
 * Görüntü istek bildiriminin Kabul/Reddet action butonlarını yakalar.
 *
 * Bildirim action'ları PendingIntent.getBroadcast ile bu receiver'a gelir.
 * Receiver PeekSessionManager üzerinden isteği yanıtlar ve bildirimi kapatır.
 *
 * MainActivity açık olsun veya olmasın çalışır — PeekSessionManager singleton
 * olduğu için bağlantı state'i her zaman erişilebilir.
 */
class ViewRequestActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ACCEPT -> {
                Log.d(TAG, "Görüntü isteği kabul edildi (bildirim action)")
                PeekSessionManager.get(context).acceptViewRequest()
                NotificationHelper.cancelViewRequestNotification(context)
            }

            ACTION_REJECT -> {
                Log.d(TAG, "Görüntü isteği reddedildi (bildirim action)")
                PeekSessionManager.get(context).rejectViewRequest()
                NotificationHelper.cancelViewRequestNotification(context)
            }
        }
    }

    companion object {
        private const val TAG = "ViewRequestActionReceiver"

        const val ACTION_ACCEPT = "com.peek.app.action.VIEW_ACCEPT"
        const val ACTION_REJECT = "com.peek.app.action.VIEW_REJECT"
    }
}
