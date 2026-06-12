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
import tech.iamtitan.app.chat.ChatTurn
import tech.iamtitan.app.chat.chatSessionFor
import tech.iamtitan.app.crypto.DeviceKey
import tech.iamtitan.app.data.ChatStore
import tech.iamtitan.app.data.PairingStore
import tech.iamtitan.app.net.AndroidHttpTransport
import tech.iamtitan.app.net.ConsoleClient
import tech.iamtitan.app.net.ConsoleEvent
import tech.iamtitan.app.notify.Notifier
import java.util.concurrent.TimeUnit

/**
 * Background drain of the Console Agent event queue when the app is deep-backgrounded
 * (no held long-poll). WorkManager fires it on the OS-blessed ~15-min floor; each run
 * does ONE instant drain (`wait=0`), renders notifications, persists Titan-initiated
 * messages to the transcript (so they're in history on open), and acks. Battery-
 * respectful (AG-EVT-5): no foreground service — just a periodic wake.
 *
 * Signing is best-effort: the device key's 8-hour auth window lets `sign()` succeed
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
        // (lapsed window); sign() then fails and we skip this run gracefully.
        val key = DeviceKey.existing(ctx, store) { error("no activity in background") }
            ?: return Result.success()
        val client = ConsoleClient(endpoint, AndroidHttpTransport(tlsPin = store.tlsPin))

        return try {
            val resp = withContext(Dispatchers.IO) {
                client.events(key, wait = 0, since = store.eventCursor)
            }
            if (resp.events.isNotEmpty()) {
                deliver(resp.events, Notifier(ctx).apply { ensureChannels() },
                        ChatStore(ctx), chatSessionFor(deviceId))
                store.eventCursor = resp.cursor
            }
            withContext(Dispatchers.IO) { client.heartbeat(key, "background", resp.cursor) }
            Result.success()
        } catch (_: Exception) {
            Result.success() // lapsed signing window / transient network → next cycle catches up
        }
    }

    /** Render events: a message persists to the transcript (deduped by seq) + notifies;
     *  a health event notifies. Unknown types are ignored (forward-compatible). */
    private fun deliver(
        events: List<ConsoleEvent>,
        notifier: Notifier,
        chatStore: ChatStore,
        session: String,
    ) {
        val turns = chatStore.load(session).toMutableList()
        var changed = false
        for (e in events) {
            when (e.type) {
                "message", "reply" -> {
                    val text = e.messageText() ?: continue
                    val id = "evt-${e.seq}"
                    if (turns.none { it.id == id }) {
                        turns.add(ChatTurn(fromMaker = false, text = text,
                                           ts = (e.ts * 1000).toLong(), id = id))
                        changed = true
                    }
                    notifier.notifyReply(text)
                }
                "health" -> notifier.notifyHealth(
                    e.healthUp(),
                    e.healthText() ?: if (e.healthUp()) "Titan recovered." else "Titan is down.",
                )
                else -> Unit
            }
        }
        if (changed) chatStore.save(session, turns)
    }

    companion object {
        const val WORK_NAME = "titan-event-poll"

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
