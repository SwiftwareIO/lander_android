package link.lander.android

import kotlin.time.Duration

/**
 * Thrown by [Lander] calls that need an answer from the API, such as
 * [Lander.createLink]. Fire and forget calls ([Lander.initialize],
 * [Lander.trackEvent]) never throw on network or server errors.
 */
public class LanderException(
    /**
     * The API's error code (for example `rate_limited`), or `network_error`,
     * `timeout`, `invalid_response` or `install_not_registered` for client side
     * failures.
     */
    public val code: String,
    override val message: String,
    /** HTTP status, or null when no response was received. */
    public val statusCode: Int? = null,
    /** Server requested delay before retrying, from the `Retry-After` header. */
    public val retryAfter: Duration? = null,
) : Exception(message) {
    /** [retryAfter] in milliseconds, for Java callers. */
    public val retryAfterMillis: Long? get() = retryAfter?.inWholeMilliseconds

    /**
     * Whether retrying the same request later may succeed.
     *
     * `link_quota_exceeded` is a 429 but frees up only as the rolling 24 hour
     * window moves, so retrying soon cannot help. `invalid_response` usually
     * comes from something between the app and Lander (a captive portal).
     */
    public val isRetryable: Boolean
        get() {
            if (code == "link_quota_exceeded") return false
            if (code == "invalid_response") return true
            val s = statusCode ?: return true
            return s == 408 || s == 429 || s >= 500
        }

    override fun toString(): String =
        "LanderException($code${statusCode?.let { ", $it" } ?: ""}): $message"
}
