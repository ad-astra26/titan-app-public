package tech.iamtitan.app.crypto

/**
 * Raw Ed25519 — the device-identity signature primitive (SPEC /,).
 *
 * One contract in `commonMain`, a native `actual` per platform. Both the JVM
 * (tests) and Android (app) actuals are BouncyCastle — IDENTICAL code over the
 * SAME library version — so `:shared:jvmTest` exercises byte-for-byte what the
 * app runs on the phone (no untested jvm↔android divergence). The output MUST
 * equal the Python reference (`titan_console/_ed25519.py`); vectors are pinned
 * in `SigningContractTest`.
 *
 * Keys are RAW bytes: 32-byte seed, 32-byte public key, 64-byte detached signature.
 * The seed at rest is sealed by the platform hardware keystore — that
 * sealing lives in the platform UI module (Android Keystore + BiometricPrompt),
 * NOT here; this object is the pure math, kept testable.
 */
expect object Ed25519 {
    /** Derive the 32-byte public key from a 32-byte [seed]. */
    fun publicKey(seed: ByteArray): ByteArray

    /** Detached 64-byte signature over [message] using the 32-byte [seed]. */
    fun sign(seed: ByteArray, message: ByteArray): ByteArray

    /** True iff [signature] (64 bytes) is valid for [message] under [publicKey] (32 bytes). */
    fun verify(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean
}
