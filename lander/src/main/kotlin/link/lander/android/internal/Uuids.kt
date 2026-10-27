package link.lander.android.internal

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

internal object Uuids {
    /** RFC 4122 URL namespace, as used by the Flutter SDK for its stable keys. */
    private val urlNamespace = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8")

    // The same shape zod's z.uuid() accepts (version 1 to 8, RFC variant).
    private val pattern = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$",
    )

    fun v4(): String = UUID.randomUUID().toString()

    /** Name based UUID (version 5, SHA-1) in the URL namespace: stable for the same [name]. */
    fun v5(name: String): String {
        val ns = ByteBuffer.allocate(16)
            .putLong(urlNamespace.mostSignificantBits)
            .putLong(urlNamespace.leastSignificantBits)
            .array()
        val sha1 = MessageDigest.getInstance("SHA-1").apply {
            update(ns)
            update(name.toByteArray(Charsets.UTF_8))
        }.digest()
        sha1[6] = ((sha1[6].toInt() and 0x0f) or 0x50).toByte()
        sha1[8] = ((sha1[8].toInt() and 0x3f) or 0x80).toByte()
        val buf = ByteBuffer.wrap(sha1, 0, 16)
        return UUID(buf.long, buf.long).toString()
    }

    fun isValid(value: String?): Boolean = value != null && pattern.matches(value)
}
