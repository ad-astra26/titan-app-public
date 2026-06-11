package tech.iamtitan.app.crypto

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * JVM actual = BouncyCastle (identical to the Android actual — same lib/version).
 * Standard Ed25519, so it matches the Python vendored impl byte-for-byte.
 */
actual object Ed25519 {
    actual fun publicKey(seed: ByteArray): ByteArray {
        require(seed.size == 32) { "Ed25519 seed must be 32 bytes" }
        return Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded
    }

    actual fun sign(seed: ByteArray, message: ByteArray): ByteArray {
        require(seed.size == 32) { "Ed25519 seed must be 32 bytes" }
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(seed, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    actual fun verify(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean {
        if (signature.size != 64 || publicKey.size != 32) return false
        val signer = Ed25519Signer()
        signer.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        signer.update(message, 0, message.size)
        return signer.verifySignature(signature)
    }
}
