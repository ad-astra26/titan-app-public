package tech.iamtitan.app.crypto

import java.security.MessageDigest

/** JVM/Android SHA-256 via the platform provider. (Android reuses this source set in Step B.) */
actual fun sha256(data: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(data)
