package tech.iamtitan.app.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
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

    /** Text of a `system` event (RFP §7.3 3a), or null. */
    fun systemText(): String? = prim("text")?.contentOrNull

    /** Actionable buttons of a `system` event (RFP §7.3 3a). Empty if none/malformed —
     *  forward-compatible (an action missing an id/label is skipped, never crashes). */
    fun systemActions(): List<EventAction> {
        val arr = (payload as? JsonObject)?.get("actions") as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val label = (o["label"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val needsApp = (o["needs_app"] as? JsonPrimitive)?.booleanOrNull ?: false
            EventAction(id, label, needsApp)
        }
    }
}

/** One Channel-2 action affordance (RFP §7.3 3a). [needsApp] high-stakes actions open the
 *  app to complete; low-stakes ones are signed + POSTed headlessly by the ResponseReceiver. */
data class EventAction(val id: String, val label: String, val needsApp: Boolean = false)

/** The `GET /console/events` drain response: pending events + the new cursor. */
@Serializable
data class EventsResponse(
    val events: List<ConsoleEvent> = emptyList(),
    val cursor: Int = 0,
)

/** `POST /console/app/heartbeat` body — presence (+ the cursor the phone has consumed).
 *  [availability] is the Maker's declared status (RFP §7.3 3b: available/busy/dnd) — a
 *  transport signal Titan *reasons* about (missions P4), never a coded mute. Omitted (null)
 *  ⇒ the server keeps the default "available". */
@Serializable
data class HeartbeatBody(
    val state: String,
    @SerialName("ack_cursor") val ackCursor: Int? = null,
    val battery: Int? = null,
    val availability: String? = null,
    @SerialName("availability_until") val availabilityUntil: Double? = null,
)

/** `POST /console/events/respond` body — a Channel-2 action tap or a feedback chip
 *  (RFP §7.3). [kind] = "action" (with [actionId]) or "feedback" (with [reaction]/[stars]).
 *  [inReplyTo] is the originating event's seq. */
@Serializable
data class RespondBody(
    @SerialName("in_reply_to") val inReplyTo: Int? = null,
    val kind: String,
    @SerialName("action_id") val actionId: String? = null,
    val reaction: String? = null,
    val stars: Int? = null,
)
