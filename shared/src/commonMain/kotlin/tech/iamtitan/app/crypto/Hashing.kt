package tech.iamtitan.app.crypto

/**
 * SHA-256 of [data]. Platform-provided via `expect`/`actual` — the KMP keystone:
 * one contract in `commonMain`, a native implementation per platform
 * (JVM/Android: `java.security.MessageDigest`; iOS later: CommonCrypto).
 */
expect fun sha256(data: ByteArray): ByteArray
