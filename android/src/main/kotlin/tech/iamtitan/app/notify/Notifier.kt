package tech.iamtitan.app.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import tech.iamtitan.app.MainActivity

/**
 * Native AOSP notifications (NotificationManager — no GMS, AD-7). Two channels:
 *  - [CHANNEL_CHAT] — "Titan replied" while the app is backgrounded.
 *  - [CHANNEL_LINK] — the low-importance ongoing notice for `TitanReplyService`
 *    (a foreground service must show one).
 *
 * Posting the reply notification is permission-aware: on API 33+ POST_NOTIFICATIONS
 * is a runtime grant; if it's absent we silently skip the OS notification — the
 * reply is still persisted to ChatStore and shown in-app, so nothing is lost.
 */
class Notifier(private val context: Context) {

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_CHAT, "Titan messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Replies and messages from Titan"
            },
        )
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_LINK, "Titan link", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown briefly while Titan is replying"
                setShowBadge(false)
            },
        )
    }

    /** The ongoing notification a foreground service is required to display. */
    fun linkNotification(text: String): Notification =
        baseBuilder(CHANNEL_LINK, android.R.drawable.stat_notify_sync)
            .setContentTitle("Titan")
            .setContentText(text)
            .setOngoing(true)
            .build()

    /** Post a "Titan replied" notification iff allowed; no-op (safely) otherwise. */
    fun notifyReply(text: String) {
        if (!canPost()) return
        ensureChannels()
        val n = baseBuilder(CHANNEL_CHAT, android.R.drawable.stat_notify_chat)
            .setContentTitle("Titan")
            .setContentText(text.take(240))
            .setStyle(Notification.BigTextStyle().bigText(text.take(1000)))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(REPLY_NOTIF_ID, n)
    }

    private fun baseBuilder(channelId: String, smallIcon: Int): Notification.Builder {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, channelId)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(context)
        }
        return builder.setSmallIcon(smallIcon).setContentIntent(openAppIntent())
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        // minSdk 26 ⇒ FLAG_IMMUTABLE always available.
        return PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        const val CHANNEL_CHAT = "titan.chat"
        const val CHANNEL_LINK = "titan.link"
        const val REPLY_NOTIF_ID = 1001
        const val LINK_NOTIF_ID = 1002
    }
}
