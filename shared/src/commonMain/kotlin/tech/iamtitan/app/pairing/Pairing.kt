package tech.iamtitan.app.pairing

import tech.iamtitan.app.crypto.sha256

/**
 * The 6-digit pairing confirmation code (SPEC AG5 / §3).
 *
 * Derived deterministically from the QR `pairingToken` and the device's public
 * key so that BOTH the phone and the Console Agent compute the SAME value from
 * independent inputs. A man-in-the-middle that relays the QR but substitutes its
 * own pubkey produces a DIFFERENT code than the phone shows → the human match
 * fails → pairing is rejected. The human code-match is the second factor that
 * binds "the pubkey that was submitted" to "the phone in the Maker's hand".
 *
 * Contract (must match Python `titan_console/pairing.py::code6` byte-for-byte):
 *   code = uint32_be( sha256(pairingToken ‖ devicePubKey)[0:4] ) % 1_000_000
 * zero-padded to 6 digits.
 */
fun code6(pairingToken: ByteArray, devicePubKey: ByteArray): String {
    val h = sha256(pairingToken + devicePubKey)
    val n = ((h[0].toLong() and 0xFF) shl 24) or
        ((h[1].toLong() and 0xFF) shl 16) or
        ((h[2].toLong() and 0xFF) shl 8) or
        (h[3].toLong() and 0xFF)
    return (n % 1_000_000L).toString().padStart(6, '0')
}
