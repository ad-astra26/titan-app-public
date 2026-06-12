package tech.iamtitan.app

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.iamtitan.app.chat.ChatRepository
import tech.iamtitan.app.chat.ChatResult
import tech.iamtitan.app.chat.ChatTurn
import tech.iamtitan.app.crypto.DeviceKey
import tech.iamtitan.app.data.ChatStore
import tech.iamtitan.app.data.LockMode
import tech.iamtitan.app.data.PairingStore
import tech.iamtitan.app.data.SecuritySettings
import java.util.UUID
import tech.iamtitan.app.net.AndroidHttpTransport
import tech.iamtitan.app.net.ConsoleClient
import tech.iamtitan.app.net.ConsoleEvent
import tech.iamtitan.app.notify.Notifier
import tech.iamtitan.app.service.TitanReplyService
import tech.iamtitan.app.work.EventPollWorker
import tech.iamtitan.app.pairing.SubmitRequest
import tech.iamtitan.app.pairing.code6
import tech.iamtitan.app.pairing.parsePairingPayload
import tech.iamtitan.app.ui.PairingUiState
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

enum class Screen { Pairing, Chat }

/**
 * Drives the M1 "Hello Titan" slice: QR → keygen (sealed) → submit → code-match
 * confirm → signed chat. Holds Compose snapshot state read by [TitanApp]; all I/O
 * is off the main thread, biometrics on it.
 */
@OptIn(ExperimentalEncodingApi::class)
class TitanController(
    private val activity: FragmentActivity,
    private val scope: CoroutineScope,
) {
    private val context = activity.applicationContext
    private val store = PairingStore(context)
    private val chatStore = ChatStore(context)
    private val security = SecuritySettings(context)
    private val notifier = Notifier(context).apply { ensureChannels() }
    private val activityProvider = { activity }

    /** True when any Activity is started — drives "notify vs update live UI". */
    private fun appIsForeground(): Boolean = (context as? TitanApp)?.isForeground ?: true

    var screen by mutableStateOf(Screen.Pairing); private set
    var pairing by mutableStateOf<PairingUiState>(PairingUiState.NotPaired); private set
    val turns: SnapshotStateList<ChatTurn> = mutableStateListOf()
    var draft by mutableStateOf(""); private set
    var sending by mutableStateOf(false); private set
    var resting by mutableStateOf(false); private set
    val titanLabel = "Titan"

    // ── App lock (Settings-driven UX gate; see SecuritySettings + DeviceKey window) ──
    var locked by mutableStateOf(false); private set
    var showSettings by mutableStateOf(false); private set
    var lockMode by mutableStateOf(security.lockMode); private set
    var lockTimerMinutes by mutableStateOf(security.lockTimerMinutes); private set
    private var lastBackgroundAt = 0L

    private var signer: DeviceKey? = null
    private var repo: ChatRepository? = null
    private var pendingCode6: String? = null
    private var eventLoopJob: Job? = null

    private fun baseUrl(): String = store.endpointUrl ?: DEFAULT_DEV_ENDPOINT
    // Pinned TLS (AG-TLS): the transport pins the QR's cert sha256 when present.
    private fun client() = ConsoleClient(baseUrl(), AndroidHttpTransport(tlsPin = store.tlsPin))

    init {
        DeviceKey.existing(context, store, activityProvider)?.let { key ->
            if (store.paired) {
                bindChat(key)
                // Rehydrate the transcript so history survives a process kill (the
                // "chat gone after hours" quirk). repo is set by bindChat above.
                repo?.session?.let { turns.addAll(chatStore.load(it)) }
                pairing = PairingUiState.Paired(store.label)
                screen = Screen.Chat
                // Cold-start lock: every mode except OFF requires an unlock to begin.
                locked = lockMode != LockMode.OFF
            }
        }
    }

    private fun bindChat(key: DeviceKey) {
        signer = key
        repo = ChatRepository(client(), key)
    }

    /** A scanned/pasted QR payload → seal a key, submit, show the code to match. */
    fun onScanned(qrText: String) {
        val payload = parsePairingPayload(qrText) ?: run {
            pairing = PairingUiState.Error("That QR isn’t a Titan pairing code.")
            return
        }
        // AG-MODE/AG-TLS fail-closed: a remote QR MUST carry a TLS pin — never pair a
        // remote Titan over an unpinned (sniffable/MITM-able) channel.
        if (payload.mode == "remote" && payload.serverTlsPin.isNullOrBlank()) {
            pairing = PairingUiState.Error(
                "This remote pairing QR is missing its security pin — refusing to connect.",
            )
            return
        }
        payload.endpointUrl?.let { store.endpointUrl = it }
        store.tlsPin = payload.serverTlsPin
        store.mode = payload.mode
        pairing = PairingUiState.Working("Generating your device key…")
        scope.launch {
            try {
                val key = DeviceKey.create(
                    context, store, label = Build.MODEL ?: "phone", activityProvider,
                )
                bindChat(key)
                val code = code6(Base64.decode(payload.pairingToken), key.publicKey)
                pendingCode6 = code
                val resp = withContext(Dispatchers.IO) {
                    client().submitDevice(
                        SubmitRequest(
                            pairingToken = payload.pairingToken,
                            deviceId = key.deviceId,
                            devicePubkey = Base64.encode(key.publicKey),
                            fingerprint = DeviceKey.fingerprint(context),
                            label = store.label,
                        ),
                    )
                }
                pairing = if (resp.ok || resp.awaitingConfirm) {
                    PairingUiState.AwaitingConfirm(code, store.label)
                } else {
                    PairingUiState.Error(resp.error ?: "Pairing was rejected — try a fresh QR.")
                }
            } catch (e: Exception) {
                pairing = PairingUiState.Error(e.message ?: "Pairing failed.")
            }
        }
    }

    /** Maker tapped "I've entered the code" → one signed self-check (one biometric). */
    fun onConfirmed() {
        val key = signer ?: return
        pairing = PairingUiState.Working("Confirming…")
        scope.launch {
            try {
                val me = withContext(Dispatchers.IO) { client().whoAmI(key) }
                if (me != null) {
                    store.paired = true
                    pairing = PairingUiState.Paired(store.label)
                    screen = Screen.Chat
                } else {
                    pairing = PairingUiState.Error(
                        "Not confirmed yet. Enter the code in your Command Center, then tap “Try again”.",
                    )
                }
            } catch (e: Exception) {
                pairing = PairingUiState.Error(e.message ?: "Couldn’t confirm yet.")
            }
        }
    }

    fun onRetry() {
        pairing = pendingCode6?.let { PairingUiState.AwaitingConfirm(it, store.label) }
            ?: PairingUiState.NotPaired
    }

    fun onDraftChange(value: String) { draft = value }

    fun onSend() {
        val repository = repo ?: return
        val text = draft.trim()
        if (text.isEmpty() || sending) return
        addTurn(ChatTurn(fromMaker = true, text = text, ts = nowMs(), id = newId()))
        draft = ""
        sending = true
        // The request runs on the process-lifetime appScope (not the Activity's),
        // and a short foreground service keeps the process un-frozen so a reply
        // that lands after the Maker backgrounds the app is NOT dropped (the
        // reported quirk). The biometric to sign happens here, while foregrounded.
        TitanReplyService.start(context)
        scope.launch {
            val result = try {
                withContext(Dispatchers.IO) { repository.send(text) }
            } catch (e: Exception) {
                ChatResult.Failed(e.message ?: "Network error.")
            }
            sending = false
            val replyText = when (result) {
                is ChatResult.Reply -> { resting = false; result.text }
                is ChatResult.Declined -> { resting = false; result.reason }
                ChatResult.TitanResting -> {
                    resting = true
                    "I’m resting right now. Wake me from the Console when you need me."
                }
                is ChatResult.Failed -> "⚠ ${result.message}"
            }
            addBotTurn(replyText)
            // Backgrounded when the reply landed ⇒ surface it as a native notification;
            // it's already persisted, so opening the app shows it in the transcript too.
            if (!appIsForeground()) notifier.notifyReply(replyText)
            TitanReplyService.stop(context)
        }
    }

    /**
     * Re-sync the in-memory transcript from the persisted store on app resume —
     * the backstop that shows a reply delivered by the appScope request after the
     * Activity was recreated (e.g. a rotation mid-reply). Idempotent: clear+reload
     * from the single source of truth (ChatStore).
     */
    fun onAppResume() {
        repo?.session?.let { session ->
            val persisted = chatStore.load(session)
            if (persisted.size != turns.size) {
                turns.clear()
                turns.addAll(persisted)
            }
        }
        evaluateLockOnForeground()
        if (store.paired && !locked) startEventLoop()
    }

    /** Activity stopped — note when, for the TIMER lock policy. */
    fun onBackground() {
        if (store.paired) lastBackgroundAt = System.currentTimeMillis()
        stopEventLoop()
    }

    // ── Event channel (RFP_titan_app_event_channel Phase 1) ────────────────────
    /**
     * Foreground drain: a held long-poll on `GET /console/events` while the app is
     * open (near-instant delivery). Runs on the process-lifetime [scope] so it isn't
     * torn by an Activity recreation; cancelled on background, where WorkManager
     * ([EventPollWorker]) takes over the ~15-min cadence. Idempotent.
     */
    fun startEventLoop() {
        val key = signer ?: return
        if (!store.paired || eventLoopJob?.isActive == true) return
        EventPollWorker.schedule(context) // background cadence (KEEP-idempotent)
        eventLoopJob = scope.launch {
            heartbeat(key, "foreground")
            while (isActive) {
                val t0 = System.currentTimeMillis()
                val resp = try {
                    withContext(Dispatchers.IO) {
                        client().events(key, wait = LONGPOLL_WAIT, since = store.eventCursor)
                    }
                } catch (_: Exception) {
                    delay(BACKOFF_MS); continue
                }
                if (resp.events.isNotEmpty()) {
                    processEvents(resp.events)
                    store.eventCursor = resp.cursor
                    heartbeat(key, "foreground") // carries ack_cursor = the new cursor
                } else if (System.currentTimeMillis() - t0 < MIN_POLL_MS) {
                    // Server fast-failed (401 / kernel down) instead of holding — back off.
                    delay(BACKOFF_MS)
                }
            }
        }
    }

    fun stopEventLoop() {
        eventLoopJob?.cancel()
        eventLoopJob = null
        signer?.let { key -> scope.launch { heartbeat(key, "background") } }
    }

    /** Render drained events into the UI/notifications (deliver-once via the cursor +
     *  a per-seq turn id). Unknown types are ignored (forward-compatible). */
    private fun processEvents(events: List<ConsoleEvent>) {
        for (e in events) {
            when (e.type) {
                "message", "reply" -> {
                    val text = e.messageText() ?: continue
                    addBotTurnFromEvent(e.seq, text)
                    if (!appIsForeground()) notifier.notifyReply(text)
                }
                "health" -> {
                    val up = e.healthUp()
                    resting = !up
                    notifier.notifyHealth(
                        up, e.healthText() ?: if (up) "Titan recovered." else "Titan is down.",
                    )
                }
                else -> Unit
            }
        }
    }

    /** The health notification's Restart action opened the app → run the signed restart. */
    fun onRestartRequested() {
        val key = signer ?: return
        scope.launch {
            val ok = try {
                withContext(Dispatchers.IO) { client().restart(key) }
            } catch (_: Exception) { false }
            if (ok) resting = false
            addBotTurn(
                if (ok) "Restarting Titan… give it a moment to wake."
                else "⚠ Couldn't reach the Console to restart.",
            )
        }
    }

    private suspend fun heartbeat(key: DeviceKey, state: String) {
        try {
            withContext(Dispatchers.IO) {
                client().heartbeat(key, state, store.eventCursor, batteryPct())
            }
        } catch (_: Exception) { /* presence is best-effort */ }
    }

    private fun batteryPct(): Int? =
        (context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }

    private fun addBotTurnFromEvent(seq: Int, text: String) {
        val id = "evt-$seq"
        if (turns.any { it.id == id }) return
        addTurn(ChatTurn(fromMaker = false, text = text, ts = nowMs(), id = id))
    }

    /** Re-lock on return-from-background per the policy. Cold start is handled in
     *  init (lastBackgroundAt==0 ⇒ skip; the initial lock is already set). */
    private fun evaluateLockOnForeground() {
        if (!store.paired || lastBackgroundAt == 0L) return
        when (lockMode) {
            LockMode.OFF, LockMode.ON_LAUNCH -> Unit
            LockMode.IMMEDIATE -> locked = true
            LockMode.TIMER ->
                if (System.currentTimeMillis() - lastBackgroundAt >= lockTimerMinutes * 60_000L) {
                    locked = true
                }
        }
    }

    /** Dismiss the lock overlay via one biometric/credential auth (which also opens
     *  the time-bound signing window, so the next chat turn doesn't re-prompt). */
    fun unlock() {
        val key = signer ?: run { locked = false; return }
        scope.launch {
            if (key.unlock()) {
                locked = false
                if (store.paired) startEventLoop() // gated off while locked
            }
        }
    }

    fun openSettings() { showSettings = true }
    fun closeSettings() { showSettings = false }

    fun updateLockMode(mode: LockMode) {
        lockMode = mode
        security.lockMode = mode
    }

    fun updateLockTimerMinutes(minutes: Int) {
        lockTimerMinutes = minutes
        security.lockTimerMinutes = minutes
    }

    /** Append a turn and persist the transcript (survives a process kill). */
    private fun addTurn(turn: ChatTurn) {
        turns.add(turn)
        persist()
    }

    private fun addBotTurn(text: String) =
        addTurn(ChatTurn(fromMaker = false, text = text, ts = nowMs(), id = newId()))

    private fun persist() {
        repo?.session?.let { chatStore.save(it, turns.toList()) }
    }

    private fun nowMs(): Long = System.currentTimeMillis()

    private fun newId(): String = UUID.randomUUID().toString()

    companion object {
        /** Emulator → host loopback. Real phones get the endpoint from the QR (Tailscale). */
        const val DEFAULT_DEV_ENDPOINT = "http://10.0.2.2:7799"

        /** Held long-poll window (s) — matches the server clamp (`events._MAX_WAIT_S`). */
        private const val LONGPOLL_WAIT = 25

        /** Back-off after a fast-failing drain (kernel down / 401) to avoid a tight loop. */
        private const val BACKOFF_MS = 3000L
        private const val MIN_POLL_MS = 2000L
    }
}
