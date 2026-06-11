package tech.iamtitan.app.chat

import tech.iamtitan.app.net.ConsoleClient
import tech.iamtitan.app.net.RequestSigner

/**
 * Thin chat surface over [ConsoleClient] (SPEC §1.2b). Owns the stable `session`
 * (the kernel thread id; ≥8 chars so the agent never has to pad it) and forwards
 * each turn through the signed `/console/chat` path. Transcript state is the UI's.
 */
class ChatRepository(
    private val client: ConsoleClient,
    private val signer: RequestSigner,
    /** Stable per-device thread so the owner conversation is continuous. */
    val session: String = "console-${signer.deviceId}".take(64).padEnd(8, '0'),
) {
    suspend fun send(message: String): ChatResult {
        val trimmed = message.trim()
        if (trimmed.isEmpty()) return ChatResult.Failed("Say something first.")
        return client.chat(signer, trimmed, session)
    }
}
