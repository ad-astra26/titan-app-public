package tech.iamtitan.app

import tech.iamtitan.app.net.bodySha256Hex
import tech.iamtitan.app.net.canonicalRequest
import tech.iamtitan.app.pairing.code6
import tech.iamtitan.app.pairing.parsePairingPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Locks the cross-language pairing/signing contract. Every constant here is the
 * value produced by the Python reference (`titan_console/pairing.py`) for the
 * same input — if these drift, the phone and the Console Agent will disagree and
 * pairing/signatures break. Vectors generated 2026-06-10.
 */
class PairingTest {
    private fun b(s: String) = s.encodeToByteArray()

    @Test
    fun code6_matches_python_vector() {
        assertEquals("028241", code6(b("TESTTOKEN"), b("PUBKEY")))
    }

    @Test
    fun code6_is_pubkey_sensitive() {
        assertNotEquals(
            code6(b("TESTTOKEN"), b("PUBKEY")),
            code6(b("TESTTOKEN"), b("PUBKEY2")),
        )
        assertEquals("635194", code6(b("TESTTOKEN"), b("PUBKEY2")))
    }

    @Test
    fun body_hash_matches_python_vector() {
        assertEquals(
            "adbd982b8fe0bbd8477f09262028d3ac264001dc36e3c7579905e72c0b718755",
            bodySha256Hex(b("{\"message\":\"hi\"}")),
        )
    }

    @Test
    fun canonical_request_format() {
        assertEquals(
            "POST\n/console/chat\n1700000000\n" +
                "adbd982b8fe0bbd8477f09262028d3ac264001dc36e3c7579905e72c0b718755",
            canonicalRequest(
                "POST", "/console/chat", "1700000000",
                "adbd982b8fe0bbd8477f09262028d3ac264001dc36e3c7579905e72c0b718755",
            ),
        )
    }

    @Test
    fun parses_mode_and_tls_pin_and_ignores_unknown_fields() {
        val qr = """{"mode":"remote","pairing_token":"tok","server_pubkey":"pk",""" +
            """"titan_id":"T1","ttl":90,"endpoint_url":"https://1.2.3.4:7799",""" +
            """"server_tls_pin":"48efea7a","future_field":"ignored"}"""
        val p = parsePairingPayload(qr)!!
        assertEquals("remote", p.mode)
        assertEquals("https://1.2.3.4:7799", p.endpointUrl)
        assertEquals("48efea7a", p.serverTlsPin)
        assertEquals("tok", p.pairingToken)
    }

    @Test
    fun parses_legacy_payload_without_new_fields() {
        val p = parsePairingPayload(
            """{"pairing_token":"tok","server_pubkey":"pk","titan_id":"T1","ttl":90}""",
        )!!
        assertNull(p.mode)
        assertNull(p.serverTlsPin)
        assertNull(p.endpointUrl)
    }
}
