package tech.iamtitan.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import tech.iamtitan.app.TitanApp
import tech.iamtitan.app.notify.Notifier

/**
 * The foreground-service process anchor for the event channel
 * (RFP_titan_app_event_channel §7.2a/b). It does NO work itself — the drain loop runs
 * in `TitanApp.appScope` (`ConnectionManager`); this service only keeps the process
 * foreground (un-frozen, socket alive) so the held long-poll survives. Two modes:
 *
 *  - [startShort] — `dataSync`, `START_NOT_STICKY`: the brief anchor while an in-flight
 *    chat reply is awaited (the original #1a quirk fix). Always started from the
 *    foreground (the Maker tapping Send), so the Android-12+ background-FGS-start
 *    restriction never applies. Well under the API-34 6 h/24 h `dataSync` cap.
 *
 *  - [startPersistent] — `specialUse`, `START_STICKY`: the opt-in "Stay connected"
 *    24/7 anchor for the ALWAYS_ON tier. **Must be `specialUse`, not `dataSync`** —
 *    `dataSync` is force-stopped after 6 h/24 h on API 34+, which would silently drop
 *    an always-on link. titan-app is sideloaded (no Play), so the `specialUse` review
 *    gate doesn't apply. On a `START_STICKY` respawn (OS killed the process under
 *    memory pressure) it re-arms the loop via `connection.setAlwaysOn(true)` so the
 *    link self-heals headlessly.
 *
 * Mode invariant: SHORT and PERSISTENT never run concurrently — the callers ensure it
 * (a chat send / urgency-warm only starts SHORT when always-connected is OFF; when ON,
 * PERSISTENT already holds the line).
 */
class TitanLinkService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent is a START_STICKY respawn → only PERSISTENT is sticky.
        val persistent = intent?.getBooleanExtra(EXTRA_PERSISTENT, false) ?: true
        val notifier = Notifier(this).apply { ensureChannels() }
        val text = if (persistent) "Connected to Titan" else "Titan is replying…"
        val n = notifier.linkNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type =
                if (persistent) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            startForeground(Notifier.LINK_NOTIF_ID, n, type)
        } else {
            startForeground(Notifier.LINK_NOTIF_ID, n)
        }
        if (persistent) {
            // Re-arm the always-on loop — covers a headless START_STICKY respawn where
            // no Activity exists to call setAlwaysOn. Idempotent.
            (applicationContext as? TitanApp)?.connection?.setAlwaysOn(true)
            return START_STICKY
        }
        // SHORT: the send / warm pipeline owns lifecycle — no auto-restart on a kill.
        return START_NOT_STICKY
    }

    companion object {
        private const val EXTRA_PERSISTENT = "persistent"

        /** Brief foreground anchor (chat reply in flight / urgency warm). */
        fun startShort(context: Context) = start(context, persistent = false)

        /** 24/7 opt-in anchor for ALWAYS_ON. */
        fun startPersistent(context: Context) = start(context, persistent = true)

        private fun start(context: Context, persistent: Boolean) {
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, TitanLinkService::class.java)
                    .putExtra(EXTRA_PERSISTENT, persistent),
            )
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(
                Intent(context.applicationContext, TitanLinkService::class.java),
            )
        }
    }
}
