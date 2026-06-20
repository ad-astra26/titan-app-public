package tech.iamtitan.app.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.iamtitan.app.chat.chatSessionFor
import tech.iamtitan.app.crypto.DeviceKey
import tech.iamtitan.app.data.ConnectionSettings
import tech.iamtitan.app.data.PairingStore
import tech.iamtitan.app.link.EventRenderer
import tech.iamtitan.app.net.AndroidHttpTransport
import tech.iamtitan.app.net.ConsoleClient
import tech.iamtitan.app.notify.Notifier
import java.util.concurrent.TimeUnit

/**
 * Background drain of the Console Agent event queue when the app is deep-backgrounded
 * (no held long-poll). WorkManager fires it on the OS-blessed ~15-min floor; each run
 * does ONE instant drain (`wait=0`), renders via [EventRenderer] (notifications +
 * transcript persistence), and acks. Battery-respectful: no standing
 * foreground service — just a periodic wake.
 *
 * Urgency warm-the-line: if a drained event is `urgency=="high"` and the
 * Maker has NOT opted into always-connected, the worker promotes itself to a short
 * foreground service ([setForeground]) and holds a brief long-poll so a follow-up
 * (a Titan burst, or a fast reply) lands without waiting for the next ~15-min cycle.
 * When always-connected is ON the persistent service already holds the line, so no warm.
 *
 * Signing is best-effort: the device key's 8-hour auth window lets `sign` succeed
 * headlessly while it's open; if the window has lapsed (no Activity here to prompt) this
 * run quietly no-ops and the next foreground open catches up. Never crashes the scheduler.
 */
class EventPollWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val store = PairingStore(ctx)
        if (!store.paired) return Result.success()
        val deviceId = store.deviceId ?: return Result.success()
        val endpoint = store.endpointUrl ?: return Result.success()
        // No Activity in a Worker → the provider throws if a biometric prompt is needed
        // (lapsed window); sign then fails and we skip this run gracefully.
        val key = DeviceKey.existing(ctx, store) { error("no activity in background") }
            ?: return Result.success()
        val client = ConsoleClient(endpoint, AndroidHttpTransport(tlsPin = store.tlsPin))

        return try {
            val resp = withContext(Dispatchers.IO) {
                client.events(key, wait = 0, since = store.eventCursor)
            }
            var hadUrgent = false
            if (resp.events.isNotEmpty()) {
                hadUrgent = EventRenderer.render(ctx, resp.events, deviceId)
                store.eventCursor = resp.cursor
            }
            withContext(Dispatchers.IO) { client.heartbeat(key, "background", store.eventCursor) }
            if (hadUrgent && !ConnectionSettings(ctx).alwaysConnected) {
                warmTheLine(client, key, store, deviceId)
            }
            Result.success()
        } catch (_: Exception) {
            Result.success() // lapsed signing window / transient network → next cycle catches up
        }
    }

    /** Hold a brief foreground long-poll after a high-urgency delivery (). */
    private suspend fun warmTheLine(
        client: ConsoleClient,
        key: DeviceKey,
        store: PairingStore,
        deviceId: String,
    ) {
        try {
            setForeground(warmForegroundInfo())
        } catch (_: Exception) {
            return // can't promote (rare OS refusal) → skip the warm; delivery already happened
        }
        val deadline = System.currentTimeMillis() + WARM_MS
        while (System.currentTimeMillis() < deadline) {
            val resp = try {
                withContext(Dispatchers.IO) {
                    client.events(key, wait = WARM_WAIT, since = store.eventCursor)
                }
            } catch (_: Exception) {
                break
            }
            if (resp.events.isNotEmpty()) {
                EventRenderer.render(applicationContext, resp.events, deviceId)
                store.eventCursor = resp.cursor
                withContext(Dispatchers.IO) { client.heartbeat(key, "background", store.eventCursor) }
            }
        }
    }

    private fun warmForegroundInfo(): ForegroundInfo {
        val n = Notifier(applicationContext).apply { ensureChannels() }
            .linkNotification("Titan — staying on briefly…")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(Notifier.LINK_NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Notifier.LINK_NOTIF_ID, n)
        }
    }

    companion object {
        const val WORK_NAME = "titan-event-poll"

        /** How long the urgency warm-the-line held poll runs (well under WorkManager's
         * 10-min execution ceiling and the 6 h dataSync cap). */
        private const val WARM_MS = 3 * 60 * 1000L
        private const val WARM_WAIT = 25

        /** Schedule the periodic deep-background drain (idempotent; KEEP existing). */
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<EventPollWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
