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

/** A line in the conversation transcript (UI state). */
data class ChatTurn(val fromMaker: Boolean, val text: String)
