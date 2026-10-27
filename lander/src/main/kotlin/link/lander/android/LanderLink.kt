package link.lander.android

import android.net.Uri
import link.lander.android.internal.ParsedUri
import link.lander.android.internal.optStringOrNull
import link.lander.android.internal.paramsFrom
import org.json.JSONObject

/** How sure Lander is that a link caused this open or install. */
public enum class LanderConfidence(
    /** The value the API uses, for example `deterministic`. */
    public val wireName: String,
) {
    /**
     * Matched by an exact identifier, for example an App Link open or a Play
     * Install Referrer token.
     */
    DETERMINISTIC("deterministic"),

    /**
     * Matched by coarse signals (OS version, country, click time) within a
     * short window. Only for iOS deferred links of projects that opted in.
     */
    PROBABILISTIC("probabilistic"),

    /** No link could be tied to this open. */
    UNATTRIBUTED("unattributed"),
    ;

    public companion object {
        /** Parses the API's value; anything unknown is [UNATTRIBUTED]. */
        @JvmStatic
        public fun parse(value: Any?): LanderConfidence =
            entries.firstOrNull { it.wireName == value } ?: UNATTRIBUTED
    }
}

/**
 * A deep link delivered to the app.
 *
 * When Lander knows the link, the fields mirror the `ResolvedLink` returned by
 * `POST /v1/sdk/resolve`. When it does not (unknown link, custom scheme URL,
 * or the network was unavailable) only the URI is set, [isResolved] is false
 * and the app can still route on the raw URL.
 */
public class LanderLink(
    /** The URL that opened the app, exactly as Android delivered it. */
    public val uriString: String,
    /** Lander link ID, or null when the link is not known to Lander. */
    public val linkId: String? = null,
    /** The canonical short link, for example `https://acme.golander.link/p-123`. */
    public val url: String? = null,
    /** The in app route configured on the link, for example `/product/123`. */
    public val path: String? = null,
    /**
     * The link's stored params with the opened URL's query parameters merged
     * over them. Values are `String`, `Number` or `Boolean`.
     */
    public val params: Map<String, Any> = emptyMap(),
    public val campaign: String? = null,
    public val channel: String? = null,
    /**
     * True when the link was recovered after an install or handed off after
     * sign in (a deferred deep link), rather than opened directly.
     */
    public val isDeferred: Boolean = false,
    /** How sure Lander is that this link belongs to this open or install. */
    public val confidence: LanderConfidence = LanderConfidence.UNATTRIBUTED,
    /**
     * How the link was tied to this open or install, as reported by the API:
     * `app_link`, `universal_link`, `install_referrer`, `landing_page`,
     * `authenticated_handoff` or `probabilistic_match`. Null when unresolved.
     */
    public val method: String? = null,
) {
    /** The URL that opened the app, as an [Uri]. */
    public val uri: Uri by lazy(LazyThreadSafetyMode.PUBLICATION) { Uri.parse(uriString) }

    /** Whether Lander recognized this link. */
    public val isResolved: Boolean get() = linkId != null

    /**
     * The route to navigate to: the link's [path] when resolved, otherwise the
     * raw URL's path and query. For custom scheme URLs the host counts as the
     * first path segment, so `acmeshop://product/123` gives `/product/123`.
     */
    public val route: String
        get() {
            path?.let { return it }
            val parsed = ParsedUri.parse(uriString)
            val isWeb = parsed.scheme == "http" || parsed.scheme == "https"
            var raw = if (!isWeb && parsed.host.isNotEmpty()) "/${parsed.host}${parsed.path}" else parsed.path
            if (raw.isEmpty()) raw = "/"
            return if (parsed.query != null) "$raw?${parsed.query}" else raw
        }

    override fun equals(other: Any?): Boolean =
        other is LanderLink &&
            other.uriString == uriString &&
            other.linkId == linkId &&
            other.url == url &&
            other.path == path &&
            other.params == params &&
            other.campaign == campaign &&
            other.channel == channel &&
            other.isDeferred == isDeferred &&
            other.confidence == confidence &&
            other.method == method

    override fun hashCode(): Int {
        var result = uriString.hashCode()
        result = 31 * result + (linkId?.hashCode() ?: 0)
        result = 31 * result + (path?.hashCode() ?: 0)
        result = 31 * result + isDeferred.hashCode()
        result = 31 * result + confidence.hashCode()
        return result
    }

    override fun toString(): String =
        if (isResolved) {
            "LanderLink($linkId, path: $path, params: $params, uri: $uriString" +
                (if (isDeferred) ", deferred" else "") +
                ", ${confidence.wireName}" +
                (method?.let { " via $it" } ?: "") + ")"
        } else {
            "LanderLink(unresolved, uri: $uriString)"
        }

    public companion object {
        /** A link Lander could not resolve. Only the raw URI is available. */
        @JvmStatic
        public fun unresolved(uri: String): LanderLink = LanderLink(uriString = uri)

        /**
         * Builds a link from a `ResolvedLink` JSON object. [uri] is the URL that
         * opened the app; for deferred links it falls back to the link's own URL.
         *
         * Never throws: fields of an unexpected type are treated as missing, so a
         * partly malformed response still delivers what it can.
         */
        internal fun fromJson(json: JSONObject, uri: String? = null): LanderLink {
            val url = json.optStringOrNull("url")
            return LanderLink(
                uriString = uri ?: url ?: "",
                linkId = json.optStringOrNull("linkId"),
                url = url,
                path = json.optStringOrNull("path"),
                params = paramsFrom(json.opt("params")),
                campaign = json.optStringOrNull("campaign"),
                channel = json.optStringOrNull("channel"),
                isDeferred = json.opt("isDeferred") == true,
                confidence = LanderConfidence.parse(json.opt("confidence")),
                method = json.optStringOrNull("method"),
            )
        }
    }
}
