package link.lander.android

import link.lander.android.internal.Iso8601
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date

/**
 * What to put in a link made with [Lander.createLink]. Kotlin callers can pass
 * the same fields as named arguments to the suspending [Lander.createLink];
 * Java callers build one with [Builder].
 *
 * ```java
 * LanderLinkOptions options = new LanderLinkOptions.Builder()
 *     .path("/product/123")
 *     .param("productId", "123")
 *     .channel("share")
 *     .build();
 * ```
 */
public class LanderLinkOptions private constructor(builder: Builder) {
    /** The in app route the link opens. Must start with `/`. */
    public val path: String = builder.path

    /** Values are `String`, `Number` or `Boolean`. */
    public val params: Map<String, Any> = builder.params.toMap()
    public val title: String? = builder.title
    public val campaign: String? = builder.campaign
    public val channel: String? = builder.channel
    public val tags: List<String> = builder.tags.toList()
    public val iosFallbackUrl: String? = builder.iosFallbackUrl
    public val androidFallbackUrl: String? = builder.androidFallbackUrl
    public val webFallbackUrl: String? = builder.webFallbackUrl
    public val ogTitle: String? = builder.ogTitle
    public val ogDescription: String? = builder.ogDescription
    public val ogImageUrl: String? = builder.ogImageUrl
    public val expiresAt: Date? = builder.expiresAt

    init {
        require(path.startsWith("/")) { "path must start with /" }
        for ((key, value) in params) {
            require(value is String || value is Number || value is Boolean) {
                "params[$key] must be a String, Number or Boolean"
            }
            if (value is Double) require(value.isFinite()) { "params[$key] must be finite" }
            if (value is Float) require(value.isFinite()) { "params[$key] must be finite" }
        }
    }

    internal fun toJson(): JSONObject = JSONObject().apply {
        put("deepLinkPath", path)
        put("params", JSONObject().apply { params.forEach { (k, v) -> put(k, v) } })
        title?.let { put("title", it) }
        campaign?.let { put("campaign", it) }
        channel?.let { put("channel", it) }
        if (tags.isNotEmpty()) put("tags", JSONArray(tags))
        iosFallbackUrl?.let { put("iosFallbackUrl", it) }
        androidFallbackUrl?.let { put("androidFallbackUrl", it) }
        webFallbackUrl?.let { put("webFallbackUrl", it) }
        ogTitle?.let { put("ogTitle", it) }
        ogDescription?.let { put("ogDescription", it) }
        ogImageUrl?.let { put("ogImageUrl", it) }
        expiresAt?.let { put("expiresAt", Iso8601.format(it)) }
    }

    /** Builds [LanderLinkOptions]. Every field is optional; [path] defaults to `/`. */
    public class Builder {
        internal var path: String = "/"
        internal val params: MutableMap<String, Any> = linkedMapOf()
        internal var title: String? = null
        internal var campaign: String? = null
        internal var channel: String? = null
        internal val tags: MutableList<String> = mutableListOf()
        internal var iosFallbackUrl: String? = null
        internal var androidFallbackUrl: String? = null
        internal var webFallbackUrl: String? = null
        internal var ogTitle: String? = null
        internal var ogDescription: String? = null
        internal var ogImageUrl: String? = null
        internal var expiresAt: Date? = null

        public fun path(path: String): Builder = apply { this.path = path }
        public fun param(key: String, value: Any): Builder = apply { params[key] = value }
        public fun params(params: Map<String, Any>): Builder = apply { this.params.putAll(params) }
        public fun title(title: String?): Builder = apply { this.title = title }
        public fun campaign(campaign: String?): Builder = apply { this.campaign = campaign }
        public fun channel(channel: String?): Builder = apply { this.channel = channel }
        public fun tags(tags: List<String>): Builder = apply { this.tags.addAll(tags) }
        public fun iosFallbackUrl(url: String?): Builder = apply { iosFallbackUrl = url }
        public fun androidFallbackUrl(url: String?): Builder = apply { androidFallbackUrl = url }
        public fun webFallbackUrl(url: String?): Builder = apply { webFallbackUrl = url }
        public fun ogTitle(title: String?): Builder = apply { ogTitle = title }
        public fun ogDescription(description: String?): Builder = apply { ogDescription = description }
        public fun ogImageUrl(url: String?): Builder = apply { ogImageUrl = url }
        public fun expiresAt(date: Date?): Builder = apply { expiresAt = date }

        /** Throws [IllegalArgumentException] for a path without a leading `/` or bad params. */
        public fun build(): LanderLinkOptions = LanderLinkOptions(this)
    }
}
