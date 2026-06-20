package tech.iamtitan.app.net

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import tech.iamtitan.app.chat.ChatRaw
import tech.iamtitan.app.chat.ChatRequestBody
import tech.iamtitan.app.chat.ChatResult
import tech.iamtitan.app.crypto.sha256
import tech.iamtitan.app.pairing.DeviceRecord
import tech.iamtitan.app.presence.ContextBody
import tech.iamtitan.app.presence.PresenceLatest
import tech.iamtitan.app.presence.PresenceSample
import tech.iamtitan.app.presence.PresenceSettings
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
        availability: String? = null,
        availabilityUntil: Double? = null,
    ): Boolean {
        val body = WireJson.encodeToString(
            HeartbeatBody.serializer(),
            HeartbeatBody(state, ackCursor, battery, availability, availabilityUntil),
        ).encodeToByteArray()
        return signedRequest(signer, "POST", "/console/app/heartbeat", body).status == 200
    }

    /** Restart the kernel from the phone (the health notification's action). 200 = ok. */
    suspend fun restart(signer: RequestSigner): Boolean =
        signedRequest(signer, "POST", "/console/restart", "{}".encodeToByteArray()).status == 200

    /**
     * Send a Channel-2 action tap or a feedback chip back to Titan (RFP §7.3) — a signed
     * POST that lands durably in the Console Agent's inbox (the kernel consumes it). 200 = ok.
     */
    suspend fun respond(
        signer: RequestSigner,
        inReplyTo: Int?,
        kind: String,
        actionId: String? = null,
        reaction: String? = null,
        stars: Int? = null,
    ): Boolean {
        val body = WireJson.encodeToString(
            RespondBody.serializer(), RespondBody(inReplyTo, kind, actionId, reaction, stars),
        ).encodeToByteArray()
        return signedRequest(signer, "POST", "/console/events/respond", body).status == 200
    }

    // ── Diagnostics + config (RFP_titan_mobile_app Phase 2a) — all signed reads over
    //    existing Console routes; null on any non-200/parse failure (the UI degrades). ──

    /** GET /console/host — host CPU/mem/swap/disk snapshot. */
    suspend fun host(signer: RequestSigner): HostResources? =
        getJson(signer, "/console/host", HostResources.serializer())

    /** GET /console/titan-status — liveness (up/why-down + journal tail when down). */
    suspend fun titanStatus(signer: RequestSigner): TitanLiveness? =
        getJson(signer, "/console/titan-status", TitanLiveness.serializer())

    /** GET /console/journal?lines=N — recent service journal. */
    suspend fun journal(signer: RequestSigner, lines: Int = 80): JournalTail? =
        getJson(signer, "/console/journal", JournalTail.serializer(), query = "lines=$lines")

    /** GET /console/api/v6/readiness — kernel WORKER-module roster (module_count / modules
     *  [name,state,pid] / module_state_summary). NOT /v6/nervous-system: that returns the 11
     *  cognitive AXES (REFLEX/FOCUS/…), not the workers (verified live 2026-06-20). */
    suspend fun nervousSystem(signer: RequestSigner): NervousSystem? =
        getJson(signer, "/console/api/v6/readiness", NervousSystem.serializer())

    /** GET /console/config[?section=] — all config keys (value+help+editable+source). */
    suspend fun config(signer: RequestSigner, section: String? = null): ConfigList? =
        getJson(signer, "/console/config", ConfigList.serializer(),
                query = section?.let { "section=$it" })

    /** POST /console/config/set — write a key (server is editable-guarded; returns ok/error). */
    suspend fun setConfig(signer: RequestSigner, key: String, value: String): SetConfigResult {
        val body = WireJson.encodeToString(
            SetConfigBody.serializer(), SetConfigBody(key, value),
        ).encodeToByteArray()
        val resp = signedRequest(signer, "POST", "/console/config/set", body)
        return runCatching {
            WireJson.decodeFromString(SetConfigResult.serializer(), resp.bodyText())
        }.getOrElse { SetConfigResult(ok = false, error = "bad response (${resp.status})") }
    }

    /** GET /console/api/v6/metabolism/gate-status → SOL balance + metabolic tier. Defensively
     *  parsed (the payload is wrapped `{status, data:{…}}`; we tolerate either nesting). */
    suspend fun metabolism(signer: RequestSigner): MetabolismView? {
        val obj = rawGet(signer, "/console/api/v6/metabolism/gate-status") ?: return null
        val data = (obj["data"] as? JsonObject) ?: obj
        return MetabolismView(
            tier = data["current_tier"]?.strOrNull(),
            solBalance = data["sol_balance"]?.dblOrNull(),
        )
    }

    /** GET /console/backups → ops.list_backups (records[] + manifest). Defensively parsed (a
     *  record's `ts`/`size_bytes` scalar type isn't pinned, so we read whatever's there). */
    suspend fun backups(signer: RequestSigner): BackupView? {
        val obj = rawGet(signer, "/console/backups") ?: return null
        val records = obj["records"] as? JsonArray
        val latest = records?.firstOrNull() as? JsonObject
        val manifest = obj["manifest"] as? JsonObject
        return BackupView(
            records = records?.size ?: 0,
            latestType = latest?.get("type")?.strOrNull(),
            latestTs = latest?.get("ts")?.scalarOrNull(),
            arweaveEvents = manifest?.get("events")?.intOrNull2(),
        )
    }

    // ── Presence / context uplink (RFP_titan_mobile_app Phase 3 / AG6) ──

    /** POST /console/context — upload opt-in-gated context samples. true on 200. */
    suspend fun uploadContext(signer: RequestSigner, samples: List<PresenceSample>): Boolean {
        if (samples.isEmpty()) return true
        val body = WireJson.encodeToString(
            ContextBody.serializer(), ContextBody(samples),
        ).encodeToByteArray()
        return signedRequest(signer, "POST", "/console/context", body).status == 200
    }

    /** GET /console/presence — the Maker's latest uploaded context (flat; no cognition). */
    suspend fun presence(signer: RequestSigner): PresenceLatest? =
        getJson(signer, "/console/presence", PresenceLatest.serializer())

    /** GET /console/presence/settings — per-sensor opt-in flags + cadence. */
    suspend fun presenceSettings(signer: RequestSigner): PresenceSettings? =
        getJson(signer, "/console/presence/settings", PresenceSettings.serializer())

    /** POST /console/presence/settings — patch opt-in flags / cadence; returns the merged set. */
    suspend fun setPresenceSettings(signer: RequestSigner, patch: PresenceSettings): PresenceSettings? {
        val body = WireJson.encodeToString(PresenceSettings.serializer(), patch).encodeToByteArray()
        val resp = signedRequest(signer, "POST", "/console/presence/settings", body)
        return runCatching {
            WireJson.decodeFromString(PresenceSettings.serializer(), resp.bodyText())
        }.getOrNull()
    }

    // ── Advanced layered ops (RFP_titan_mobile_app Phase 2b §7.2b) — privileged, signed.
    //    The Console gates each route device-side; the app additionally hides this surface
    //    behind the advanced-mode toggle. ──

    /** POST /console/ops/module/<action>/<name> — L2 worker reload|restart|enable, proxied to
     *  the kernel admin endpoint. [action] ∈ {reload,restart,enable}; [name] is a live module. */
    suspend fun moduleOp(signer: RequestSigner, action: String, name: String): OpsResult =
        postOps(signer, "/console/ops/module/$action/$name", null)

    /** POST /console/ops/reload-api — L3 zero-downtime api-layer reload (kernel /v6/admin/reload-api). */
    suspend fun reloadApi(signer: RequestSigner): OpsResult =
        postOps(signer, "/console/ops/reload-api", null)

    /** POST /console/ops/reboot — host VPS reboot. Requires a primary device (server-checked,
     *  403 otherwise) + the typed [confirmPhrase] ("REBOOT"). */
    suspend fun reboot(signer: RequestSigner, confirmPhrase: String): RebootResult {
        val body = WireJson.encodeToString(RebootBody.serializer(), RebootBody(confirmPhrase))
            .encodeToByteArray()
        val resp = signedRequest(signer, "POST", "/console/ops/reboot", body)
        if (resp.status == 403) return RebootResult(ok = false, error = "This device isn't authorized to reboot.")
        return runCatching {
            WireJson.decodeFromString(RebootResult.serializer(), resp.bodyText())
        }.getOrElse { RebootResult(ok = false, error = "Unexpected reply (${resp.status}).") }
    }

    /** GET /console/ops/processes — dry-run process scan (orphan-helper reapables). */
    suspend fun scanProcesses(signer: RequestSigner): ProcessScan? =
        getJson(signer, "/console/ops/processes", ProcessScan.serializer())

    /** POST /console/ops/processes/reap — kill specific allow-listed orphan PIDs (re-checked
     *  server-side at kill time). [pids] are confirmed from a prior [scanProcesses]. */
    suspend fun reapProcesses(signer: RequestSigner, pids: List<Int>): ReapResult? {
        val body = WireJson.encodeToString(ReapBody.serializer(), ReapBody(pids)).encodeToByteArray()
        val resp = signedRequest(signer, "POST", "/console/ops/processes/reap", body)
        if (resp.status != 200) return null
        return runCatching {
            WireJson.decodeFromString(ReapResult.serializer(), resp.bodyText())
        }.getOrNull()
    }

    /** POST /console/ops/prune-arweave-devnet — keep-newest-N of the devnet Arweave cache. */
    suspend fun pruneArweaveDevnet(signer: RequestSigner, keep: Int, confirm: Boolean): PruneResult? {
        val body = WireJson.encodeToString(PruneBody.serializer(), PruneBody(keep, confirm)).encodeToByteArray()
        val resp = signedRequest(signer, "POST", "/console/ops/prune-arweave-devnet", body)
        if (resp.status != 200) return null
        return runCatching {
            WireJson.decodeFromString(PruneResult.serializer(), resp.bodyText())
        }.getOrNull()
    }

    /** GET /console/agent-status — console self-status (uptime/version/Titan-reachable). The app
     *  polls this to detect the console coming back after a VPS reboot. */
    suspend fun agentStatus(signer: RequestSigner): AgentStatus? =
        getJson(signer, "/console/agent-status", AgentStatus.serializer())

    private suspend fun postOps(signer: RequestSigner, path: String, body: ByteArray?): OpsResult {
        val resp = signedRequest(signer, "POST", path, body ?: "{}".encodeToByteArray())
        if (resp.status == 401 || resp.status == 403)
            return OpsResult(error = "Not authorized for this op (${resp.status}).")
        return runCatching {
            WireJson.decodeFromString(OpsResult.serializer(), resp.bodyText())
        }.getOrElse { OpsResult(error = "Unexpected reply (${resp.status}).") }
    }

    private suspend fun rawGet(signer: RequestSigner, consolePath: String): JsonObject? =
        runCatching {
            val resp = signedRequest(signer, "GET", consolePath, null)
            if (resp.status != 200) null
            else WireJson.parseToJsonElement(resp.bodyText()) as? JsonObject
        }.getOrNull()

    private suspend fun <T> getJson(
        signer: RequestSigner,
        path: String,
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
        query: String? = null,
    ): T? = runCatching {
        val resp = signedRequest(signer, "GET", path, null, query)
        if (resp.status != 200) null else WireJson.decodeFromString(deserializer, resp.bodyText())
    }.getOrNull()

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

// Defensive JSON-scalar readers (used by metabolism()/backups()). Each returns null for a
// non-primitive / JsonNull / wrong-type element rather than throwing — a single odd field
// never propagates a decode failure up to the UI.
private fun JsonElement.strOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull
private fun JsonElement.scalarOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull
private fun JsonElement.dblOrNull(): Double? = (this as? JsonPrimitive)?.doubleOrNull
private fun JsonElement.intOrNull2(): Int? = (this as? JsonPrimitive)?.intOrNull
