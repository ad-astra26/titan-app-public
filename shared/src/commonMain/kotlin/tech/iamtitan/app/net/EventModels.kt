package tech.iamtitan.app.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * One event drained from the Console Agent's per-device queue
 * (RFP_titan_app_event_channel §1.2). [payload] is type-dependent JSON
 * (`{text}` for a message, `{up, why, text}` for a health event), kept as a raw
 * [JsonElement] so the wire stays forward-compatible — unknown types are ignored,
 * never crash. Field accessors are null-safe over a malformed payload.
 */
@Serializable
data class ConsoleEvent(
    val seq: Int = 0,
    val type: String = "",
    val payload: JsonElement? = null,
    val urgency: String = "normal",
    val ts: Double = 0.0,
    @SerialName("dedupe_key") val dedupeKey: String? = null,
) {
    private fun prim(name: String): JsonPrimitive? =
        (payload as? JsonObject)?.get(name) as? JsonPrimitive

    /** Text of a `message`/`reply` event (the chat line), or null. */
    fun messageText(): String? = prim("text")?.contentOrNull

    /** Human string of a `health` event, or null. */
    fun healthText(): String? = prim("text")?.contentOrNull

    /** A `health` event's up/down flag (default true if absent). */
    fun healthUp(): Boolean = prim("up")?.booleanOrNull ?: true
}

/** The `GET /console/events` drain response: pending events + the new cursor. */
@Serializable
data class EventsResponse(
    val events: List<ConsoleEvent> = emptyList(),
    val cursor: Int = 0,
)

/** `POST /console/app/heartbeat` body — presence (+ the cursor the phone has consumed). */
@Serializable
data class HeartbeatBody(
    val state: String,
    @SerialName("ack_cursor") val ackCursor: Int? = null,
    val battery: Int? = null,
)
