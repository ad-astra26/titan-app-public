package tech.iamtitan.app.net

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest as JdkRequest
import java.net.http.HttpResponse as JdkResponse
import java.time.Duration

/** JVM transport (tests + a JVM-driven local e2e). Blocking inside suspend — call on an IO context. */
class JdkHttpTransport(private val timeoutSeconds: Long = 60) : HttpTransport {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
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
