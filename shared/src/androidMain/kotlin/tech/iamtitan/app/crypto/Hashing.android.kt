package tech.iamtitan.app.crypto

import java.security.MessageDigest

/** Android SHA-256 via the platform provider (same JDK API as the JVM target). */
actual fun sha256(data: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(data)
