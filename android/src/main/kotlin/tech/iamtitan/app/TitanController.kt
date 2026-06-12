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
import tech.iamtitan.app.chat.ChatRepository
import tech.iamtitan.app.chat.ChatResult
import tech.iamtitan.app.chat.ChatTurn
import tech.iamtitan.app.crypto.DeviceKey
import tech.iamtitan.app.data.ChatStore
import tech.iamtitan.app.data.PairingStore
import java.util.UUID
import tech.iamtitan.app.net.AndroidHttpTransport
import tech.iamtitan.app.net.ConsoleClient
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
    private val activityProvider = { activity }

    var screen by mutableStateOf(Screen.Pairing); private set
    var pairing by mutableStateOf<PairingUiState>(PairingUiState.NotPaired); private set
    val turns: SnapshotStateList<ChatTurn> = mutableStateListOf()
    var draft by mutableStateOf(""); private set
    var sending by mutableStateOf(false); private set
    var resting by mutableStateOf(false); private set
    val titanLabel = "Titan"

    private var signer: DeviceKey? = null
    private var repo: ChatRepository? = null
    private var pendingCode6: String? = null

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
        scope.launch {
            val result = try {
                withContext(Dispatchers.IO) { repository.send(text) }
            } catch (e: Exception) {
                ChatResult.Failed(e.message ?: "Network error.")
            }
            sending = false
            when (result) {
                is ChatResult.Reply -> { resting = false; addBotTurn(result.text) }
                is ChatResult.Declined -> { resting = false; addBotTurn(result.reason) }
                ChatResult.TitanResting -> {
                    resting = true
                    addBotTurn("I’m resting right now. Wake me from the Console when you need me.")
                }
                is ChatResult.Failed -> addBotTurn("⚠ ${result.message}")
            }
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
