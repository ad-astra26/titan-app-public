package tech.iamtitan.app.net

import tech.iamtitan.app.crypto.sha256

/** Lowercase hex of the bytes. */
fun ByteArray.toHex(): String =
    joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

/** SHA-256 hex of a request body — the body component of the signed string (SPEC ). */
fun bodySha256Hex(body: ByteArray): String = sha256(body).toHex()

/**
 * The canonical string the device key signs (SPEC / step 5):
 *
 * method "\n" path "\n" timestamp "\n" sha256hex(body)
 *
 * Must match Python `titan_console/pairing.py::verify_request_signature`
 * canonicalization exactly, or signatures will never verify cross-language.
 */
fun canonicalRequest(
    method: String,
    path: String,
    timestamp: String,
    bodySha256Hex: String,
): String = "$method\n$path\n$timestamp\n$bodySha256Hex"
