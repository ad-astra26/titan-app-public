package tech.iamtitan.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.iamtitan.app.crypto.DeviceKey
import tech.iamtitan.app.data.PairingStore
import tech.iamtitan.app.data.PresenceStore
import tech.iamtitan.app.net.AndroidHttpTransport
import tech.iamtitan.app.net.ConsoleClient
import tech.iamtitan.app.presence.PresenceCollector
import java.util.concurrent.TimeUnit

/**
 * Background presence sampler ( / ). On the OS cadence it
 * collects ONE opt-in-gated [tech.iamtitan.app.presence.PresenceSample] (AOSP senses only)
 * and uploads it signed to /console/context. Battery-respectful: a periodic wake, no standing
 * service. Signing is best-effort headless via the DeviceKey 8h window (mirrors EventPollWorker);
 * a lapsed window quietly no-ops. Scheduled only while a sensor is opted in; cancelled when none.
 */
class PresenceWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val store = PairingStore(ctx)
        val presence = PresenceStore(ctx)
        if (!store.paired || !presence.anyEnabled) return Result.success()
        val endpoint = store.endpointUrl ?: return Result.success()
        val sample = PresenceCollector.collect(ctx, presence.settings())
            ?: return Result.success() // nothing collectable this cycle
        // No Activity in a Worker → a lapsed key window can't prompt; sign then fails and we
        // skip this cycle gracefully (never crash the scheduler).
        val key = DeviceKey.existing(ctx, store) { error("no activity in background") }
            ?: return Result.success()
        val client = ConsoleClient(endpoint, AndroidHttpTransport(tlsPin = store.tlsPin))
        return try {
            withContext(Dispatchers.IO) { client.uploadContext(key, listOf(sample)) }
            maybeChainWhileMoving(ctx, presence, sample.lat, sample.lon)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    /** Adaptive cadence (): if this fix moved ≥ [MOVING_THRESHOLD_M] from the last,
     * the Maker is on the move → enqueue a FAST one-shot follow-up so Titan gets fresh presence
     * every few minutes, not every 15. When still (small/no delta), do nothing → the 15-min
     * periodic baseline carries on (battery-respectful). */
    private fun maybeChainWhileMoving(ctx: Context, store: PresenceStore, lat: Double?, lon: Double?) {
        if (lat == null || lon == null) return
        val prevLat = store.lastLat
        val prevLon = store.lastLon
        store.lastLat = lat.toFloat()
        store.lastLon = lon.toFloat()
        if (prevLat.isNaN() || prevLon.isNaN()) return   // first fix — no baseline to compare
        val out = FloatArray(1)
        android.location.Location.distanceBetween(
            prevLat.toDouble(), prevLon.toDouble(), lat, lon, out,
        )
        if (out[0] >= MOVING_THRESHOLD_M) enqueueFast(ctx)
    }

    companion object {
        private const val WORK = "titan-presence-sampler"
        private const val FAST_WORK = "titan-presence-fast"
        private const val MOVING_THRESHOLD_M = 120f      // moved this far since last fix ⇒ moving
        private const val FAST_CADENCE_MINUTES = 3L      // upload cadence while moving

        /** (Re)schedule the periodic sampler at the opted-in cadence (WorkManager floors at 15m). */
        fun schedule(context: Context, cadenceMinutes: Int) {
            val req = PeriodicWorkRequestBuilder<PresenceWorker>(
                cadenceMinutes.toLong().coerceAtLeast(15), TimeUnit.MINUTES,
            ).setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            ).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        /** One-shot sample after [delayMinutes] — the fast chain while moving, or an immediate
         * upload on a significant-motion trigger ([delayMinutes]=0). REPLACE keeps one pending. */
        fun enqueueFast(context: Context, delayMinutes: Long = FAST_CADENCE_MINUTES) {
            val req = OneTimeWorkRequestBuilder<PresenceWorker>()
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                ).build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(FAST_WORK, ExistingWorkPolicy.REPLACE, req)
        }

        fun cancel(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(WORK)
            wm.cancelUniqueWork(FAST_WORK)
        }
    }
}
