package tech.iamtitan.app.net

/** A single HTTP exchange. `body == null` means no request body (GET). */
data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray? = null,
) {
    // value-style equality on the byte body (data-class default compares identity)
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpRequest) return false
        return method == other.method && url == other.url && headers == other.headers &&
            (body?.contentEquals(other.body ?: ByteArray(0)) ?: (other.body == null))
    }

    override fun hashCode(): Int {
        var h = method.hashCode()
        h = 31 * h + url.hashCode()
        h = 31 * h + headers.hashCode()
        h = 31 * h + (body?.let { it.contentHashCode() } ?: 0)
        return h
    }
}

data class HttpResponse(val status: Int, val body: ByteArray) {
    fun bodyText(): String = body.decodeToString()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpResponse) return false
        return status == other.status && body.contentEquals(other.body)
    }

    override fun hashCode(): Int = 31 * status + body.contentHashCode()
}

/**
 * Platform HTTP. Android = `HttpURLConnection` (AOSP, no GMS, all API levels);
 * JVM = `java.net.http.HttpClient`. `ConsoleClient` depends only on this
 * interface, so tests inject a fake transport that captures the signed request.
 */
interface HttpTransport {
    suspend fun send(request: HttpRequest): HttpResponse
}
