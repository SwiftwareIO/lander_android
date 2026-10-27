package link.lander.android.internal

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import link.lander.android.LanderException
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.net.SocketTimeoutException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/** Thin JSON client for the `/v1/sdk/` endpoints in docs/API.md. */
internal class LanderApi(
    private val apiKey: String,
    baseUrl: String,
    private val transport: HttpTransport,
    private val logger: LanderLogger,
    private val sdkVersion: String,
    /** Per attempt request timeout. */
    val timeoutMs: Long = 10_000,
    /** Backoff between attempts of [post] when `maxAttempts > 1`. */
    private val backoff: Backoff = Backoff(baseMs = 500, maxMs = 4_000),
    /**
     * Total time one [post] may wait between its attempts. Callers are waiting
     * on the answer (a share button, the first screen), so a longer
     * `Retry-After` ends the retries and the error, with its
     * [LanderException.retryAfter], goes to the caller instead.
     */
    private val maxRetryWaitMs: Long = 10_000,
) {
    private val baseUrl = baseUrl.trimEnd('/')

    /**
     * POSTs [body] as JSON and returns the decoded response object.
     *
     * Retries retryable failures (network errors, timeouts, 408, 429, 5xx) up
     * to [maxAttempts] times, reusing the same [idempotencyKey] so the server
     * can drop duplicates, and waiting at most [maxRetryWaitMs] in total. A 2xx
     * response must be a JSON object (except 204). Throws [LanderException].
     */
    suspend fun post(
        path: String,
        body: JSONObject,
        idempotencyKey: String? = null,
        maxAttempts: Int = 1,
    ): JSONObject {
        var attempt = 0
        var waited = 0L
        while (true) {
            try {
                return postOnce(path, body, idempotencyKey)
            } catch (e: LanderException) {
                attempt++
                if (!e.isRetryable || attempt >= maxAttempts) throw e
                var wait = backoff.delayMs(attempt - 1)
                val retryAfter = e.retryAfter?.inWholeMilliseconds
                if (retryAfter != null && retryAfter > wait) wait = retryAfter
                if (waited + wait > maxRetryWaitMs) {
                    logger.log("POST $path failed (${e.code}), not retrying: next try in ${wait / 1000}s")
                    throw e
                }
                waited += wait
                logger.log("POST $path failed (${e.code}), retrying in $wait ms")
                delay(wait)
            }
        }
    }

    private suspend fun postOnce(path: String, body: JSONObject, idempotencyKey: String?): JSONObject {
        val headers = buildMap {
            put("Authorization", "Bearer $apiKey")
            put("Content-Type", "application/json")
            put("Accept", "application/json")
            put("X-Lander-Sdk", "android/$sdkVersion")
            if (idempotencyKey != null) put("Idempotency-Key", idempotencyKey)
        }
        val res = try {
            withTimeout(timeoutMs) { transport.post("$baseUrl$path", headers, body.toString(), timeoutMs) }
        } catch (_: TimeoutCancellationException) {
            throw timeout(path)
        } catch (_: SocketTimeoutException) {
            throw timeout(path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw LanderException(code = "network_error", message = "POST $path failed: $e")
        }

        val json = decode(res.body)
        if (res.status in 200..299) {
            if (json != null) return json
            if (res.status == 204) return JSONObject()
            // For example a captive portal or proxy page. Treating it as success
            // would, for init, mark the first launch reported and lose a deferred link.
            throw LanderException(
                code = "invalid_response",
                message = "POST $path returned ${res.status} without a JSON object",
                statusCode = res.status,
            )
        }
        val error = json?.opt("error") as? JSONObject
        throw LanderException(
            code = error?.optStringOrNull("code") ?: "http_${res.status}",
            message = error?.optStringOrNull("message") ?: "POST $path returned ${res.status}",
            statusCode = res.status,
            retryAfter = retryAfter(res.headers["retry-after"]),
        )
    }

    private fun timeout(path: String) =
        LanderException(code = "timeout", message = "POST $path timed out after ${timeoutMs / 1000}s")

    companion object {
        fun decode(body: String): JSONObject? {
            if (body.isBlank()) return null
            return try {
                JSONTokener(body).nextValue() as? JSONObject
            } catch (_: JSONException) {
                null
            }
        }

        fun retryAfter(header: String?): kotlin.time.Duration? {
            val seconds = header?.trim()?.toIntOrNull() ?: return null
            return seconds.coerceIn(0, 3600).seconds
        }
    }
}
