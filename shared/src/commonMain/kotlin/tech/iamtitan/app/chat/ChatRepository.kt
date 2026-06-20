package tech.iamtitan.app.chat

import tech.iamtitan.app.net.ConsoleClient
import tech.iamtitan.app.net.RequestSigner

/**
 * Thin chat surface over [ConsoleClient] (SPEC). Owns the stable `session`
 * (the kernel thread id; ≥8 chars so the agent never has to pad it) and forwards
 * each turn through the signed `/console/chat` path. Transcript state is the UI's.
 */
/**
 * The stable per-device chat thread id (the kernel thread id; ≥8 chars). A top-level
 * fn so the live repo and the background event worker derive the SAME session — a
 * Titan-initiated message persists to the same transcript the chat screen reads.
 */
fun chatSessionFor(deviceId: String): String =
    "console-$deviceId".take(64).padEnd(8, '0')

/** The Channel-2 (Alerts/Info) timeline session for a device — a separate transcript from
 * the conversational chat ( / ). All paths (controller, the
 * headless EventRenderer, the ResponseReceiver) MUST derive it the same way. */
fun alertsSessionFor(deviceId: String): String =
    "${chatSessionFor(deviceId)}__alerts"

class ChatRepository(
    private val client: ConsoleClient,
    private val signer: RequestSigner,
    /** Stable per-device thread so the owner conversation is continuous. */
    val session: String = chatSessionFor(signer.deviceId),
) {
    suspend fun send(message: String): ChatResult {
        val trimmed = message.trim()
        if (trimmed.isEmpty()) return ChatResult.Failed("Say something first.")
        return client.chat(signer, trimmed, session)
    }
}
