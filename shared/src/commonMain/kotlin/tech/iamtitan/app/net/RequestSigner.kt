package tech.iamtitan.app.net

/**
 * The device's request-signing identity (SPEC AG3/AG4). Supplied by the platform:
 * on Android, an implementation that unwraps the hardware-sealed Ed25519 seed
 * behind a `BiometricPrompt`, then signs; in tests, a plain in-memory seed.
 *
 * `shared/` networking depends only on this interface — it never touches the
 * Keystore or biometrics, keeping the wire layer fully unit-testable.
 */
interface RequestSigner {
    /** Stable per-device id registered in `devices.json` → the `X-Device-Id` header. */
    val deviceId: String

    /** This device's 32-byte Ed25519 public key (raw bytes). */
    val publicKey: ByteArray

    /**
     * Ed25519 signature (64 bytes) over [canonicalBytes]
     * (`method\npath\nts\nsha256hex(body)`, UTF-8). MAY suspend to prompt biometrics.
     */
    suspend fun sign(canonicalBytes: ByteArray): ByteArray
}
