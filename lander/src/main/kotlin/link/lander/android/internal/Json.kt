package link.lander.android.internal

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger

/** The string at [key], or null when it is missing or not a string. */
internal fun JSONObject.optStringOrNull(key: String): String? = opt(key) as? String

/** Link params from a JSON value: only string, number and boolean values are kept. */
internal fun paramsFrom(value: Any?): Map<String, Any> {
    if (value !is JSONObject) return emptyMap()
    val out = linkedMapOf<String, Any>()
    for (key in value.keys()) {
        when (val v = value.opt(key)) {
            is String, is Boolean -> out[key] = v
            is BigDecimal -> out[key] = v.toDouble()
            is BigInteger -> out[key] = v.toLong()
            is Number -> out[key] = v
        }
    }
    return out
}

/**
 * Converts a Kotlin value (maps, lists, strings, numbers, booleans, null) to
 * its org.json form. Throws [IllegalArgumentException] for anything that
 * cannot be sent as JSON, so a bad value fails where it is passed in.
 */
internal fun toJsonValue(value: Any?, where: String): Any = when (value) {
    null -> JSONObject.NULL
    is String, is Boolean, is Int, is Long, is Short, is Byte, is BigInteger, is BigDecimal -> value
    is Double -> value.also { require(it.isFinite()) { "$where must be finite" } }
    is Float -> value.toDouble().also { require(it.isFinite()) { "$where must be finite" } }
    is Map<*, *> -> JSONObject().apply {
        for ((k, v) in value) {
            require(k is String) { "$where keys must be strings" }
            put(k, toJsonValue(v, "$where.$k"))
        }
    }
    is Iterable<*> -> JSONArray().apply { value.forEachIndexed { i, v -> put(toJsonValue(v, "$where[$i]")) } }
    is Array<*> -> toJsonValue(value.toList(), where)
    else -> throw IllegalArgumentException("$where must be JSON encodable, got ${value::class.java.name}")
}
