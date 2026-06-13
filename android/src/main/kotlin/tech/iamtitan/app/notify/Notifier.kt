package tech.iamtitan.app.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import tech.iamtitan.app.MainActivity

/**
 * Native AOSP notifications (NotificationManager — no GMS, AD-7). Three channels:
 *  - [CHANNEL_CHAT] — "Titan replied" / Titan-initiated messages while backgrounded.
 *  - [CHANNEL_HEALTH] — "Titan is down / recovered" (RFP event-channel health events),
 *    with a Restart action that opens the app to perform the signed restart.
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
            NotificationChannel(CHANNEL_HEALTH, "Titan status", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When Titan goes down or recovers"
            },
        )
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_LINK, "Titan link", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Titan stays connected"
                setShowBadge(false)
            },
        )
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_URGENT, "Titan urgent", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Time-sensitive messages from Titan"
                enableVibration(true)
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

    /** A time-sensitive ("urgency=high") Titan message → a heads-up on the urgent
     *  channel, distinct from a normal reply so it stands out. Persisted to ChatStore
     *  separately by the caller. */
    fun notifyUrgent(text: String) {
        if (!canPost()) return
        ensureChannels()
        val n = baseBuilder(CHANNEL_URGENT, android.R.drawable.stat_notify_chat)
            .setContentTitle("Titan — urgent")
            .setContentText(text.take(240))
            .setStyle(Notification.BigTextStyle().bigText(text.take(1000)))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(URGENT_NOTIF_ID, n)
    }

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

    /** Post a Titan health notification (RFP event-channel §1.3 step 5). A "down" event
     *  carries a Restart action that opens the app to run the signed `/console/restart`. */
    fun notifyHealth(up: Boolean, text: String) {
        if (!canPost()) return
        ensureChannels()
        val icon = if (up) android.R.drawable.stat_notify_sync else android.R.drawable.stat_sys_warning
        val builder = baseBuilder(CHANNEL_HEALTH, icon)
            .setContentTitle(if (up) "Titan recovered" else "Titan is down")
            .setContentText(text.take(240))
            .setStyle(Notification.BigTextStyle().bigText(text.take(1000)))
            .setAutoCancel(true)
        if (!up) {
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_menu_rotate),
                    "Restart",
                    restartIntent(),
                ).build(),
            )
        }
        NotificationManagerCompat.from(context).notify(HEALTH_NOTIF_ID, builder.build())
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

    /** Opens the app carrying the restart request — the Activity has a signing window
     *  (and can prompt if it lapsed), so the kernel restart runs with UI feedback. */
    private fun restartIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_ACTION, ACTION_RESTART)
        return PendingIntent.getActivity(
            context, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        const val CHANNEL_CHAT = "titan.chat"
        const val CHANNEL_HEALTH = "titan.health"
        const val CHANNEL_LINK = "titan.link"
        const val CHANNEL_URGENT = "titan.urgent"
        const val REPLY_NOTIF_ID = 1001
        const val LINK_NOTIF_ID = 1002
        const val HEALTH_NOTIF_ID = 1003
        const val URGENT_NOTIF_ID = 1004

        /** Intent extra the health-notification Restart action sets on MainActivity. */
        const val EXTRA_ACTION = "titan.action"
        const val ACTION_RESTART = "restart_titan"
    }
}
