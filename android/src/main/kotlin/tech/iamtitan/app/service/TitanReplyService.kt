package tech.iamtitan.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import tech.iamtitan.app.notify.Notifier

/**
 * A short foreground service that exists only to keep the process foreground (and
 * therefore un-frozen, with its socket alive) while an in-flight chat reply is
 * awaited — so backgrounding the app mid-reply no longer drops Titan's answer (the
 * reported quirk). It does NO work itself: the signed request runs in
 * `TitanApp.appScope`; this service is purely a process-priority anchor that the
 * send-pipeline starts before the request and stops when the reply lands.
 *
 * Started from the foreground (the Maker tapping Send) so the Android-12+
 * background-FGS-start restriction never applies. Declared `dataSync` in the
 * manifest (network sync; valid since API 26, type required on API 34+).
 */
class TitanReplyService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notifier = Notifier(this).apply { ensureChannels() }
        val n = notifier.linkNotification("Titan is replying…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(Notifier.LINK_NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(Notifier.LINK_NOTIF_ID, n)
        }
        // If the OS kills us under memory pressure we do NOT want an automatic
        // restart with a null intent — the send-pipeline owns lifecycle.
        return START_NOT_STICKY
    }

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, TitanReplyService::class.java),
            )
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(
                Intent(context.applicationContext, TitanReplyService::class.java),
            )
        }
    }
}
