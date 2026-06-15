package tech.iamtitan.app.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** POST /console/chat body — the agent derives the kernel `thread_id` from `session`. */
@Serializable
data class ChatRequestBody(
    val message: String,
    val session: String? = null,
)

/**
 * The kernel `PitchChatResponse` (owner path) as it arrives through the proxy,
 * plus the proxy's `{titan_down}` 503 envelope. All fields optional/lenient.
 */
@Serializable
data class ChatRaw(
    val response: String? = null,
    val declined: Boolean = false,
    @SerialName("decline_explanation") val declineExplanation: String? = null,
    @SerialName("titan_down") val titanDown: Boolean = false,
    val detail: String? = null,
    val error: String? = null,
)

/** What the UI renders. */
sealed interface ChatResult {
    data class Reply(val text: String) : ChatResult
    data class Declined(val reason: String) : ChatResult
    /** Kernel down — show "Titan resting" inline (AG2). */
    data object TitanResting : ChatResult
    data class Failed(val message: String) : ChatResult
}

/**
 * Lifecycle of a transcript turn. COMPLETE for finished lines; PENDING/FAILED
 * drive the background-reply machinery (a reply still being awaited survives
 * backgrounding / process death as a PENDING line, then is replaced in place
 * when it lands — see ChatStore + the foreground-reply service).
 */
@Serializable
enum class TurnStatus { COMPLETE, PENDING, FAILED }

/**
 * A line in the conversation transcript. Held as Compose UI state AND persisted
 * locally by `ChatStore` so history survives the process being killed. New
 * fields default so older persisted transcripts decode cleanly (WireJson is
 * lenient + encodes defaults).
 */
@Serializable
data class ChatTurn(
    val fromMaker: Boolean,
    val text: String,
    /** epoch millis when the line was created (0 = legacy/unknown). */
    val ts: Long = 0L,
    val status: TurnStatus = TurnStatus.COMPLETE,
    /** stable id so a PENDING reply can be replaced in place once it arrives. */
    val id: String = "",
    /** Channel-2 actionable buttons (RFP §7.3 3a) — non-empty only for a `type:"system"`
     *  turn; renders as an actionable card in the chat. */
    val actions: List<ChatAction> = emptyList(),
    /** The action id the Maker chose (null = not yet acted) — drives the in-card
     *  "✓ Acknowledged" confirmation after a tap. */
    val respondedAction: String? = null,
)

/** A Channel-2 action affordance persisted on a [ChatTurn] (RFP §7.3 3a). Mirror of
 *  `net.EventAction`, kept in `chat/` to avoid a net↔chat dependency cycle. */
@Serializable
data class ChatAction(val id: String, val label: String, val needsApp: Boolean = false)
