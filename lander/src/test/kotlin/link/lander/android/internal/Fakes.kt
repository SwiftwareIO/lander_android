package link.lander.android.internal

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import link.lander.android.LanderLink
import org.json.JSONObject
import java.io.IOException

internal const val TEST_KEY = "pk_test_DemoPublishableKey000000000000"

internal class FakePlatform(
    val storage: MutableMap<String, String> = mutableMapOf(),
    var referrer: String? = null,
    var appVersion: String = "1.2.3",
    var osVersion: String? = "15",
) : LanderPlatform {
    var referrerCalls = 0
    var failWrites = 0

    override fun appVersion(): String = appVersion
    override fun osVersion(): String? = osVersion

    override suspend fun installReferrer(): String? {
        referrerCalls++
        return referrer
    }

    override fun read(key: String): String? = storage[key]

    override fun write(key: String, value: String) {
        if (failWrites > 0) {
            failWrites--
            throw IOException("disk full")
        }
        storage[key] = value
    }

    override fun delete(key: String) {
        storage.remove(key)
    }
}

internal class Recorded(val path: String, val headers: Map<String, String>, val body: JSONObject)

/** Answers by path: queued responses first, then [defaults]. */
internal class FakeTransport : HttpTransport {
    val requests = mutableListOf<Recorded>()
    private val queued = mutableMapOf<String, ArrayDeque<suspend (Recorded) -> HttpResponse>>()

    fun on(path: String, vararg responses: suspend (Recorded) -> HttpResponse) {
        queued.getOrPut(path) { ArrayDeque() }.addAll(responses)
    }

    fun requestsTo(path: String) = requests.filter { it.path == path }

    var defaults: suspend (Recorded) -> HttpResponse = { r ->
        when (r.path) {
            "/v1/sdk/init" -> json(200, """{"installId":"${r.body.getString("installId")}","deferredLink":null}""")
            "/v1/sdk/resolve" -> json(404, """{"error":{"code":"link_not_found","message":"Not found"}}""")
            "/v1/sdk/events" -> json(202, """{"accepted":${r.body.getJSONArray("events").length()},"duplicates":0}""")
            "/v1/sdk/handoffs/claim" -> json(200, """{"deferredLink":null}""")
            else -> json(404, """{"error":{"code":"not_found","message":"Not found"}}""")
        }
    }

    override suspend fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Long): HttpResponse {
        val path = url.removePrefix(BASE_URL)
        val r = Recorded(path, headers, JSONObject(body))
        requests += r
        val handler = queued[path]?.removeFirstOrNull() ?: defaults
        return handler(r)
    }

    companion object {
        const val BASE_URL = "http://api.test"
    }
}

internal fun json(status: Int, body: String, headers: Map<String, String> = emptyMap()): HttpResponse =
    HttpResponse(status, body, headers)

internal val offline: suspend (Recorded) -> HttpResponse = { throw IOException("offline") }

internal fun resolved(
    linkId: String = "11111111-1111-4111-8111-111111111111",
    path: String = "/product/123",
    url: String = "https://acme.golander.link/p-123",
    isDeferred: Boolean = false,
    confidence: String = "deterministic",
    method: String = "app_link",
    params: String = """{"productId":"123"}""",
): String =
    """{"linkId":"$linkId","url":"$url","path":"$path","params":$params,"campaign":"summer","channel":"share",""" +
        """"isDeferred":$isDeferred,"confidence":"$confidence","method":"$method"}"""

internal class TestLog {
    val lines = mutableListOf<String>()
    val logger = LanderLogger(true) { lines += it }
}

internal fun TestScope.newClient(
    platform: FakePlatform = FakePlatform(),
    transport: FakeTransport = FakeTransport(),
    dispatcher: LinkDispatcher = LinkDispatcher(),
    log: TestLog = TestLog(),
): LanderClient {
    val d = StandardTestDispatcher(testScheduler)
    return LanderClient(
        apiKey = TEST_KEY,
        baseUrl = FakeTransport.BASE_URL,
        dispatcher = dispatcher,
        platform = platform,
        transport = transport,
        logger = log.logger,
        serial = d,
        io = d,
        clock = { testScheduler.currentTime },
        sdkVersion = "0.1.0",
        eventBackoff = Backoff(baseMs = 1_000, maxMs = 300_000, jitter = 0.0),
        requestBackoff = Backoff(baseMs = 500, maxMs = 4_000, jitter = 0.0),
    )
}

internal fun LanderLink.short() = "$linkId $route deferred=$isDeferred ${confidence.wireName} $method"
