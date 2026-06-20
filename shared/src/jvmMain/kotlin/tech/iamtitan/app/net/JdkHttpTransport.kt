package tech.iamtitan.app.net

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest as JdkRequest
import java.net.http.HttpResponse as JdkResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * JVM transport (tests + a JVM-driven local e2e). Blocking inside suspend — call on an IO context.
 *
 * TLS pinning (/) mirrors the Android transport: when a pin is supplied, the server is
 * trusted iff `sha256(leaf-cert-DER) == pin`, and hostname identification is disabled (the pin
 * replaces it — so a self-signed cert on a bare IP is accepted, only the real Titan's).
 */
class JdkHttpTransport(
    private val timeoutSeconds: Long = 60,
    private val tlsPin: String? = null,
) : HttpTransport {
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .also { b ->
            if (tlsPin != null) {
                b.sslContext(jdkPinnedSslContext(tlsPin))
                b.sslParameters(SSLParameters().apply { endpointIdentificationAlgorithm = null })
            }
        }
        .build()

    override suspend fun send(request: HttpRequest): HttpResponse {
        val builder = JdkRequest.newBuilder(URI.create(request.url))
            .timeout(Duration.ofSeconds(timeoutSeconds))
        request.headers.forEach { (k, v) -> builder.header(k, v) }
        val publisher =
            if (request.body != null) JdkRequest.BodyPublishers.ofByteArray(request.body)
            else JdkRequest.BodyPublishers.noBody()
        builder.method(request.method, publisher)
        val resp = client.send(builder.build(), JdkResponse.BodyHandlers.ofByteArray())
        return HttpResponse(resp.statusCode(), resp.body())
    }
}

/** sha256 of `bytes` as lowercase hex — the cross-language pin form (== Python
 * `hashlib.sha256(DER).hexdigest`). */
internal fun jvmSha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { ((it.toInt() and 0xff) + 0x100).toString(16).substring(1) }

internal fun jdkPinningTrustManager(pinHex: String): X509TrustManager = object : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("no server certificate")
        val got = jvmSha256Hex(leaf.encoded)
        if (!got.equals(pinHex, ignoreCase = true)) {
            throw CertificateException("TLS pin mismatch (expected $pinHex, got $got)")
        }
    }
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

internal fun jdkPinnedSslContext(pinHex: String): SSLContext =
    SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(jdkPinningTrustManager(pinHex)), SecureRandom())
    }
