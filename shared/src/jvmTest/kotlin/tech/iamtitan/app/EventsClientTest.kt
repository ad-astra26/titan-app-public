package tech.iamtitan.app

import kotlinx.coroutines.runBlocking
import tech.iamtitan.app.crypto.Ed25519
import tech.iamtitan.app.crypto.sha256
import tech.iamtitan.app.net.ConsoleClient
import tech.iamtitan.app.net.HttpRequest
import tech.iamtitan.app.net.HttpResponse
import tech.iamtitan.app.net.HttpTransport
import tech.iamtitan.app.net.RequestSigner
import tech.iamtitan.app.net.canonicalRequest
import tech.iamtitan.app.net.toHex
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The event-channel signed wire (RFP_titan_app_event_channel Phase 1). Proves the app's
 * `events()` long-poll signs EXACTLY what `pairing.verify_request_signature` reconstructs
 * — over the **bare path** `/console/events` (the `wait`/`since` query is sent but NOT
 * signed) — so a real Console Agent accepts it; that the drain response (incl. typed
 * payload extraction) decodes; and that `heartbeat` signs its body + carries `ack_cursor`.
 */
@OptIn(ExperimentalEncodingApi::class)
class EventsClientTest {
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
    fun events_signs_bare_path_sends_query_and_parses() = runBlocking {
        val body = ("""{"events":[{"seq":42,"type":"message","payload":{"text":"It is 6"}},""" +
            """{"seq":43,"type":"health","payload":{"up":false,"text":"down"}}],"cursor":43}""")
            .encodeToByteArray()
        val fake = FakeTransport(HttpResponse(200, body))
        val client = ConsoleClient("http://10.0.2.2:7799", fake)

        val resp = client.events(signer, wait = 25, since = 7)
        assertEquals(43, resp.cursor)
        assertEquals(2, resp.events.size)
        assertEquals("It is 6", resp.events[0].messageText())
        assertEquals("message", resp.events[0].type)
        assertFalse(resp.events[1].healthUp())
        assertEquals("down", resp.events[1].healthText())

        val req = assertNotNull(fake.last)
        assertEquals("GET", req.method)
        // The query rides in the URL...
        assertEquals("http://10.0.2.2:7799/console/events?wait=25&since=7", req.url)
        // ...but the signature is over the BARE path (no query), matching the server.
        val ts = req.headers["X-Timestamp"]!!
        val canonical = canonicalRequest("GET", "/console/events", ts, sha256(ByteArray(0)).toHex())
        val sig = Base64.decode(req.headers["X-Signature"]!!)
        assertTrue(Ed25519.verify(sig, canonical.encodeToByteArray(), signer.publicKey))
    }

    @Test
    fun urgency_decodes_off_the_wire_default_normal() = runBlocking {
        // The §7.2b warm-the-line + urgent heads-up both branch on this field, so it
        // must survive the wire: explicit "high" preserved, absent defaults to "normal".
        val body = ("""{"events":[{"seq":1,"type":"message","urgency":"high","payload":{"text":"now"}},""" +
            """{"seq":2,"type":"message","payload":{"text":"later"}}],"cursor":2}""")
            .encodeToByteArray()
        val resp = ConsoleClient("http://x", FakeTransport(HttpResponse(200, body)))
            .events(signer, wait = 0, since = 0)
        assertEquals("high", resp.events[0].urgency)
        assertEquals("normal", resp.events[1].urgency)
    }

    @Test
    fun events_non_200_returns_empty_keeping_cursor() = runBlocking {
        val fake = FakeTransport(HttpResponse(401, """{"error":"nope"}""".encodeToByteArray()))
        val resp = ConsoleClient("http://x", fake).events(signer, wait = 0, since = 12)
        assertTrue(resp.events.isEmpty())
        assertEquals(12, resp.cursor) // unchanged → the loop retries from where it was
    }

    @Test
    fun heartbeat_signs_body_and_carries_ack_cursor() = runBlocking {
        val fake = FakeTransport(HttpResponse(200, """{"ok":true}""".encodeToByteArray()))
        val ok = ConsoleClient("http://x", fake).heartbeat(signer, "foreground", 43, battery = 88)
        assertTrue(ok)

        val req = assertNotNull(fake.last)
        assertEquals("POST", req.method)
        assertEquals("http://x/console/app/heartbeat", req.url)
        val ts = req.headers["X-Timestamp"]!!
        val canonical = canonicalRequest("POST", "/console/app/heartbeat", ts, sha256(req.body!!).toHex())
        val sig = Base64.decode(req.headers["X-Signature"]!!)
        assertTrue(Ed25519.verify(sig, canonical.encodeToByteArray(), signer.publicKey))

        val sent = req.body!!.decodeToString()
        assertTrue(sent.contains("\"state\":\"foreground\""))
        assertTrue(sent.contains("\"ack_cursor\":43"))
        assertTrue(sent.contains("\"battery\":88"))
    }

    // ── Phase 3 §7.3 ─────────────────────────────────────────────────────────
    @Test
    fun system_event_decodes_text_and_actions() = runBlocking {
        val body = ("""{"events":[{"seq":7,"type":"system","payload":{"text":"Backup failed.",""" +
            """"actions":[{"id":"retry","label":"Retry now","needs_app":false},""" +
            """{"id":"details","label":"Tell me more","needs_app":true},""" +
            """{"label":"malformed-no-id"}]}}],"cursor":7}""").encodeToByteArray()
        val resp = ConsoleClient("http://x", FakeTransport(HttpResponse(200, body)))
            .events(signer, wait = 0, since = 0)
        val e = resp.events[0]
        assertEquals("system", e.type)
        assertEquals("Backup failed.", e.systemText())
        val actions = e.systemActions()
        assertEquals(2, actions.size) // the malformed (no id) entry is skipped, not a crash
        assertEquals("retry", actions[0].id)
        assertEquals("Retry now", actions[0].label)
        assertFalse(actions[0].needsApp)
        assertTrue(actions[1].needsApp) // "details" = high-stakes → opens the app
    }

    @Test
    fun respond_signs_bare_path_and_carries_choice() = runBlocking {
        val fake = FakeTransport(HttpResponse(200, """{"ok":true,"seq":1}""".encodeToByteArray()))
        val ok = ConsoleClient("http://x", fake)
            .respond(signer, inReplyTo = 42, kind = "action", actionId = "retry")
        assertTrue(ok)
        val req = assertNotNull(fake.last)
        assertEquals("POST", req.method)
        assertEquals("http://x/console/events/respond", req.url)
        val ts = req.headers["X-Timestamp"]!!
        val canonical = canonicalRequest("POST", "/console/events/respond", ts, sha256(req.body!!).toHex())
        val sig = Base64.decode(req.headers["X-Signature"]!!)
        assertTrue(Ed25519.verify(sig, canonical.encodeToByteArray(), signer.publicKey))
        val sent = req.body!!.decodeToString()
        assertTrue(sent.contains("\"in_reply_to\":42"))
        assertTrue(sent.contains("\"kind\":\"action\""))
        assertTrue(sent.contains("\"action_id\":\"retry\""))
    }

    @Test
    fun heartbeat_carries_availability() = runBlocking {
        val fake = FakeTransport(HttpResponse(200, """{"ok":true}""".encodeToByteArray()))
        ConsoleClient("http://x", fake)
            .heartbeat(signer, "foreground", 1, battery = 80, availability = "busy")
        val sent = assertNotNull(fake.last).body!!.decodeToString()
        assertTrue(sent.contains("\"availability\":\"busy\""))
    }
}
