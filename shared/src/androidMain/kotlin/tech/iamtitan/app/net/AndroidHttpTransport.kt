package tech.iamtitan.app.net

import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Android transport via `HttpURLConnection` — AOSP, zero Google-Play-Services
 *, works on every API ≥ 26. Blocking inside suspend — the app calls it on
 * `Dispatchers.IO`.
 *
 * TLS pinning (/): when the scanned QR carried `server_tls_pin`, https
 * connections trust the server iff `sha256(leaf-cert-DER) == pin`, and hostname
 * verification is replaced by the pin — so a self-signed cert on a BARE IP (no
 * domain/CA) is accepted, but ONLY the real Titan's cert is. A mismatch throws
 * (fail-closed; no plaintext fallback). pin == null ⇒ no pinning (dev/LAN-http).
 */
class AndroidHttpTransport(
    private val readTimeoutMs: Int = 60_000,
    private val tlsPin: String? = null,
) : HttpTransport {
    override suspend fun send(request: HttpRequest): HttpResponse {
        val conn = (URL(request.url).openConnection() as HttpURLConnection).apply {
            if (this is HttpsURLConnection && tlsPin != null) {
                sslSocketFactory = pinnedSocketFactory(tlsPin)
                setHostnameVerifier { _, _ -> true } // pin replaces hostname trust (bare IP)
            }
            requestMethod = request.method
            connectTimeout = 10_000
            readTimeout = readTimeoutMs
            request.headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (request.body != null) {
                doOutput = true
                outputStream.use { it.write(request.body) }
            }
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val bytes = stream?.let { BufferedInputStream(it).use { b -> b.readBytes() } } ?: ByteArray(0)
            HttpResponse(code, bytes)
        } finally {
            conn.disconnect()
        }
    }
}

/** sha256 of `bytes` as lowercase hex — the cross-language pin form (== Python
 * `hashlib.sha256(DER).hexdigest`). */
internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { ((it.toInt() and 0xff) + 0x100).toString(16).substring(1) }

/** A TrustManager that trusts a server iff its leaf cert DER sha256 matches the pin. */
internal fun pinningTrustManager(pinHex: String): X509TrustManager = object : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("no server certificate")
        val got = sha256Hex(leaf.encoded)
        if (!got.equals(pinHex, ignoreCase = true)) {
            throw CertificateException("TLS pin mismatch (expected $pinHex, got $got)")
        }
    }
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

internal fun pinnedSocketFactory(pinHex: String): SSLSocketFactory =
    SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(pinningTrustManager(pinHex)), SecureRandom())
    }.socketFactory
