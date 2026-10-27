package link.lander.android.internal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

internal class HttpResponse(
    val status: Int,
    val body: String,
    /** Header names are lowercase. */
    val headers: Map<String, String> = emptyMap(),
)

/** Sends one POST. Throws on network failures; any HTTP status is a response. */
internal fun interface HttpTransport {
    suspend fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Long): HttpResponse
}

/** [HttpTransport] on HttpURLConnection, so the SDK brings no HTTP library. */
internal class UrlConnectionTransport(
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : HttpTransport {
    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String,
        timeoutMs: Long,
    ): HttpResponse = runInterruptible(io) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMs.toInt()
            conn.readTimeout = timeoutMs.toInt()
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.doOutput = true
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val bytes = body.toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val status = conn.responseCode
            val stream: InputStream? = if (status >= 400) conn.errorStream else conn.inputStream
            // JSON is UTF-8 whatever the Content-Type says.
            val text = stream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            val responseHeaders = conn.headerFields
                .filterKeys { it != null }
                .mapKeys { it.key.lowercase() }
                .mapValues { it.value.firstOrNull() ?: "" }
            HttpResponse(status, text, responseHeaders)
        } finally {
            conn.disconnect()
        }
    }
}
