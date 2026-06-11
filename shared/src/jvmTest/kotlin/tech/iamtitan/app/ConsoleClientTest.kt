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
}
