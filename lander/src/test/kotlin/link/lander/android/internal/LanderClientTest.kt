@file:OptIn(ExperimentalCoroutinesApi::class)

package link.lander.android.internal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import link.lander.android.LanderConfidence
import link.lander.android.LanderException
import link.lander.android.LanderLink
import link.lander.android.LanderLinkOptions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LanderClientTest {
    private val platform = FakePlatform()
    private val transport = FakeTransport()
    private val dispatcher = LinkDispatcher()
    private val clients = mutableListOf<LanderClient>()

    @After
    fun tearDown() = clients.forEach { it.dispose() }

    private fun TestScope.start(
        p: FakePlatform = platform,
        t: FakeTransport = transport,
        d: LinkDispatcher = dispatcher,
    ): LanderClient = newClient(p, t, d).also {
        clients += it
        it.start()
    }

    private fun TestScope.collect(d: LinkDispatcher = dispatcher): MutableList<LanderLink> {
        val got = mutableListOf<LanderLink>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { d.flow.collect { got += it } }
        return got
    }

    private fun inits() = transport.requestsTo("/v1/sdk/init").map { it.body }

    private fun deferredInit(
        body: String = resolved(isDeferred = true, method = "install_referrer"),
        delayMs: Long = 0,
    ): suspend (Recorded) -> HttpResponse = { r ->
        delay(delayMs)
        json(200, """{"installId":"${r.body.getString("installId")}","deferredLink":$body}""")
    }

    private suspend inline fun <reified T : Throwable> expectThrows(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw e
        }
        fail("expected ${T::class.simpleName}")
        throw AssertionError()
    }

    // Install and init

    @Test
    fun `first launch creates and persists a random install ID and reports isFirstLaunch`() = runTest {
        val client = start()
        client.onLink(null)
        advanceUntilIdle()
        val id = platform.storage[LanderClient.INSTALL_ID_KEY]
        assertTrue(Uuids.isValid(id))
        assertEquals(id, client.installId)
        val body = inits().single()
        assertEquals(id, body.getString("installId"))
        assertEquals("android", body.getString("platform"))
        assertEquals("1.2.3", body.getString("appVersion"))
        assertEquals("0.1.0", body.getString("sdkVersion"))
        assertEquals("15", body.getString("osVersion"))
        assertTrue(body.getBoolean("isFirstLaunch"))
        assertEquals("1", platform.storage[LanderClient.FIRST_LAUNCH_REPORTED_KEY])
    }

    @Test
    fun `later launches reuse the install ID and report isFirstLaunch false`() = runTest {
        start().onLink(null)
        advanceUntilIdle()
        val first = platform.storage[LanderClient.INSTALL_ID_KEY]
        start(d = LinkDispatcher()).onLink(null)
        advanceUntilIdle()
        val second = inits()[1]
        assertEquals(first, second.getString("installId"))
        assertFalse(second.getBoolean("isFirstLaunch"))
        assertFalse(second.has("installReferrer"))
    }

    @Test
    fun `a failed first launch report is retried on the next launch with the same key`() = runTest {
        transport.on("/v1/sdk/init", offline, offline, offline)
        start().onLink(null)
        advanceUntilIdle()
        assertNull(platform.storage[LanderClient.FIRST_LAUNCH_REPORTED_KEY])
        start(d = LinkDispatcher()).onLink(null)
        advanceUntilIdle()
        val keys = transport.requestsTo("/v1/sdk/init").map { it.headers["Idempotency-Key"] }
        assertEquals(4, keys.size)
        assertEquals(1, keys.toSet().size)
        assertTrue(inits().last().getBoolean("isFirstLaunch"))
        assertEquals("1", platform.storage[LanderClient.FIRST_LAUNCH_REPORTED_KEY])
    }

    @Test
    fun `first launch sends the Play Install Referrer, only once`() = runTest {
        platform.referrer = "utm_source=google-play&lander_token=abc"
        start().onLink(null)
        advanceUntilIdle()
        start(d = LinkDispatcher()).onLink(null)
        advanceUntilIdle()
        assertEquals("utm_source=google-play&lander_token=abc", inits()[0].getString("installReferrer"))
        assertFalse(inits()[1].has("installReferrer"))
        assertEquals(1, platform.referrerCalls)
    }

    @Test
    fun `never throws when the network is down`() = runTest {
        transport.defaults = offline
        val client = start()
        client.onLink(null)
        advanceUntilIdle()
        assertTrue(client.initDone.isCompleted)
        assertNull(client.getInitialLink())
    }

    @Test
    fun `a 2xx init that is not JSON does not count as the first launch report`() = runTest {
        transport.on("/v1/sdk/init", { json(200, "<html>portal</html>") }, { json(200, "<html>portal</html>") }, { json(200, "<html>portal</html>") })
        start().onLink(null)
        advanceUntilIdle()
        assertNull(platform.storage[LanderClient.FIRST_LAUNCH_REPORTED_KEY])
    }

    @Test
    fun `appVersion and osVersion are cut to 50 characters`() = runTest {
        platform.appVersion = "1".repeat(80)
        platform.osVersion = "2".repeat(80)
        start().onLink(null)
        advanceUntilIdle()
        assertEquals(50, inits()[0].getString("appVersion").length)
        assertEquals(50, inits()[0].getString("osVersion").length)
    }

    @Test
    fun `a failed first launch marker write is retried before the deferred link is delivered`() = runTest {
        transport.on("/v1/sdk/init", deferredInit(delayMs = 100))
        val client = start()
        client.onLink(null)
        runCurrent()
        platform.failWrites = 2
        advanceUntilIdle()
        assertEquals("1", platform.storage[LanderClient.FIRST_LAUNCH_REPORTED_KEY])
        assertNotNull(client.getInitialLink())
    }

    @Test
    fun `install_id_conflict creates a new install ID and reports a first launch`() = runTest {
        start().onLink(null)
        advanceUntilIdle()
        val oldId = platform.storage[LanderClient.INSTALL_ID_KEY]
        transport.on("/v1/sdk/init", { json(409, """{"error":{"code":"install_id_conflict","message":"Taken"}}""") })
        val client = start(d = LinkDispatcher())
        client.onLink(null)
        advanceUntilIdle()
        val (conflicted, retried) = inits().drop(1)
        assertEquals(oldId, conflicted.getString("installId"))
        assertFalse(conflicted.getBoolean("isFirstLaunch"))
        assertNotEquals(oldId, retried.getString("installId"))
        assertTrue(retried.getBoolean("isFirstLaunch"))
        assertEquals(retried.getString("installId"), client.installId)
        assertEquals(client.installId, platform.storage[LanderClient.INSTALL_ID_KEY])
    }

    @Test
    fun `a failed first launch init is retried when the app comes to the foreground, once at a time`() = runTest {
        transport.on("/v1/sdk/init", offline, offline, offline)
        val client = start()
        client.onLink(null)
        advanceUntilIdle()
        transport.on("/v1/sdk/init", { delay(100); json(200, """{"deferredLink":null}""") })
        client.onForeground()
        client.onForeground()
        advanceUntilIdle()
        assertEquals(4, inits().size)
        client.onForeground()
        advanceUntilIdle()
        assertEquals(4, inits().size)
    }

    // Deferred links

    @Test
    fun `a deferred link from init becomes the initial link`() = runTest {
        platform.referrer = "lander_token=abc"
        transport.on("/v1/sdk/init", deferredInit())
        val client = start()
        client.onLink(null)
        val initial = async { client.getInitialLink() }
        advanceUntilIdle()
        val link = initial.await()!!
        assertTrue(link.isDeferred)
        assertEquals(LanderConfidence.DETERMINISTIC, link.confidence)
        assertEquals("install_referrer", link.method)
        assertEquals("/product/123", link.route)
        assertEquals("https://acme.golander.link/p-123", link.uriString)
        assertNull(client.getInitialLink())
    }

    @Test
    fun `getInitialLink waits for the launch intent`() = runTest {
        transport.on("/v1/sdk/init", deferredInit())
        val client = start()
        val initial = async { client.getInitialLink() }
        advanceUntilIdle()
        assertFalse(initial.isCompleted)
        client.onLink(null)
        advanceUntilIdle()
        assertTrue(initial.await()!!.isDeferred)
    }

    @Test
    fun `a deferred link is delivered exactly once through deepLinks when nobody asks getInitialLink`() = runTest {
        transport.on("/v1/sdk/init", deferredInit())
        val client = start()
        client.onLink(null)
        advanceUntilIdle()
        val got = collect()
        runCurrent()
        assertEquals(1, got.size)
        assertTrue(got[0].isDeferred)
        assertNull(client.getInitialLink())
    }

    @Test
    fun `a link that opened the app wins over a deferred link`() = runTest {
        transport.on("/v1/sdk/init", deferredInit())
        transport.on("/v1/sdk/resolve", { json(200, resolved(linkId = "22222222-2222-4222-8222-222222222222", path = "/sale")) })
        val client = start()
        client.onLink("https://acme.golander.link/sale")
        val got = collect()
        advanceUntilIdle()
        assertEquals(listOf("/sale"), got.map { it.route })
        assertFalse(got[0].isDeferred)
    }

    @Test
    fun `iOS style probabilistic links keep their label`() = runTest {
        transport.on("/v1/sdk/init", deferredInit(resolved(isDeferred = true, confidence = "probabilistic", method = "probabilistic_match")))
        val client = start()
        client.onLink(null)
        val initial = async { client.getInitialLink() }
        advanceUntilIdle()
        assertEquals(LanderConfidence.PROBABILISTIC, initial.await()!!.confidence)
    }

    @Test
    fun `no deferred link - getInitialLink completes with null after init`() = runTest {
        val client = start()
        client.onLink(null)
        val initial = async { client.getInitialLink() }
        advanceUntilIdle()
        assertNull(initial.await())
    }

    @Test
    fun `a later launch without a link settles the initial link without the network`() = runTest {
        platform.storage[LanderClient.FIRST_LAUNCH_REPORTED_KEY] = "1"
        transport.defaults = { delay(5_000); offline(it) }
        val client = start()
        client.settleLaunchIfNeeded()
        val initial = async { client.getInitialLink() }
        runCurrent()
        assertTrue(initial.isCompleted)
        assertNull(initial.await())
    }

    @Test
    fun `a deferred link from an init retried later in the session arrives on deepLinks`() = runTest {
        transport.on("/v1/sdk/init", offline, offline, offline)
        val client = start()
        client.onLink(null)
        val initial = async { client.getInitialLink() }
        advanceUntilIdle()
        assertNull(initial.await())
        transport.on("/v1/sdk/init", deferredInit())
        val got = collect()
        client.onForeground()
        advanceUntilIdle()
        assertEquals(1, got.size)
        assertTrue(got[0].isDeferred)
    }

    // Resolving links

    @Test
    fun `a known link resolves to a LanderLink with its params`() = runTest {
        transport.on("/v1/sdk/resolve", { json(200, resolved(params = """{"productId":"123","discount":15,"vip":true}""")) })
        val client = start()
        val got = collect()
        client.onLink("https://acme.golander.link/p-123?utm_source=x")
        advanceUntilIdle()
        val link = got.single()
        assertTrue(link.isResolved)
        assertEquals("/product/123", link.route)
        assertEquals("https://acme.golander.link/p-123?utm_source=x", link.uriString)
        assertEquals(mapOf("productId" to "123", "discount" to 15, "vip" to true), link.params)
        assertEquals("summer", link.campaign)
        assertEquals("share", link.channel)
        val body = transport.requestsTo("/v1/sdk/resolve").single().body
        assertEquals("https://acme.golander.link/p-123?utm_source=x", body.getString("url"))
        assertEquals(client.installId, body.getString("installId"))
    }

    @Test
    fun `the launch link is resolved after init registered the install`() = runTest {
        transport.on("/v1/sdk/init", { delay(1_000); json(200, "{}") })
        val client = start()
        client.onLink("https://acme.golander.link/p-123")
        advanceUntilIdle()
        assertEquals(listOf("/v1/sdk/init", "/v1/sdk/resolve"), transport.requests.map { it.path })
        assertFalse(client.getInitialLink()!!.isResolved)
    }

    @Test
    fun `getInitialLink delivers the launch link once`() = runTest {
        transport.on("/v1/sdk/resolve", { json(200, resolved()) })
        val client = start()
        client.onLink("https://acme.golander.link/p-123")
        val initial = async { client.getInitialLink() }
        advanceUntilIdle()
        assertEquals("/product/123", initial.await()!!.route)
        assertNull(client.getInitialLink())
        val got = collect()
        runCurrent()
        assertTrue(got.isEmpty())
    }

    @Test
    fun `an unknown link (404) is still delivered with the raw URI`() = runTest {
        val client = start()
        val got = collect()
        client.onLink(null)
        client.onLink("https://example.com/about?x=1")
        advanceUntilIdle()
        val link = got.single()
        assertFalse(link.isResolved)
        assertEquals("https://example.com/about?x=1", link.uriString)
        assertEquals("/about?x=1", link.route)
    }

    @Test
    fun `a network failure delivers the raw URI instead of losing the link`() = runTest {
        transport.on("/v1/sdk/resolve", offline, offline)
        val client = start()
        val got = collect()
        client.onLink(null)
        client.onLink("https://acme.golander.link/p-123")
        advanceUntilIdle()
        assertFalse(got.single().isResolved)
        assertEquals(2, transport.requestsTo("/v1/sdk/resolve").size)
    }

    @Test
    fun `custom scheme URLs are delivered without a network call`() = runTest {
        val client = start()
        val got = collect()
        client.onLink(null)
        client.onLink("acmeshop://product/123?ref=mail")
        advanceUntilIdle()
        assertEquals("/product/123?ref=mail", got.single().route)
        assertTrue(transport.requestsTo("/v1/sdk/resolve").isEmpty())
    }

    @Test
    fun `a custom scheme URL carrying lander_link resolves that link`() = runTest {
        transport.on("/v1/sdk/resolve", { json(200, resolved(method = "landing_page", isDeferred = true)) })
        val client = start()
        val got = collect()
        client.onLink(null)
        val raw = "acmeshop://open?lander_link=https%3A%2F%2Facme.golander.link%2Fp-123%3Flander_token%3Dt1"
        client.onLink(raw)
        advanceUntilIdle()
        assertEquals("https://acme.golander.link/p-123?lander_token=t1", transport.requestsTo("/v1/sdk/resolve").single().body.getString("url"))
        assertEquals(raw, got.single().uriString)
        assertEquals("landing_page", got.single().method)
    }

    @Test
    fun `when that resolve fails, the scheme URL still routes`() = runTest {
        transport.on("/v1/sdk/resolve", offline, offline)
        val client = start()
        val got = collect()
        client.onLink(null)
        client.onLink("acmeshop://product/123?lander_link=https%3A%2F%2Facme.golander.link%2Fp-123")
        advanceUntilIdle()
        assertEquals("/product/123?lander_link=https%3A%2F%2Facme.golander.link%2Fp-123", got.single().route)
    }

    @Test
    fun `warm start links are resolved and delivered in arrival order`() = runTest {
        transport.on(
            "/v1/sdk/resolve",
            { delay(3_000); json(200, resolved(path = "/slow")) },
            { json(200, resolved(path = "/fast")) },
        )
        val client = start()
        val got = collect()
        client.onLink(null)
        client.onLink("https://acme.golander.link/slow")
        client.onLink("https://acme.golander.link/fast")
        advanceUntilIdle()
        assertEquals(listOf("/slow", "/fast"), got.map { it.route })
    }

    @Test
    fun `the same URL reported twice within two seconds counts once`() = runTest {
        val client = start()
        val got = collect()
        client.onLink(null)
        client.onLink("acmeshop://a")
        client.onLink("acmeshop://a")
        advanceTimeBy(2_500)
        client.onLink("acmeshop://a")
        advanceUntilIdle()
        assertEquals(2, got.size)
    }

    @Test
    fun `links that arrive before anyone collects are replayed`() = runTest {
        val client = start()
        client.onLink(null)
        client.onLink("acmeshop://a")
        client.onLink("acmeshop://b")
        advanceUntilIdle()
        val got = collect()
        runCurrent()
        assertEquals(listOf("/a", "/b"), got.map { it.route })
    }

    // Handoffs

    @Test
    fun `claimHandoff returns the handed off link without emitting it`() = runTest {
        transport.on("/v1/sdk/handoffs/claim", { json(200, """{"deferredLink":${resolved(isDeferred = true, method = "authenticated_handoff")}}""") })
        val client = start()
        val got = collect()
        client.onLink(null)
        val link = client.claimHandoff("user-1")!!
        assertEquals("authenticated_handoff", link.method)
        assertTrue(link.isDeferred)
        val body = transport.requestsTo("/v1/sdk/handoffs/claim").single().body
        assertEquals("user-1", body.getString("userId"))
        assertEquals(client.installId, body.getString("installId"))
        advanceUntilIdle()
        assertTrue(got.isEmpty())
    }

    @Test
    fun `claimHandoff returns null when nothing is pending`() = runTest {
        val client = start()
        assertNull(client.claimHandoff("user-1"))
    }

    @Test
    fun `claimHandoff retries with one Idempotency-Key, then throws`() = runTest {
        transport.on("/v1/sdk/handoffs/claim", offline, offline, offline)
        val client = start()
        val e = expectThrows<LanderException> { client.claimHandoff("user-1") }
        assertEquals("network_error", e.code)
        val keys = transport.requestsTo("/v1/sdk/handoffs/claim").map { it.headers["Idempotency-Key"] }
        assertEquals(3, keys.size)
        assertEquals(1, keys.toSet().size)
    }

    @Test
    fun `a malformed handoff response throws invalid_response`() = runTest {
        transport.on("/v1/sdk/handoffs/claim", { json(200, """{"deferredLink":{"path":"/x"}}""") })
        val client = start()
        assertEquals("invalid_response", expectThrows<LanderException> { client.claimHandoff("u") }.code)
    }

    @Test
    fun `claimHandoff retries a failed init before giving up`() = runTest {
        transport.on("/v1/sdk/init", offline, offline, offline, offline, offline, offline)
        val client = start()
        val e = expectThrows<LanderException> { client.claimHandoff("u") }
        assertEquals("install_not_registered", e.code)
        assertEquals(6, inits().size)
        assertTrue(client.claimHandoff("u") == null)
        assertEquals(7, inits().size)
    }

    // Links

    @Test
    fun `createLink posts to the SDK links endpoint and parses the link`() = runTest {
        transport.on("/v1/sdk/links", { r ->
            json(
                201,
                """{"id":"33333333-3333-4333-8333-333333333333","url":"https://acme.golander.link/x7Kp2Qa","domain":"acme.golander.link",""" +
                    """"slug":"x7Kp2Qa","deepLinkPath":"${r.body.getString("deepLinkPath")}","params":{"productId":"123"},"title":"Trail runner",""" +
                    """"tags":["a"],"expiresAt":null,"createdAt":"2026-10-15T10:00:00.123Z"}""",
            )
        })
        val client = start()
        val options = LanderLinkOptions.Builder().path("/product/123").param("productId", "123").title("Trail runner")
            .channel("share").tags(listOf("a")).build()
        val link = client.createLink(options)
        assertEquals("https://acme.golander.link/x7Kp2Qa", link.url)
        assertEquals("/product/123", link.path)
        assertEquals(listOf("a"), link.tags)
        assertNull(link.expiresAt)
        assertEquals(1_792_058_400_123L, link.createdAt!!.time)
        val body = transport.requestsTo("/v1/sdk/links").single().body
        assertEquals(client.installId, body.getString("installId"))
        assertEquals("share", body.getString("channel"))
        assertEquals("123", body.getJSONObject("params").getString("productId"))
        assertFalse(body.has("slug"))
    }

    @Test
    fun `createLink throws LanderException after retrying with one key`() = runTest {
        transport.on("/v1/sdk/links", offline, offline, offline)
        val client = start()
        val e = expectThrows<LanderException> { client.createLink(LanderLinkOptions.Builder().build()) }
        assertEquals("network_error", e.code)
        assertEquals(1, transport.requestsTo("/v1/sdk/links").map { it.headers["Idempotency-Key"] }.toSet().size)
        assertEquals(3, transport.requestsTo("/v1/sdk/links").size)
    }

    // Events

    @Test
    fun `trackEvent queues, persists and sends a batch with client ids`() = runTest {
        val client = start()
        client.onLink(null)
        advanceUntilIdle()
        client.trackEvent("purchase", mapOf("amount" to 49.99, "items" to listOf("a"), "meta" to mapOf("x" to null)), "11111111-1111-4111-8111-111111111111")
        client.trackEvent("signup", emptyMap(), null)
        runCurrent()
        assertTrue(platform.storage[EventQueue.STORAGE_KEY]!!.contains("purchase"))
        advanceTimeBy(2_001)
        val req = transport.requestsTo("/v1/sdk/events").single()
        val events = req.body.getJSONArray("events")
        assertEquals(2, events.length())
        val purchase = events.getJSONObject(0)
        assertEquals("custom", purchase.getString("type"))
        assertEquals("purchase", purchase.getString("name"))
        assertTrue(Uuids.isValid(purchase.getString("id")))
        assertEquals("11111111-1111-4111-8111-111111111111", purchase.getString("linkId"))
        assertEquals(49.99, purchase.getJSONObject("properties").getDouble("amount"), 0.0)
        assertTrue(purchase.getString("occurredAt").endsWith("Z"))
        assertFalse(events.getJSONObject(1).has("linkId"))
        assertEquals("[]", platform.storage[EventQueue.STORAGE_KEY])
    }

    @Test
    fun `events tracked offline are sent on the next launch`() = runTest {
        transport.on("/v1/sdk/events", offline)
        val client = start()
        client.onLink(null)
        advanceUntilIdle()
        client.trackEvent("purchase", emptyMap(), null)
        advanceTimeBy(2_001)
        client.dispose()
        assertTrue(platform.storage[EventQueue.STORAGE_KEY]!!.contains("purchase"))
        start(d = LinkDispatcher()).onLink(null)
        runCurrent()
        assertEquals(2, transport.requestsTo("/v1/sdk/events").size)
        assertEquals("[]", platform.storage[EventQueue.STORAGE_KEY])
    }

    @Test
    fun `a failed batch is retried with the same idempotency key`() = runTest {
        transport.on("/v1/sdk/events", { json(503, "") })
        val client = start()
        client.onLink(null)
        advanceUntilIdle()
        client.trackEvent("purchase", emptyMap(), null)
        advanceTimeBy(2_001)
        advanceTimeBy(1_001)
        val keys = transport.requestsTo("/v1/sdk/events").map { it.headers["Idempotency-Key"] }
        assertEquals(2, keys.size)
        assertEquals(keys[0], keys[1])
    }

    @Test
    fun `trackEvent rejects invalid names, link IDs and properties`() = runTest {
        val client = start()
        expectThrows<IllegalArgumentException> { client.trackEvent("", emptyMap(), null) }
        expectThrows<IllegalArgumentException> { client.trackEvent("x".repeat(101), emptyMap(), null) }
        expectThrows<IllegalArgumentException> { client.trackEvent("ok", emptyMap(), "not-a-uuid") }
        expectThrows<IllegalArgumentException> { client.trackEvent("ok", mapOf("d" to Any()), null) }
        expectThrows<IllegalArgumentException> { client.trackEvent("ok", mapOf("n" to Double.NaN), null) }
    }

    @Test
    fun `events wait for a successful init instead of being dropped`() = runTest {
        transport.on("/v1/sdk/init", offline, offline, offline, offline, offline, offline)
        val client = start()
        client.onLink(null)
        advanceUntilIdle()
        client.trackEvent("purchase", emptyMap(), null)
        // The flush retries init (3 tries over 1.5 s), fails, and backs off 1 s.
        advanceTimeBy(4_000)
        assertTrue(transport.requestsTo("/v1/sdk/events").isEmpty())
        assertTrue(platform.storage[EventQueue.STORAGE_KEY]!!.contains("purchase"))
        advanceTimeBy(1_000) // the backoff retry registers the install, then sends
        assertEquals(1, transport.requestsTo("/v1/sdk/events").size)
    }

    @Test
    fun `install_not_found on events registers the install again and keeps the events`() = runTest {
        platform.storage[LanderClient.FIRST_LAUNCH_REPORTED_KEY] = "1"
        transport.on("/v1/sdk/events", { json(404, """{"error":{"code":"install_not_found","message":"Unknown install"}}""") })
        val client = start()
        client.onLink(null)
        advanceUntilIdle()
        client.trackEvent("purchase", emptyMap(), null)
        advanceTimeBy(2_001)
        // Registered again as a first launch, then the same event is sent again.
        assertEquals(2, inits().size)
        assertTrue(inits().last().getBoolean("isFirstLaunch"))
        val sends = transport.requestsTo("/v1/sdk/events").map { it.body.getJSONArray("events").getJSONObject(0).getString("id") }
        assertEquals(2, sends.size)
        assertEquals(sends[0], sends[1])
        assertEquals("[]", platform.storage[EventQueue.STORAGE_KEY])
    }

    @Test
    fun `flushes events when the app goes to the background`() = runTest {
        val client = start()
        client.onLink(null)
        advanceUntilIdle()
        client.trackEvent("purchase", emptyMap(), null)
        runCurrent()
        client.onBackground()
        runCurrent()
        assertEquals(1, transport.requestsTo("/v1/sdk/events").size)
    }

    @Test
    fun `log lines never contain the API key`() = runTest {
        val log = TestLog()
        val client = newClient(platform, transport, dispatcher, log).also { clients += it }
        client.start()
        client.onLink("https://acme.golander.link/p-123")
        advanceUntilIdle()
        assertTrue(log.lines.isNotEmpty())
        assertTrue(log.lines.none { it.contains(TEST_KEY) })
    }
}