package link.lander.android.internal

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import link.lander.android.LanderException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class LanderApiTest {
    private val transport = FakeTransport()
    private val api = LanderApi(
        apiKey = TEST_KEY,
        baseUrl = FakeTransport.BASE_URL + "/",
        transport = transport,
        logger = TestLog().logger,
        sdkVersion = "0.1.0",
        backoff = Backoff(baseMs = 500, maxMs = 4_000, jitter = 0.0),
    )

    private suspend fun expectError(block: suspend () -> Unit): LanderException {
        try {
            block()
        } catch (e: LanderException) {
            return e
        }
        fail("expected LanderException")
        throw AssertionError()
    }

    @Test
    fun `sends auth, sdk and idempotency headers`() = runTest {
        transport.on("/v1/sdk/links", { json(201, """{"id":"x"}""") })
        val res = api.post("/v1/sdk/links", JSONObject().put("a", 1), idempotencyKey = "key-1")
        assertEquals("x", res.getString("id"))
        val r = transport.requests.single()
        assertEquals("Bearer $TEST_KEY", r.headers["Authorization"])
        assertEquals("android/0.1.0", r.headers["X-Lander-Sdk"])
        assertEquals("application/json", r.headers["Content-Type"])
        assertEquals("key-1", r.headers["Idempotency-Key"])
        assertEquals(1, r.body.getInt("a"))
    }

    @Test
    fun `parses the API error body`() = runTest {
        transport.on("/x", { json(409, """{"error":{"code":"install_id_conflict","message":"Taken"}}""") })
        val e = expectError { api.post("/x", JSONObject()) }
        assertEquals("install_id_conflict", e.code)
        assertEquals("Taken", e.message)
        assertEquals(409, e.statusCode)
        assertFalse(e.isRetryable)
    }

    @Test
    fun `retries retryable failures with the same idempotency key`() = runTest {
        transport.on("/x", offline, { json(503, "") }, { json(200, """{"ok":true}""") })
        val res = api.post("/x", JSONObject(), idempotencyKey = "same", maxAttempts = 3)
        assertTrue(res.getBoolean("ok"))
        assertEquals(listOf("same", "same", "same"), transport.requests.map { it.headers["Idempotency-Key"] })
        // 500 ms then 1000 ms of backoff.
        assertEquals(1_500, currentTime)
    }

    @Test
    fun `honors Retry-After when it is longer than the backoff`() = runTest {
        transport.on("/x", { json(429, """{"error":{"code":"rate_limited","message":"Slow down"}}""", mapOf("retry-after" to "3")) }, { json(200, "{}") })
        api.post("/x", JSONObject(), maxAttempts = 2)
        assertEquals(3_000, currentTime)
    }

    @Test
    fun `a Retry-After beyond the total retry budget comes back to the caller`() = runTest {
        transport.on("/x", { json(429, """{"error":{"code":"rate_limited","message":"Slow down"}}""", mapOf("retry-after" to "30")) })
        val e = expectError { api.post("/x", JSONObject(), maxAttempts = 3) }
        assertEquals("rate_limited", e.code)
        assertEquals(30.seconds, e.retryAfter)
        assertEquals(30_000L, e.retryAfterMillis)
        assertEquals(1, transport.requests.size)
        assertEquals(0, currentTime)
    }

    @Test
    fun `does not retry client errors or link_quota_exceeded`() = runTest {
        transport.on("/x", { json(429, """{"error":{"code":"link_quota_exceeded","message":"Quota"}}""") })
        val e = expectError { api.post("/x", JSONObject(), maxAttempts = 3) }
        assertEquals("link_quota_exceeded", e.code)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `a 2xx response that is not a JSON object is invalid_response`() = runTest {
        transport.on("/x", { json(200, "<html>captive portal</html>") })
        val e = expectError { api.post("/x", JSONObject()) }
        assertEquals("invalid_response", e.code)
        assertTrue(e.isRetryable)
    }

    @Test
    fun `204 without a body is an empty object`() = runTest {
        transport.on("/x", { json(204, "") })
        assertEquals(0, api.post("/x", JSONObject()).length())
    }

    @Test
    fun `network errors and timeouts have their own codes`() = runTest {
        transport.on("/x", offline, { delay(60_000); json(200, "{}") })
        assertEquals("network_error", expectError { api.post("/x", JSONObject()) }.code)
        val timeout = expectError { api.post("/x", JSONObject()) }
        assertEquals("timeout", timeout.code)
        assertNull(timeout.statusCode)
        assertEquals(10_000, currentTime)
    }

    @Test
    fun `Retry-After is clamped to an hour`() {
        assertEquals(3600.seconds, LanderApi.retryAfter("99999"))
        assertNull(LanderApi.retryAfter("Wed, 21 Oct 2026 07:28:00 GMT"))
    }
}
