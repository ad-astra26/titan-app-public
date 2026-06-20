package tech.iamtitan.app

import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.iamtitan.app.chat.ChatAction
import tech.iamtitan.app.chat.ChatRepository
import tech.iamtitan.app.chat.ChatResult
import tech.iamtitan.app.chat.ChatTurn
import tech.iamtitan.app.chat.alertsSessionFor
import tech.iamtitan.app.crypto.DeviceKey
import tech.iamtitan.app.data.ChatStore
import tech.iamtitan.app.data.ConnectionSettings
import tech.iamtitan.app.data.LockMode
import tech.iamtitan.app.data.PairingStore
import tech.iamtitan.app.data.PresenceStore
import tech.iamtitan.app.data.SecuritySettings
import tech.iamtitan.app.presence.PresenceCollector
import tech.iamtitan.app.presence.PresenceMotion
import tech.iamtitan.app.work.PresenceWorker
import java.util.UUID
import tech.iamtitan.app.net.AndroidHttpTransport
import tech.iamtitan.app.net.BackupView
import tech.iamtitan.app.net.ConfigEntry
import tech.iamtitan.app.net.ConsoleClient
import tech.iamtitan.app.net.ConsoleEvent
import tech.iamtitan.app.net.EventAction
import tech.iamtitan.app.net.HostResources
import tech.iamtitan.app.net.JournalTail
import tech.iamtitan.app.net.MetabolismView
import tech.iamtitan.app.net.NervousSystem
import tech.iamtitan.app.net.SetConfigResult
import tech.iamtitan.app.net.AgentStatus
import tech.iamtitan.app.net.ProcessScan
import tech.iamtitan.app.net.TitanLiveness
import tech.iamtitan.app.notify.Notifier
import tech.iamtitan.app.service.TitanLinkService
import tech.iamtitan.app.pairing.SubmitRequest
import tech.iamtitan.app.pairing.code6
import tech.iamtitan.app.pairing.parsePairingPayload
import tech.iamtitan.app.ui.PairingUiState
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

enum class Screen { Pairing, Home, Chat, Alerts, ControlCenter, Diagnostics, Config, Advanced }

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
    private val connectionSettings = ConnectionSettings(context)
    private val presenceStore = PresenceStore(context)
    private val notifier = Notifier(context).apply { ensureChannels() }
    private val activityProvider = { activity }
    // The event-channel loop + tier machine is a process singleton — this
    // per-Activity controller only binds a renderer and drives lifecycle into it.
    private val connection = (activity.application as TitanApp).connection

    /** True when any Activity is started — drives "notify vs update live UI". */
    private fun appIsForeground(): Boolean = (context as? TitanApp)?.isForeground ?: true

    var screen by mutableStateOf(Screen.Pairing); private set
    var pairing by mutableStateOf<PairingUiState>(PairingUiState.NotPaired); private set
    val turns: SnapshotStateList<ChatTurn> = mutableStateListOf()
    // Channel-2 feed ( / ) — Titan's system/health/ops
    // messages live in their OWN timeline, separate from the conversational chat.
    val alerts: SnapshotStateList<ChatTurn> = mutableStateListOf()
    var unreadAlerts by mutableStateOf(0); private set
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

    // ── "Stay connected" opt-in ( — persistent always-on link) ──
    var alwaysConnected by mutableStateOf(connectionSettings.alwaysConnected); private set

    // ── Declared availability ( 3b — a hint Titan reasons about, not a mute) ──
    var availabilityState by mutableStateOf(connectionSettings.availability); private set

    // ──: in-app diagnostics + config console (read-only diag + config R/W). All
    // fetched over existing signed Console routes; null = not-yet-loaded / unreachable. ──
    var diagLoading by mutableStateOf(false); private set
    var diagStatus by mutableStateOf<TitanLiveness?>(null); private set
    var diagHost by mutableStateOf<HostResources?>(null); private set
    var diagNs by mutableStateOf<NervousSystem?>(null); private set
    var diagMetabolism by mutableStateOf<MetabolismView?>(null); private set
    var diagBackups by mutableStateOf<BackupView?>(null); private set
    var diagJournal by mutableStateOf<JournalTail?>(null); private set
    var configLoading by mutableStateOf(false); private set
    var configSections by mutableStateOf<List<String>>(emptyList()); private set
    var configEntries by mutableStateOf<List<ConfigEntry>>(emptyList()); private set

    // ──: advanced layered ops. Gated by [advancedOpsEnabled] (app-lock-gated
    // toggle, OFF by default); the privileged Console routes are independently device-authed. ──
    var advancedOpsEnabled by mutableStateOf(security.advancedOpsEnabled); private set
    var advLoading by mutableStateOf(false); private set
    var advScan by mutableStateOf<ProcessScan?>(null); private set
    var advAgentStatus by mutableStateOf<AgentStatus?>(null); private set
    var advBanner by mutableStateOf<String?>(null); private set

    // ──: presence opt-in. Per-sensor toggles → console-local store + signed
    // backend settings + the background sampler. All default OFF. ──
    var presenceLocation by mutableStateOf(presenceStore.locationEnabled); private set
    var presenceTime by mutableStateOf(presenceStore.timeEnabled); private set
    var presenceBattery by mutableStateOf(presenceStore.batteryEnabled); private set
    /** Set by MainActivity to request the runtime location grant when the Maker enables location. */
    var onRequestLocationPermission: (() -> Unit)? = null

    private var signer: DeviceKey? = null
    private var repo: ChatRepository? = null
    private var pendingCode6: String? = null

    private fun baseUrl(): String = store.endpointUrl ?: DEFAULT_DEV_ENDPOINT
    // Pinned TLS: the transport pins the QR's cert sha256 when present.
    private fun client() = ConsoleClient(baseUrl(), AndroidHttpTransport(tlsPin = store.tlsPin))

    init {
        DeviceKey.existing(context, store, activityProvider)?.let { key ->
            if (store.paired) {
                bindChat(key)
                // Rehydrate the transcript so history survives a process kill (the
                // "chat gone after hours" quirk). repo is set by bindChat above.
                repo?.session?.let { turns.addAll(chatStore.load(it)) }
                alertsSession()?.let {
                    alerts.addAll(chatStore.load(it))
                    unreadAlerts = (alerts.size - connectionSettings.seenAlertsCount)
                        .coerceAtLeast(0)
                }
                pairing = PairingUiState.Paired(store.label)
                screen = Screen.Home
                // Cold-start lock: every mode except OFF requires an unlock to begin.
                locked = lockMode != LockMode.OFF
            }
        }
    }

    private fun bindChat(key: DeviceKey) {
        signer = key
        repo = ChatRepository(client(), key)
        // Hand the singleton loop a fresh signer source + this Activity's renderer,
        // and seed the always-on flag so the tier is correct before any service callback.
        connection.bind(signer = { signer }, render = ::processEvents)
        connection.setAlwaysOn(connectionSettings.alwaysConnected)
    }

    /** A scanned/pasted QR payload → seal a key, submit, show the code to match. */
    fun onScanned(qrText: String) {
        val payload = parsePairingPayload(qrText) ?: run {
            pairing = PairingUiState.Error("That QR isn’t a Titan pairing code.")
            return
        }
        // / fail-closed: a remote QR MUST carry a TLS pin — never pair a
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
                    screen = Screen.Home
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
        connection.onSending(true) // keep the event loop held (ACTIVE_TASK) if backgrounded
        // The request runs on the process-lifetime appScope (not the Activity's),
        // and a short foreground service keeps the process un-frozen so a reply
        // that lands after the Maker backgrounds the app is NOT dropped (the
        // reported quirk). The biometric to sign happens here, while foregrounded.
        // Skip when always-connected: the persistent service already holds the line.
        if (!alwaysConnected) TitanLinkService.startShort(context)
        scope.launch {
            val result = try {
                withContext(Dispatchers.IO) { repository.send(text) }
            } catch (e: Exception) {
                ChatResult.Failed(e.message ?: "Network error.")
            }
            sending = false
            connection.onSending(false)
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
            if (!alwaysConnected) TitanLinkService.stop(context)
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
        // Re-sync the Alerts/Info timeline too (a headless delivery may have grown it) +
        // recompute the unread badge from what the Maker has actually seen.
        alertsSession()?.let { session ->
            val persisted = chatStore.load(session)
            if (persisted.size != alerts.size) {
                alerts.clear()
                alerts.addAll(persisted)
            }
            unreadAlerts = if (screen == Screen.Alerts) 0
            else (alerts.size - connectionSettings.seenAlertsCount).coerceAtLeast(0)
        }
        evaluateLockOnForeground()
        if (store.paired && !locked) {
            connection.onForeground()
            // Ensure the persistent anchor is running (started from foreground, as the
            // Android-12+ FGS-start rule requires). Idempotent re-foreground if already up.
            if (alwaysConnected) TitanLinkService.startPersistent(context)
        }
        // Foreground = live app; the motion trigger is a background-only optimization.
        PresenceMotion.cancel(context)
    }

    /** Activity stopped — note when, for the TIMER lock policy. */
    fun onBackground() {
        if (store.paired) lastBackgroundAt = System.currentTimeMillis()
        connection.onBackground()
        // Adaptive presence: while backgrounded with location shared, fire an
        // immediate upload the moment the Maker starts moving (then the worker fast-chains),
        // instead of waiting for the next 15-min periodic. Battery-light one-shot HW sensor.
        if (store.paired && presenceStore.locationEnabled &&
            PresenceCollector.hasLocationPermission(context)
        ) {
            PresenceMotion.arm(context) { PresenceWorker.enqueueFast(context, delayMinutes = 0) }
        }
    }

    // ── Event channel ──────────────────────
    /** Render drained events into the UI/notifications (deliver-once via the cursor +
     * a per-seq turn id). Unknown types are ignored (forward-compatible). */
    private fun processEvents(events: List<ConsoleEvent>) {
        for (e in events) {
            when (e.type) {
                // Channel 1 — conversational. Stays in the chat timeline.
                "message", "reply" -> {
                    val text = e.messageText() ?: continue
                    addBotTurnFromEvent(e.seq, text)
                    if (!appIsForeground()) {
                        if (e.urgency == "high") notifier.notifyUrgent(text) else notifier.notifyReply(text)
                    }
                }
                // Channel 2 — info/decisions. Live in the SEPARATE Alerts/Info timeline.
                "health" -> {
                    val up = e.healthUp()
                    resting = !up
                    val text = e.healthText() ?: if (up) "Titan recovered." else "Titan is down."
                    addAlertTurn(e.seq, text)
                    notifier.notifyHealth(up, text)
                }
                "system" -> {
                    val text = e.systemText() ?: continue
                    addAlertTurn(e.seq, text, e.systemActions())
                    notifier.notifySystem(text, e.systemActions(), e.seq)
                }
                else -> Unit
            }
        }
    }

    /** Append a Channel-2 item to the Alerts/Info timeline (deduped by seq), persist it, and
     * bump the unread badge unless the Maker is currently viewing Alerts. */
    private fun addAlertTurn(seq: Int, text: String, actions: List<EventAction> = emptyList()) {
        val id = "evt-$seq"
        if (alerts.any { it.id == id }) return
        alerts.add(
            ChatTurn(
                fromMaker = false, text = text, ts = nowMs(), id = id,
                actions = actions.map { ChatAction(it.id, it.label, it.needsApp) },
            ),
        )
        persistAlerts()
        if (screen != Screen.Alerts) unreadAlerts++
    }

    private fun alertsSession(): String? = store.deviceId?.let { alertsSessionFor(it) }
    private fun persistAlerts() { alertsSession()?.let { chatStore.save(it, alerts.toList()) } }

    // ── Home navigation ( — landing view + the two channels) ──
    fun goHome() { screen = Screen.Home }
    fun goChat() { screen = Screen.Chat }
    fun goAlerts() {
        screen = Screen.Alerts
        unreadAlerts = 0
        connectionSettings.seenAlertsCount = alerts.size  // headless-delivered alerts count too
    }

    // ── Titan Control Center hub (Diagnostics / Config / Advanced live under it) ──
    fun goControlCenter() { screen = Screen.ControlCenter }

    // ── Diagnostics + config console ──
    fun goDiagnostics() { screen = Screen.Diagnostics; refreshDiagnostics() }
    // Always re-fetch on open so the app reflects the paired Titan's CURRENT config values +
    // (friendly) help, never a stale cache (Maker: "reload value for T1 into app", 2026-06-20).
    fun goConfig() { screen = Screen.Config; refreshConfig() }

    /** Fetch every diagnostics readout in parallel-ish (sequential off-main calls on one signer).
     * Each is independently null-safe so a single unreachable readout doesn't blank the screen. */
    fun refreshDiagnostics() {
        val key = signer ?: return
        if (diagLoading) return
        diagLoading = true
        scope.launch {
            val c = client()
            val status = withContext(Dispatchers.IO) { c.titanStatus(key) }
            val host = withContext(Dispatchers.IO) { c.host(key) }
            val ns = withContext(Dispatchers.IO) { c.nervousSystem(key) }
            val metab = withContext(Dispatchers.IO) { c.metabolism(key) }
            val backups = withContext(Dispatchers.IO) { c.backups(key) }
            val journal = withContext(Dispatchers.IO) { c.journal(key, 80) }
            diagStatus = status
            diagHost = host
            diagNs = ns
            diagMetabolism = metab
            diagBackups = backups
            diagJournal = journal
            resting = status?.let { !it.up } ?: resting
            diagLoading = false
        }
    }

    /** Load all config keys (value + help + editable + source file). */
    fun refreshConfig() {
        val key = signer ?: return
        if (configLoading) return
        configLoading = true
        scope.launch {
            val list = withContext(Dispatchers.IO) { client().config(key) }
            if (list != null) {
                configSections = list.sections
                configEntries = list.entries
            }
            configLoading = false
        }
    }

    /** Write a config key (server is editable-guarded). On success re-fetch so the UI reflects
     * the persisted value; [onResult] surfaces ok/error to the screen for a toast/inline note.
     * The signing inherits the Maker's configured app-lock/biometric window (every command is
     * Ed25519-signed + device-gated) — no separate auth path. */
    fun saveConfig(dotted: String, value: String, onResult: (SetConfigResult) -> Unit) {
        val key = signer ?: return
        scope.launch {
            val r = try {
                withContext(Dispatchers.IO) { client().setConfig(key, dotted, value) }
            } catch (e: Exception) {
                SetConfigResult(ok = false, error = e.message ?: "Network error.")
            }
            if (r.ok) refreshConfig()
            onResult(r)
        }
    }

    // ──: advanced layered ops — privileged, signed; gated by advancedOpsEnabled ──
    fun goAdvanced() {
        if (!advancedOpsEnabled) return
        screen = Screen.Advanced
        advBanner = null
        refreshAdvanced()
    }

    /** Pull the module roster (for the L2 tree) + console self-status. */
    fun refreshAdvanced() {
        val key = signer ?: return
        if (advLoading) return
        advLoading = true
        scope.launch {
            val c = client()
            val ns = withContext(Dispatchers.IO) { c.nervousSystem(key) }
            val st = withContext(Dispatchers.IO) { c.agentStatus(key) }
            if (ns != null) diagNs = ns
            advAgentStatus = st
            advLoading = false
        }
    }

    /** Flip the advanced-mode gate ( decision-c). Enabling requires an app-lock re-auth
     * (DeviceKey.unlock); disabling is immediate. Persisted in SecuritySettings. */
    fun updateAdvancedOpsEnabled(on: Boolean) {
        if (!on) {
            advancedOpsEnabled = false
            security.advancedOpsEnabled = false
            if (screen == Screen.Advanced) screen = Screen.Home
            return
        }
        val key = signer ?: return
        scope.launch {
            if (key.unlock()) {
                advancedOpsEnabled = true
                security.advancedOpsEnabled = true
            }
        }
    }

    /** L2 worker op (reload | restart | enable) → kernel admin via the Console proxy. */
    fun opModule(action: String, name: String) {
        val key = signer ?: return
        advBanner = "$action $name…"
        scope.launch {
            val r = try { withContext(Dispatchers.IO) { client().moduleOp(key, action, name) } }
                catch (_: Exception) { null }
            advBanner = when {
                r == null -> "⚠ $action $name: network error"
                r.succeeded -> "✓ $action $name"
                else -> "⚠ $action $name: ${r.message}"
            }
            if (r?.succeeded == true) refreshAdvanced()
        }
    }

    /** L3 zero-downtime api-layer reload. */
    fun opReloadApi() {
        val key = signer ?: return
        advBanner = "Reloading API…"
        scope.launch {
            val r = try { withContext(Dispatchers.IO) { client().reloadApi(key) } } catch (_: Exception) { null }
            advBanner = when {
                r == null -> "⚠ reload-api: network error"
                r.succeeded -> "✓ API reloaded"
                else -> "⚠ reload-api: ${r.message}"
            }
        }
    }

    /** L0/L1 fallback — the existing signed full restart (dreaming-aware server-side). */
    fun opFullRestart() {
        val key = signer ?: return
        advBanner = "Restarting Titan…"
        scope.launch {
            val ok = try { withContext(Dispatchers.IO) { client().restart(key) } } catch (_: Exception) { false }
            advBanner = if (ok) "✓ Titan restart requested" else "⚠ couldn't reach the Console to restart"
            if (ok) resting = false
        }
    }

    /** Host VPS reboot — primary device + typed phrase (server-enforced). */
    fun opReboot(phrase: String) {
        val key = signer ?: return
        advBanner = "Rebooting VPS…"
        scope.launch {
            val r = try { withContext(Dispatchers.IO) { client().reboot(key, phrase) } } catch (_: Exception) { null }
            advBanner = when {
                r == null -> "⚠ reboot: network error"
                r.rebooting -> "✓ VPS rebooting — reconnecting…"
                else -> "⚠ reboot: ${r.error ?: "refused"}"
            }
        }
    }

    /** Dry-run scan for orphaned helper processes (allow-listed, fail-closed server-side). */
    fun opScanProcesses() {
        val key = signer ?: return
        advBanner = "Scanning…"
        scope.launch {
            val scan = try { withContext(Dispatchers.IO) { client().scanProcesses(key) } } catch (_: Exception) { null }
            advScan = scan
            advBanner = if (scan == null) "⚠ scan failed" else "Scan: ${scan.reapable.size} reapable, ${scan.zombies.size} zombie"
        }
    }

    /** Reap the chosen orphan PIDs (re-classified server-side at kill time). */
    fun opReap(pids: List<Int>) {
        val key = signer ?: return
        advBanner = "Reaping ${pids.size}…"
        scope.launch {
            val r = try { withContext(Dispatchers.IO) { client().reapProcesses(key, pids) } } catch (_: Exception) { null }
            val rescan = try { withContext(Dispatchers.IO) { client().scanProcesses(key) } } catch (_: Exception) { null }
            if (rescan != null) advScan = rescan
            advBanner = if (r == null) "⚠ reap failed" else "✓ reaped ${r.killed}/${r.requested}"
        }
    }

    /** Prune the devnet Arweave cache (keep-newest-5). [confirm]=false is a dry run. */
    fun opPrune(confirm: Boolean) {
        val key = signer ?: return
        advBanner = if (confirm) "Pruning…" else "Checking…"
        scope.launch {
            val r = try { withContext(Dispatchers.IO) { client().pruneArweaveDevnet(key, 5, confirm) } }
                catch (_: Exception) { null }
            advBanner = when {
                r == null -> "⚠ prune failed"
                !r.exists -> "No devnet cache dir."
                confirm -> "✓ pruned ${r.removedBytes / 1_048_576}MB · kept ${r.kept}"
                else -> "Would free ${r.reclaimableBytes / 1_048_576}MB (${r.kept} kept)"
            }
        }
    }

    /** In-app tap on a system card's action button ( 3a): sign + send, mark the
     * card "✓ Acknowledged", and clear the shade notification. */
    fun onAction(seq: Int, actionId: String, label: String) {
        onRespondRequested(seq, actionId, label)
    }

    /** Reflect a chosen action on the in-memory + persisted alert card (drives the card ack). */
    private fun markRespondedInMemory(seq: Int, actionId: String) {
        val i = alerts.indexOfFirst { it.id == "evt-$seq" }
        if (i >= 0 && alerts[i].respondedAction == null) {
            alerts[i] = alerts[i].copy(respondedAction = actionId)
            persistAlerts()
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

    /** A Channel-2 action button completed via the app ( — a `needs_app` action, or
     * a headless tap whose key-window had lapsed). Signs + posts the response; the inbox is
     * durable so this is best-effort. */
    fun onRespondRequested(seq: Int, actionId: String, label: String = actionId) {
        val key = signer ?: return
        markRespondedInMemory(seq, actionId)      // optimistic card ack
        notifier.ackSystem(seq, label)            // clear/ack the shade notification
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    client().respond(key, inReplyTo = if (seq >= 0) seq else null,
                                     kind = "action", actionId = actionId)
                }
            } catch (_: Exception) { /* durable inbox + best-effort */ }
        }
    }

    /** Send a feedback chip on a Titan turn ( 3b). [seq] is parsed from the turn id
     * ("evt-<seq>"); a turn with no seq still posts (in_reply_to=null). */
    fun onFeedback(seq: Int?, reaction: String? = null, stars: Int? = null) {
        val key = signer ?: return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    client().respond(key, inReplyTo = seq, kind = "feedback",
                                     reaction = reaction, stars = stars)
                }
            } catch (_: Exception) { /* durable inbox + best-effort */ }
        }
    }

    /** The Maker's declared availability ( 3b). Persisted; ridden on the next
     * heartbeat by [ConnectionManager]. Triggers an immediate heartbeat so it lands now. */
    fun setAvailability(value: String) {
        connectionSettings.availability = value
        availabilityState = value
        connection.nudgeHeartbeat()
    }

    /** Home-screen quick chip: cycle available → busy → dnd → available. */
    fun cycleAvailability() = setAvailability(
        when (availabilityState) {
            "available" -> "busy"
            "busy" -> "dnd"
            else -> "available"
        },
    )

    private fun addBotTurnFromEvent(seq: Int, text: String) {
        val id = "evt-$seq"
        if (turns.any { it.id == id }) return
        addTurn(ChatTurn(fromMaker = false, text = text, ts = nowMs(), id = id))
    }

    /** Re-lock on return-from-background per the policy. Cold start is handled in
     * init (lastBackgroundAt==0 ⇒ skip; the initial lock is already set). */
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
     * the time-bound signing window, so the next chat turn doesn't re-prompt). */
    fun unlock() {
        val key = signer ?: run { locked = false; return }
        scope.launch {
            if (key.unlock()) {
                locked = false
                if (store.paired) connection.onForeground() // event loop is gated off while locked
            }
        }
    }

    fun openSettings() { showSettings = true }
    fun closeSettings() { showSettings = false }

    // ── Presence opt-in ( / ) ──
    fun togglePresenceLocation(on: Boolean) {
        presenceStore.locationEnabled = on
        presenceLocation = on
        // Enabling location needs the OS grant; request it (best-effort — if denied, the
        // collector simply omits location, so the rest of presence still works).
        if (on && !PresenceCollector.hasLocationPermission(context)) {
            onRequestLocationPermission?.invoke()
        }
        syncPresence()
    }

    fun togglePresenceTime(on: Boolean) {
        presenceStore.timeEnabled = on; presenceTime = on; syncPresence()
    }

    fun togglePresenceBattery(on: Boolean) {
        presenceStore.batteryEnabled = on; presenceBattery = on; syncPresence()
    }

    /** Reconcile the background sampler + push the opt-in to the backend (which gates server-side
     * too — defence in depth). Schedule while any sensor is on; cancel when all are off. */
    private fun syncPresence() {
        if (presenceStore.anyEnabled) PresenceWorker.schedule(context, presenceStore.cadenceMinutes)
        else PresenceWorker.cancel(context)
        val key = signer ?: return
        scope.launch {
            try {
                withContext(Dispatchers.IO) { client().setPresenceSettings(key, presenceStore.settings()) }
            } catch (_: Exception) { /* local store is the app's source of truth; retry next toggle */ }
        }
    }

    fun updateLockMode(mode: LockMode) {
        lockMode = mode
        security.lockMode = mode
    }

    fun updateLockTimerMinutes(minutes: Int) {
        lockTimerMinutes = minutes
        security.lockTimerMinutes = minutes
    }

    /** Toggle the "Stay connected" persistent link (). Started from the
     * foreground (Settings), satisfying the Android-12+ FGS-start rule. */
    fun updateAlwaysConnected(on: Boolean) {
        alwaysConnected = on
        connectionSettings.alwaysConnected = on
        if (on) {
            TitanLinkService.startPersistent(context)
            connection.setAlwaysOn(true)
        } else {
            TitanLinkService.stop(context)
            connection.setAlwaysOn(false)
        }
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
    }
}
