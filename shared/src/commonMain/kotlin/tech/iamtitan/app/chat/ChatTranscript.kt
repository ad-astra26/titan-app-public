package tech.iamtitan.app.chat

import kotlinx.serialization.builtins.ListSerializer
import tech.iamtitan.app.pairing.WireJson

/**
 * The transcript (de)serialization seam used by the platform `ChatStore`. Lives
 * in `shared` so the JSON contract is one place + cross-language testable, while
 * the platform store owns only file I/O. Reuses [WireJson] (lenient +
 * encodeDefaults) → an older/newer build's transcript still decodes.
 */
private val TRANSCRIPT = ListSerializer(ChatTurn.serializer())

/** Encode a transcript for local persistence. */
fun encodeTranscript(turns: List<ChatTurn>): String =
    WireJson.encodeToString(TRANSCRIPT, turns)

/** Decode a persisted transcript; empty list on malformed/legacy-unreadable input. */
fun decodeTranscript(text: String): List<ChatTurn> =
    runCatching { WireJson.decodeFromString(TRANSCRIPT, text) }.getOrElse { emptyList() }
