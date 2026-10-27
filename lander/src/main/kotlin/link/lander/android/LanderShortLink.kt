package link.lander.android

import android.net.Uri
import link.lander.android.internal.Iso8601
import link.lander.android.internal.optStringOrNull
import link.lander.android.internal.paramsFrom
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date

/** A short link created with [Lander.createLink]. */
public class LanderShortLink(
    public val id: String,
    /** The shareable URL, for example `https://acme.golander.link/x7Kp2Qa`. */
    public val url: String,
    public val domain: String,
    public val slug: String,
    /** The in app route the link opens. */
    public val path: String,
    public val params: Map<String, Any> = emptyMap(),
    public val title: String? = null,
    public val campaign: String? = null,
    public val channel: String? = null,
    public val tags: List<String> = emptyList(),
    public val expiresAt: Date? = null,
    public val createdAt: Date? = null,
) {
    /** [url] as an [Uri]. */
    public val uri: Uri by lazy(LazyThreadSafetyMode.PUBLICATION) { Uri.parse(url) }

    override fun equals(other: Any?): Boolean = other is LanderShortLink && other.id == id && other.url == url

    override fun hashCode(): Int = 31 * id.hashCode() + url.hashCode()

    override fun toString(): String = url

    internal companion object {
        /** Throws [IllegalArgumentException] when `id` or `url` is missing. */
        fun fromJson(json: JSONObject): LanderShortLink {
            val id = requireNotNull(json.optStringOrNull("id")) { "missing id" }
            val url = requireNotNull(json.optStringOrNull("url")) { "missing url" }
            val tags = json.opt("tags")
            return LanderShortLink(
                id = id,
                url = url,
                domain = json.optStringOrNull("domain") ?: "",
                slug = json.optStringOrNull("slug") ?: "",
                path = json.optStringOrNull("deepLinkPath") ?: "/",
                params = paramsFrom(json.opt("params")),
                title = json.optStringOrNull("title"),
                campaign = json.optStringOrNull("campaign"),
                channel = json.optStringOrNull("channel"),
                tags = if (tags is JSONArray) List(tags.length()) { tags.get(it).toString() } else emptyList(),
                expiresAt = json.optStringOrNull("expiresAt")?.let(Iso8601::parse),
                createdAt = json.optStringOrNull("createdAt")?.let(Iso8601::parse),
            )
        }
    }
}
