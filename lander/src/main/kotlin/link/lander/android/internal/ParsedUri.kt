package link.lander.android.internal

import java.net.URLDecoder

/**
 * A lenient URI split (RFC 3986 appendix B), usable in plain JVM code where
 * android.net.Uri is not available. Scheme and host are lowercased.
 */
internal class ParsedUri private constructor(
    val scheme: String,
    val host: String,
    val path: String,
    /** The raw query without `?`, or null when there is no `?`. */
    val query: String?,
) {
    /** The first value of query parameter [name], decoded, or null. */
    fun queryParameter(name: String): String? {
        val q = query ?: return null
        for (pair in q.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val key = decode(if (eq < 0) pair else pair.substring(0, eq))
            if (key == name) return if (eq < 0) "" else decode(pair.substring(eq + 1))
        }
        return null
    }

    companion object {
        private val pattern = Regex("^(([^:/?#]+):)?(//([^/?#]*))?([^?#]*)(\\?([^#]*))?(#(.*))?")

        fun parse(raw: String): ParsedUri {
            val m = pattern.find(raw)!!
            val authority = m.groups[4]?.value ?: ""
            val hostPort = authority.substringAfterLast('@')
            val host = if (hostPort.startsWith("[")) {
                hostPort.substringBefore(']') + "]"
            } else {
                hostPort.substringBefore(':')
            }
            return ParsedUri(
                scheme = (m.groups[2]?.value ?: "").lowercase(),
                host = host.lowercase(),
                path = m.groups[5]?.value ?: "",
                query = m.groups[7]?.value,
            )
        }

        private fun decode(s: String): String = try {
            URLDecoder.decode(s, "UTF-8")
        } catch (_: IllegalArgumentException) {
            s
        }
    }
}
