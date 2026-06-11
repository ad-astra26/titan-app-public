package tech.iamtitan.app

import tech.iamtitan.app.crypto.Ed25519
import tech.iamtitan.app.net.toHex
import tech.iamtitan.app.pairing.code6
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the Kotlin Ed25519 (BouncyCastle) to the Python reference
 * (`titan_console/_ed25519.py`) byte-for-byte. Vectors generated 2026-06-11 from
 * the live Python with seed = 0x00,01,…,1f. If these drift, signatures the phone
 * produces will not verify on the Console Agent.
 */
@OptIn(ExperimentalEncodingApi::class)
class Ed25519ContractTest {
    private val seed = ByteArray(32) { it.toByte() } // 000102…1f
    private val canonical =
        "POST\n/console/chat\n1700000000\n" +
            "adbd982b8fe0bbd8477f09262028d3ac264001dc36e3c7579905e72c0b718755"

    @Test
    fun public_key_matches_python_vector() {
        assertEquals(
            "03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8",
            Ed25519.publicKey(seed).toHex(),
        )
    }

    @Test
    fun signature_matches_python_vector() {
        // Ed25519 (RFC 8032) is deterministic → a fixed expected signature.
        val sig = Ed25519.sign(seed, canonical.encodeToByteArray())
        assertEquals(
            "ZRgwZrsOUjW81ovisT9qpLR0Rx+RFzAQJ8+glbmgSP9+tvBJH3SYgpzQTXZ1L1x5woimzPLY3ZN0mXFMRfjJDw==",
            Base64.encode(sig),
        )
    }

    @Test
    fun verify_roundtrip_and_rejects_tamper() {
        val pub = Ed25519.publicKey(seed)
        val msg = canonical.encodeToByteArray()
        val sig = Ed25519.sign(seed, msg)
        assertTrue(Ed25519.verify(sig, msg, pub))
        assertFalse(Ed25519.verify(sig, (canonical + "x").encodeToByteArray(), pub))
    }

    @Test
    fun code6_for_derived_device_pubkey() {
        assertEquals(
            "640801",
            code6("TESTTOKEN".encodeToByteArray(), Ed25519.publicKey(seed)),
        )
    }
}
