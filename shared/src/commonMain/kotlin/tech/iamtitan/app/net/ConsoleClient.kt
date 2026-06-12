package tech.iamtitan.app.net

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import tech.iamtitan.app.chat.ChatRaw
import tech.iamtitan.app.chat.ChatRequestBody
import tech.iamtitan.app.chat.ChatResult
import tech.iamtitan.app.crypto.sha256
import tech.iamtitan.app.pairing.DeviceRecord
import tech.iamtitan.app.pairing.SubmitRequest
import tech.iamtitan.app.pairing.SubmitResponse
import tech.iamtitan.app.pairing.WireJson

/**
 * The single sanctioned client of the Console Agent (SPEC §2). It builds the
 * AG4-signed wire — `X-Device-Id` / `X-Timestamp` / `X-Signature` over
 * `method\npath\nts\nsha256hex(body)` — and never invents routes.
 *
 * Pure over an injected [HttpTransport] + [RequestSigner], so the full signing
 * path is unit-tested in `commonTest` against the pinned Python vectors.
 *
 * @param baseUrl e.g. `http://10.0.2.2:7799` (emulator→localhost) or the tailnet URL.
 */
@OptIn(ExperimentalEncodingApi::class)
class ConsoleClient(
    private val baseUrl: String,
    private val transport: HttpTransport,
) {
    private val base = baseUrl.trimEnd('/')

    /**
     * Pairing bootstrap (SPEC §3 step 3) — POST /console/pair/submit. UNSIGNED on
     * purpose: this is the pre-identity handshake, gated server-side by the
     * single-use pairing token (`pairing.submit_device`), not the device key.
     */
    suspend fun submitDevice(req: SubmitRequest): SubmitResponse {
        val body = WireJson.encodeToString(SubmitRequest.serializer(), req).encodeToByteArray()
        val resp = transport.send(
            HttpRequest(
                method = "POST",
                url = "$base/console/pair/submit",
                headers = mapOf("Content-Type" to "application/json"),
                body = body,
            ),
        )
        return runCatching {
            WireJson.decodeFromString(SubmitResponse.serializer(), resp.bodyText())
        }.getOrElse { SubmitResponse(error = "bad response (${resp.status})") }
    }

    /**
     * Signed self-check (SPEC §3 step 5) — GET /console/device/me. Returns this
     * device's registration once the operator has confirmed the code-match, else
     * null (401 until registered). The app polls this to know pairing completed.
     */
    suspend fun whoAmI(signer: RequestSigner): DeviceRecord? {
        val resp = signedRequest(signer, "GET", "/console/device/me", null)
        if (resp.status != 200) return null
        return runCatching {
            WireJson.decodeFromString(DeviceRecord.serializer(), resp.bodyText())
        }.getOrNull()
    }

    /**
     * Signed owner chat (SPEC §1.2b) — POST /console/chat → proxied to the kernel
     * `/v6/pitch/chat` owner-bypass. Degrades to [ChatResult.TitanResting] when the
     * kernel is down (AG2).
     */
    suspend fun chat(signer: RequestSigner, message: String, session: String?): ChatResult {
        val body = WireJson.encodeToString(
            ChatRequestBody.serializer(), ChatRequestBody(message, session),
        ).encodeToByteArray()
        val resp = signedRequest(signer, "POST", "/console/chat", body)
        if (resp.status == 401) return ChatResult.Failed("This device isn't paired yet.")
        val raw = runCatching {
            WireJson.decodeFromString(ChatRaw.serializer(), resp.bodyText())
        }.getOrElse { return ChatResult.Failed("Unexpected reply (${resp.status}).") }
        return when {
            raw.titanDown -> ChatResult.TitanResting
            raw.declined -> ChatResult.Declined(raw.declineExplanation ?: "Titan declined to reply.")
            !raw.response.isNullOrBlank() -> ChatResult.Reply(raw.response!!)
            raw.error != null -> ChatResult.Failed(raw.error!!)
            else -> ChatResult.Failed("Empty reply (${resp.status}).")
        }
    }

    /**
     * Drain this device's outbound event queue (RFP event-channel §1.2) — a signed
     * long-poll. [wait] holds the connection server-side (0 = instant drain, what the
     * WorkManager job uses); [since] is the last cursor the phone consumed. A non-200
     * (401 / Titan-down) yields an empty response so the caller simply retries.
     */
    suspend fun events(signer: RequestSigner, wait: Int, since: Int): EventsResponse {
        val resp = signedRequest(signer, "GET", "/console/events", null, "wait=$wait&since=$since")
        if (resp.status != 200) return EventsResponse(cursor = since)
        return runCatching {
            WireJson.decodeFromString(EventsResponse.serializer(), resp.bodyText())
        }.getOrElse { EventsResponse(cursor = since) }
    }

    /**
     * Report presence + ack consumed events (RFP event-channel §1.2). [ackCursor]
     * prunes everything delivered up to it. Best-effort: returns true on 200.
     */
    suspend fun heartbeat(
        signer: RequestSigner,
        state: String,
        ackCursor: Int?,
        battery: Int? = null,
    ): Boolean {
        val body = WireJson.encodeToString(
            HeartbeatBody.serializer(), HeartbeatBody(state, ackCursor, battery),
        ).encodeToByteArray()
        return signedRequest(signer, "POST", "/console/app/heartbeat", body).status == 200
    }

    /** Restart the kernel from the phone (the health notification's action). 200 = ok. */
    suspend fun restart(signer: RequestSigner): Boolean =
        signedRequest(signer, "POST", "/console/restart", "{}".encodeToByteArray()).status == 200

    /**
     * Attach the AG4 signature headers and send. [body] null ⇒ no-body (GET). [query]
     * rides in the URL but is **excluded from the signature** — the canonical string
     * signs `method\npath\nts\nsha256hex(body)` (the server verifies the bare path; the
     * cursor is server-authoritative). This mirrors `verify_request_signature`.
     */
    private suspend fun signedRequest(
        signer: RequestSigner,
        method: String,
        path: String,
        body: ByteArray?,
        query: String? = null,
    ): HttpResponse {
        val ts = nowEpochSeconds().toString()
        val bodyBytes = body ?: ByteArray(0)
        val canonical = canonicalRequest(method, path, ts, sha256(bodyBytes).toHex())
        val signature = signer.sign(canonical.encodeToByteArray())
        val headers = buildMap {
            put("X-Device-Id", signer.deviceId)
            put("X-Timestamp", ts)
            put("X-Signature", Base64.encode(signature))
            if (body != null) put("Content-Type", "application/json")
        }
        val url = if (query.isNullOrEmpty()) "$base$path" else "$base$path?$query"
        return transport.send(HttpRequest(method, url, headers, body))
    }
}
