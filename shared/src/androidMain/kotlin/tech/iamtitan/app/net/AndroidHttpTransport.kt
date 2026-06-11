package tech.iamtitan.app.net

import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Android transport via `HttpURLConnection` — AOSP, zero Google-Play-Services
 * (AD-7), works on every API ≥ 26. Blocking inside suspend — the app calls it on
 * `Dispatchers.IO`. Over Tailscale/localhost; TLS handled by the platform for the
 * nginx fallback host.
 */
class AndroidHttpTransport(private val readTimeoutMs: Int = 60_000) : HttpTransport {
    override suspend fun send(request: HttpRequest): HttpResponse {
        val conn = (URL(request.url).openConnection() as HttpURLConnection).apply {
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
