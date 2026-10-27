package link.lander.android

import link.lander.android.internal.Iso8601
import link.lander.android.internal.ParsedUri
import link.lander.android.internal.Uuids
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class ModelsTest {
    @Test
    fun `fromJson keeps what it can from a partly malformed link`() {
        val link = LanderLink.fromJson(
            JSONObject("""{"linkId":"id-1","path":42,"params":{"a":"1","b":2,"c":true,"d":{"x":1},"e":null},"confidence":"nope","isDeferred":"yes"}"""),
            uri = "https://acme.golander.link/x",
        )
        assertEquals("id-1", link.linkId)
        assertNull(link.path)
        assertEquals(mapOf("a" to "1", "b" to 2, "c" to true), link.params)
        assertEquals(LanderConfidence.UNATTRIBUTED, link.confidence)
        assertFalse(link.isDeferred)
        assertEquals("/x", link.route)
    }

    @Test
    fun `a deferred link without a uri falls back to its own url`() {
        val link = LanderLink.fromJson(JSONObject("""{"linkId":"id","url":"https://acme.golander.link/p-1","isDeferred":true}"""))
        assertEquals("https://acme.golander.link/p-1", link.uriString)
        assertTrue(link.isDeferred)
    }

    @Test
    fun `route uses the path, else the raw URL, with the host first for custom schemes`() {
        assertEquals("/p", LanderLink(uriString = "https://x.test/a", linkId = "id", path = "/p").route)
        assertEquals("/a/b?c=d", LanderLink.unresolved("https://x.test/a/b?c=d").route)
        assertEquals("/", LanderLink.unresolved("https://x.test").route)
        assertEquals("/product/123", LanderLink.unresolved("acmeshop://product/123").route)
        assertEquals("/product/123", LanderLink.unresolved("AcmeShop://Product/123").route)
        assertEquals("/settings", LanderLink.unresolved("acmeshop:///settings").route)
    }

    @Test
    fun `equality and toString`() {
        val a = LanderLink(uriString = "u", linkId = "id", params = mapOf("a" to "1"))
        val b = LanderLink(uriString = "u", linkId = "id", params = mapOf("a" to "1"))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals("LanderLink(unresolved, uri: x)", LanderLink.unresolved("x").toString())
    }

    @Test
    fun `parsed URIs decode query parameters`() {
        val uri = ParsedUri.parse("acmeshop://open?lander_link=https%3A%2F%2Fa.test%2Fp&x=a+b&flag")
        assertEquals("acmeshop", uri.scheme)
        assertEquals("open", uri.host)
        assertEquals("https://a.test/p", uri.queryParameter("lander_link"))
        assertEquals("a b", uri.queryParameter("x"))
        assertEquals("", uri.queryParameter("flag"))
        assertNull(uri.queryParameter("missing"))
        assertEquals("example.com", ParsedUri.parse("https://user@Example.com:8443/p").host)
    }

    @Test
    fun `ISO 8601 dates round trip`() {
        val date = Date(1_792_058_400_123L)
        assertEquals("2026-10-15T10:00:00.123Z", Iso8601.format(date))
        assertEquals(date, Iso8601.parse("2026-10-15T10:00:00.123Z"))
        assertEquals(date, Iso8601.parse("2026-10-15T12:00:00.123+02:00"))
        assertEquals(Date(1_792_058_400_000L), Iso8601.parse("2026-10-15T10:00:00Z"))
        assertEquals(Date(1_792_058_400_123L), Iso8601.parse("2026-10-15T10:00:00.123456Z"))
        assertNull(Iso8601.parse("yesterday"))
    }

    @Test
    fun `UUID helpers`() {
        assertTrue(Uuids.isValid(Uuids.v4()))
        assertFalse(Uuids.isValid("not-a-uuid"))
        assertFalse(Uuids.isValid(null))
        // RFC 4122 version 5 in the URL namespace, as Python's uuid5 and Dart's uuid compute it.
        assertEquals("25e627ae-39d7-5fe8-9893-af1f1b49d7ea", Uuids.v5("lander:install:a"))
    }

    @Test
    fun `LanderException retryability`() {
        assertTrue(LanderException("network_error", "x").isRetryable)
        assertTrue(LanderException("rate_limited", "x", statusCode = 429).isRetryable)
        assertTrue(LanderException("internal_error", "x", statusCode = 500).isRetryable)
        assertFalse(LanderException("link_quota_exceeded", "x", statusCode = 429).isRetryable)
        assertFalse(LanderException("validation_error", "x", statusCode = 400).isRetryable)
        assertTrue(LanderException("invalid_response", "x", statusCode = 200).isRetryable)
    }

    @Test
    fun `link options validate and serialize`() {
        val json = LanderLinkOptions.Builder().path("/p").param("n", 1).campaign("c").expiresAt(Date(0)).build().toJson()
        assertEquals("/p", json.getString("deepLinkPath"))
        assertEquals(1, json.getJSONObject("params").getInt("n"))
        assertEquals("1970-01-01T00:00:00.000Z", json.getString("expiresAt"))
        assertFalse(json.has("tags"))
        assertThrows { LanderLinkOptions.Builder().path("p").build() }
        assertThrows { LanderLinkOptions.Builder().param("x", listOf(1)).build() }
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected IllegalArgumentException")
    }
}
