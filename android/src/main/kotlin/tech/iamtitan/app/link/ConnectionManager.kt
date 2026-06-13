package tech.iamtitan.app.link

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.iamtitan.app.TitanApp
import tech.iamtitan.app.TitanController
import tech.iamtitan.app.chat.chatSessionFor
import tech.iamtitan.app.crypto.DeviceKey
import tech.iamtitan.app.data.ConnectionSettings
import tech.iamtitan.app.data.PairingStore
import tech.iamtitan.app.net.AndroidHttpTransport
import tech.iamtitan.app.net.ConsoleClient
import tech.iamtitan.app.net.ConsoleEvent
import tech.iamtitan.app.work.EventPollWorker

/**
 * Owns the event-channel drain loop and the tier state machine
 * (RFP_titan_app_event_channel §7.2a). Held as a **process singleton** on [TitanApp]
 * (NOT per-Activity) so the held long-poll survives Activity recreation without
 * duplicating or orphaning — the foundation the §7.2b ALWAYS_ON tier stands on.
 *
 * It runs on the process-lifetime [appScope] and rebuilds its own HTTP client / signer
 * / cursor from the [PairingStore] each cycle, so it is decoupled from the per-Activity
 * `TitanController`; the controller only [bind]s a renderer (to fold drained events
 * into the live Compose transcript + notifications) and drives lifecycle transitions.
 *
 * Tier → transport:
 *  - any tier that [holdsLongPoll] runs the held 25 s long-poll on [appScope];
 *  - [Tier.DEEP_BG] cedes to [EventPollWorker] (WorkManager ~15 min).
 * Foreground-service anchoring for ACTIVE_TASK / ALWAYS_ON is wired in §7.2b; here the
 * GRACE tier is explicitly best-effort with NO foreground service.
 *
 * Confinement: every mutator and [apply] runs on the main thread (lifecycle callbacks
 * + the [appScope] = `MainScope`), so the tier flags need no extra synchronization;
 * only the network drain hops to [Dispatchers.IO].
 */
class ConnectionManager(
    private val appScope: CoroutineScope,
    context: Context,
) {
    private val context = context.applicationContext
    private val store = PairingStore(this.context)

    private var signerProvider: (() -> DeviceKey?)? = null
    private var renderer: ((List<ConsoleEvent>) -> Unit)? = null

    private var loopJob: Job? = null
    private var graceJob: Job? = null

    // Tier inputs (main-thread confined).
    private var foreground = false
    private var sending = false
    private var alwaysOn = false // §7.2b opt-in; always false in §7.2a
    private var backgroundedAt = 0L

    /** Wire the signed-key source + the live renderer. Replaces any prior binding
     *  (a recreated Activity rebinds its fresh renderer over the running loop). */
    fun bind(signer: () -> DeviceKey?, render: (List<ConsoleEvent>) -> Unit) {
        signerProvider = signer
        renderer = render
    }

    fun onForeground() {
        foreground = true
        graceJob?.cancel()
        apply()
    }

    fun onBackground() {
        foreground = false
        backgroundedAt = System.currentTimeMillis()
        apply()
    }

    /** A chat send is in flight → keep the line held (ACTIVE_TASK) if backgrounded. */
    fun onSending(active: Boolean) {
        sending = active
        apply()
    }

    /** §7.2b: the "Stay connected" opt-in. No-op effect in §7.2a (default false). */
    fun setAlwaysOn(on: Boolean) {
        alwaysOn = on
        apply()
    }

    /** Recompute the tier from the current inputs and start/stop the held loop. */
    private fun apply() {
        if (!store.paired) {
            stopLoop()
            return
        }
        val tier = selectTier(
            foreground = foreground,
            sending = sending,
            alwaysOn = alwaysOn,
            msSinceBackground = System.currentTimeMillis() - backgroundedAt,
        )
        if (tier.holdsLongPoll()) startLoop() else stopLoop()

        graceJob?.cancel()
        if (tier == Tier.GRACE) {
            // Re-evaluate exactly when the grace window lapses → drop to DEEP_BG.
            val fireIn = (GRACE_WINDOW_MS - (System.currentTimeMillis() - backgroundedAt)).coerceAtLeast(0)
            graceJob = appScope.launch {
                delay(fireIn)
                apply()
            }
        }
    }

    private fun startLoop() {
        val provider = signerProvider ?: return
        if (loopJob?.isActive == true) return
        EventPollWorker.schedule(context) // background cadence (KEEP-idempotent)
        loopJob = appScope.launch {
            provider()?.let { heartbeat(it) }
            var failures = 0 // escalates backoff so a lapsed signing window / kernel-down
            while (isActive) { // doesn't spin the held loop (matters for 24/7 ALWAYS_ON battery)
                val key = provider() ?: break
                val t0 = System.currentTimeMillis()
                val resp = try {
                    withContext(Dispatchers.IO) {
                        client().events(key, wait = LONGPOLL_WAIT, since = store.eventCursor)
                    }
                } catch (_: Exception) {
                    failures++; delay(backoffFor(failures)); continue
                }
                if (resp.events.isNotEmpty()) {
                    failures = 0
                    renderEvents(resp.events)
                    store.eventCursor = resp.cursor
                    heartbeat(key) // carries ack_cursor = the new cursor
                } else if (System.currentTimeMillis() - t0 < MIN_POLL_MS) {
                    // Server fast-failed (401 / kernel down) instead of holding — back off.
                    failures++; delay(backoffFor(failures))
                } else {
                    failures = 0 // a full-window held poll that returned empty is healthy
                }
            }
        }
    }

    /**
     * Route a drained batch to the live UI when an Activity is foreground (the rich
     * Compose render + notify), else render headlessly (notification + ChatStore) —
     * the ALWAYS_ON-after-process-kill and backgrounded-with-stale-renderer cases.
     * Both paths dedupe on the `evt-<seq>` id, so the on-resume reload is consistent.
     */
    private fun renderEvents(events: List<ConsoleEvent>) {
        val r = renderer
        if ((context as? TitanApp)?.isForeground == true && r != null) {
            r(events)
            return
        }
        val deviceId = store.deviceId ?: return
        EventRenderer.render(context, events, chatSessionFor(deviceId))
    }

    private fun stopLoop() {
        if (loopJob == null) return
        loopJob?.cancel()
        loopJob = null
        // A final presence ping reflecting the now-background state (best-effort).
        signerProvider?.let { provider ->
            appScope.launch { provider()?.let { heartbeat(it) } }
        }
    }

    /** Fire one immediate heartbeat (best-effort) so a just-changed availability (RFP §7.3
     *  3b) lands now instead of on the next loop cycle. No-op if no signer is bound yet. */
    fun nudgeHeartbeat() {
        signerProvider?.let { provider ->
            appScope.launch { provider()?.let { heartbeat(it) } }
        }
    }

    private suspend fun heartbeat(key: DeviceKey) {
        val state = if (foreground) "foreground" else "background"
        // The Maker's declared availability rides every heartbeat (RFP §7.3 3b).
        val availability = ConnectionSettings(context).availability
        try {
            withContext(Dispatchers.IO) {
                client().heartbeat(key, state, store.eventCursor, batteryPct(),
                                   availability = availability)
            }
        } catch (_: Exception) { /* presence is best-effort */ }
    }

    /** Battery %, with a GrapheneOS-safe fallback: `BATTERY_PROPERTY_CAPACITY` returns
     *  null on the Maker's Pixel 7a, so fall back to the sticky ACTION_BATTERY_CHANGED
     *  broadcast (`level*100/scale`). Null only if neither source reports. */
    private fun batteryPct(): Int? {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }
            ?.let { return it }
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else null
    }

    /** Exponential backoff after consecutive drain failures, capped — prevents a tight
     *  loop while the kernel is down or the signing window has lapsed (no Activity). */
    private fun backoffFor(failures: Int): Long {
        val shift = (failures - 1).coerceIn(0, 5) // cap the shift so we never overflow
        return (BACKOFF_MS shl shift).coerceAtMost(MAX_BACKOFF_MS)
    }

    private fun client() =
        ConsoleClient(
            store.endpointUrl ?: TitanController.DEFAULT_DEV_ENDPOINT,
            AndroidHttpTransport(tlsPin = store.tlsPin),
        )

    companion object {
        /** Held long-poll window (s) — matches the server clamp (`events._MAX_WAIT_S`). */
        private const val LONGPOLL_WAIT = 25

        /** Back-off after a fast-failing drain (kernel down / 401) to avoid a tight loop. */
        private const val BACKOFF_MS = 3000L
        private const val MIN_POLL_MS = 2000L

        /** Ceiling for the escalating backoff (≈ the WorkManager cadence). */
        private const val MAX_BACKOFF_MS = 60_000L
    }
}
