package tech.iamtitan.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
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
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK = "titan-presence-sampler"

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

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK)
        }
    }
}
