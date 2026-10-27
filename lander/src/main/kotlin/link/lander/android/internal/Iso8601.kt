package link.lander.android.internal

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** ISO 8601 timestamps as the API sends and accepts them, without java.time (API 26+). */
internal object Iso8601 {
    private val utc = TimeZone.getTimeZone("UTC")
    private val pattern = Regex(
        "^(\\d{4})-(\\d{2})-(\\d{2})[Tt ](\\d{2}):(\\d{2})(?::(\\d{2})(?:\\.(\\d+))?)?(Z|z|[+-]\\d{2}:?\\d{2})?$",
    )

    /** UTC with milliseconds, for example `2026-10-15T10:00:00.000Z`. */
    fun format(date: Date): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = utc }.format(date)

    /** Parses an ISO 8601 date time, or returns null. A missing offset means UTC. */
    fun parse(value: String): Date? {
        val m = pattern.find(value.trim()) ?: return null
        val g = m.groupValues
        val cal = Calendar.getInstance(utc, Locale.US).apply {
            clear()
            set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].ifEmpty { "0" }.toInt())
            set(Calendar.MILLISECOND, g[7].padEnd(3, '0').take(3).toInt())
        }
        var millis = cal.timeInMillis
        val offset = g[8]
        if (offset.isNotEmpty() && offset != "Z" && offset != "z") {
            val digits = offset.substring(1).replace(":", "")
            val minutes = digits.substring(0, 2).toInt() * 60 + digits.substring(2, 4).toInt()
            millis -= (if (offset[0] == '-') -1 else 1) * minutes * 60_000L
        }
        return Date(millis)
    }
}
