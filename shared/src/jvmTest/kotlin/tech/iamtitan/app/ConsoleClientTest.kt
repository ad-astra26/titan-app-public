package tech.iamtitan.app

import kotlinx.coroutines.runBlocking
import tech.iamtitan.app.chat.ChatResult
import tech.iamtitan.app.crypto.Ed25519
import tech.iamtitan.app.crypto.sha256
import tech.iamtitan.app.net.ConsoleClient
import tech.iamtitan.app.net.HttpRequest
import tech.iamtitan.app.net.HttpResponse
import tech.iamtitan.app.net.HttpTransport
import tech.iamtitan.app.net.RequestSigner
import tech.iamtitan.app.net.canonicalRequest
import tech.iamtitan.app.net.toHex
import tech.iamtitan.app.pairing.SubmitRequest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The signed-wire integration: ConsoleClient must assemble exactly the headers
 * `pairing.verify_request_signature` reconstructs, and parse each reply variant.
 * The key assertion re-derives the server's canonical string from the captured
 * request and verifies the signature under the device pubkey — i.e. proves a real
 * Console Agent would ACCEPT what the app sends.
 */
@OptIn(ExperimentalEncodingApi::class)
class ConsoleClientTest {
    private val seed = ByteArray(32) { it.toByte() }
    private val signer = object : RequestSigner {
        override val deviceId = "dev-123"
        override val publicKey = Ed25519.publicKey(seed)
        override suspend fun sign(canonicalBytes: ByteArray) = Ed25519.sign(seed, canonicalBytes)
    }

    private class FakeTransport(private val response: HttpResponse) : HttpTransport {
        var last: HttpRequest? = null
        override suspend fun send(request: HttpRequest): HttpResponse {
            last = request
            return response
        }
    }

    @Test
    fun chat_signs_canonically_and_parses_reply() = runBlocking {
        val fake = FakeTransport(
            HttpResponse(200, """{"response":"Always.","titan":"titan"}""".encodeToByteArray()),
        )
        val client = ConsoleClient("http://10.0.2.2:7799", fake)

        val result = client.chat(signer, "are you there?", "console-dev-123-thread")
        assertEquals(ChatResult.Reply("Always."), result)

        val req = assertNotNull(fake.last)
        assertEquals("POST", req.method)
        assertEquals("http://10.0.2.2:7799/console/chat", req.url)
        assertEquals("dev-123", req.headers["X-Device-Id"])
        assertNotNull(req.headers["X-Timestamp"])

        // Reconstruct the server's canonical string and verify the signature.
        val ts = req.headers["X-Timestamp"]!!
        val canonical = canonicalRequest("POST", "/console/chat", ts, sha256(req.body!!).toHex())
        val sig = Base64.decode(req.headers["X-Signature"]!!)
        assertTrue(Ed25519.verify(sig, canonical.encodeToByteArray(), signer.publicKey))
    }

    @Test
    fun chat_titan_down_envelope_maps_to_resting() = runBlocking {
        val fake = FakeTransport(
            HttpResponse(503, """{"titan_down":true,"detail":"unreachable"}""".encodeToByteArray()),
        )
        val result = ConsoleClient("http://x", fake).chat(signer, "hi", "console-dev-123-thread")
        assertEquals(ChatResult.TitanResting, result)
    }

    @Test
    fun chat_declined_is_surfaced() = runBlocking {
        val fake = FakeTransport(
            HttpResponse(
                200,
                """{"response":"","declined":true,"decline_explanation":"Resting now."}"""
                    .encodeToByteArray(),
            ),
        )
        val result = ConsoleClient("http://x", fake).chat(signer, "hi", "console-dev-123-thread")
        assertEquals(ChatResult.Declined("Resting now."), result)
    }

    @Test
    fun submit_device_is_unsigned_bootstrap() = runBlocking {
        val fake = FakeTransport(
            HttpResponse(200, """{"ok":true,"awaiting_confirm":true}""".encodeToByteArray()),
        )
        val resp = ConsoleClient("http://x", fake).submitDevice(
            SubmitRequest(
                pairingToken = "tok",
                deviceId = "dev-123",
                devicePubkey = Base64.encode(signer.publicKey),
                label = "Pixel",
            ),
        )
        assertTrue(resp.ok && resp.awaitingConfirm)
        val req = assertNotNull(fake.last)
        assertEquals("http://x/console/pair/submit", req.url)
        assertNull(req.headers["X-Signature"]) // the bootstrap carries no device signature
    }

    @Test
    fun whoami_returns_record_on_200_null_on_401() = runBlocking {
        val ok = FakeTransport(
            HttpResponse(200, """{"device_id":"dev-123","label":"Pixel"}""".encodeToByteArray()),
        )
        assertEquals("dev-123", ConsoleClient("http://x", ok).whoAmI(signer)?.deviceId)

        val unauth = FakeTransport(HttpResponse(401, """{"error":"unknown device"}""".encodeToByteArray()))
        assertNull(ConsoleClient("http://x", unauth).whoAmI(signer))
    }

    // ── advanced ops ──

    @Test
    fun moduleOp_signs_canonically_and_reads_ok() = runBlocking {
        val fake = FakeTransport(HttpResponse(200, """{"status":"ok","data":"x"}""".encodeToByteArray()))
        val r = ConsoleClient("http://x", fake).moduleOp(signer, "restart", "cognitive_worker")
        assertTrue(r.succeeded)
        val req = assertNotNull(fake.last)
        assertEquals("POST", req.method)
        assertEquals("http://x/console/ops/module/restart/cognitive_worker", req.url)
        // Prove the server would ACCEPT the signature over the bare path.
        val ts = req.headers["X-Timestamp"]!!
        val canonical = canonicalRequest("POST", "/console/ops/module/restart/cognitive_worker",
            ts, sha256(req.body!!).toHex())
        assertTrue(Ed25519.verify(Base64.decode(req.headers["X-Signature"]!!),
            canonical.encodeToByteArray(), signer.publicKey))
    }

    @Test
    fun reloadApi_targets_console_route() = runBlocking {
        val fake = FakeTransport(HttpResponse(200, """{"status":"ok","data":"abc"}""".encodeToByteArray()))
        assertTrue(ConsoleClient("http://x", fake).reloadApi(signer).succeeded)
        assertEquals("http://x/console/ops/reload-api", fake.last!!.url)
    }

    @Test
    fun reboot_sends_confirm_phrase_and_maps_403() = runBlocking {
        val ok = FakeTransport(HttpResponse(200, """{"ok":true,"rebooting":true}""".encodeToByteArray()))
        val r = ConsoleClient("http://x", ok).reboot(signer, "REBOOT")
        assertTrue(r.ok && r.rebooting)
        assertEquals("http://x/console/ops/reboot", ok.last!!.url)
        assertTrue(ok.last!!.body!!.decodeToString().contains("REBOOT"))

        val denied = FakeTransport(HttpResponse(403, """{"error":"reboot requires a primary paired device"}""".encodeToByteArray()))
        assertTrue(ConsoleClient("http://x", denied).reboot(signer, "REBOOT").error != null)
    }

    @Test
    fun scanProcesses_parses_classification() = runBlocking {
        val fake = FakeTransport(HttpResponse(200,
            """{"dry_run":true,"count":1,"reapable":[90001],"zombies":[],"processes":[{"pid":90001,"comm":"chromium","classification":"orphan_helper","reapable":true}]}""".encodeToByteArray()))
        val scan = assertNotNull(ConsoleClient("http://x", fake).scanProcesses(signer))
        assertEquals(listOf(90001), scan.reapable)
        assertEquals("orphan_helper", scan.processes.first().classification)
        assertEquals("GET", fake.last!!.method)
    }

    @Test
    fun reapProcesses_sends_pids_body() = runBlocking {
        val fake = FakeTransport(HttpResponse(200,
            """{"requested":1,"killed":1,"results":[{"pid":90001,"killed":true,"comm":"chromium"}]}""".encodeToByteArray()))
        val r = assertNotNull(ConsoleClient("http://x", fake).reapProcesses(signer, listOf(90001)))
        assertEquals(1, r.killed)
        assertEquals("http://x/console/ops/processes/reap", fake.last!!.url)
        assertTrue(fake.last!!.body!!.decodeToString().contains("90001"))
    }

    @Test
    fun agentStatus_reads_reachable() = runBlocking {
        val fake = FakeTransport(HttpResponse(200,
            """{"agent":"titan-console","version":"0.1.0-alpha","titan_reachable":true,"uptime_seconds":12.5}""".encodeToByteArray()))
        val s = assertNotNull(ConsoleClient("http://x", fake).agentStatus(signer))
        assertTrue(s.titanReachable)
        assertEquals("titan-console", s.agent)
    }
}
