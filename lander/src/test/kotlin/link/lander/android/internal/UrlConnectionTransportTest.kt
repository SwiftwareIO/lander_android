package link.lander.android.internal

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.json.JSONObject

class UrlConnectionTransportTest {
    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    private fun api() = LanderApi(
        apiKey = TEST_KEY,
        baseUrl = server.url("/").toString(),
        transport = UrlConnectionTransport(),
        logger = TestLog().logger,
        sdkVersion = "0.1.0",
    )

    @Test
    fun `posts JSON with headers and decodes a UTF-8 response`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"title":"Café ☕"}"""),
        )
        val res = api().post("/v1/sdk/links", JSONObject().put("title", "Crème"), idempotencyKey = "k1")
        assertEquals("Café ☕", res.getString("title"))
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/sdk/links", req.path)
        assertEquals("Bearer $TEST_KEY", req.getHeader("Authorization"))
        assertEquals("android/0.1.0", req.getHeader("X-Lander-Sdk"))
        assertEquals("k1", req.getHeader("Idempotency-Key"))
        assertEquals("Crème", JSONObject(req.body.readUtf8()).getString("title"))
    }

    @Test
    fun `reads the error body and Retry-After of a failed response`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(429).setHeader("Retry-After", "60")
                .setBody("""{"error":{"code":"rate_limited","message":"Too many requests"}}"""),
        )
        val e = runCatching { api().post("/v1/sdk/links", JSONObject(), maxAttempts = 2) }.exceptionOrNull()
            as link.lander.android.LanderException
        assertEquals("rate_limited", e.code)
        assertEquals(429, e.statusCode)
        assertEquals(60_000L, e.retryAfterMillis)
        assertEquals(1, server.requestCount)
    }
}
