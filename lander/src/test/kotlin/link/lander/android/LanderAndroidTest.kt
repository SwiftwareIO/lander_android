package link.lander.android

import android.app.Application
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import link.lander.android.internal.AndroidPlatform
import link.lander.android.internal.LanderLogger
import link.lander.android.internal.LinkIntents
import link.lander.android.internal.TEST_KEY
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LanderAndroidTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() = Lander.resetForTesting()

    private fun view(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url))

    @Test
    fun `an intent's link is read once`() {
        val intent = view("https://acme.golander.link/p-123")
        assertEquals("https://acme.golander.link/p-123", LinkIntents.take(intent))
        assertNull(LinkIntents.take(intent))
        assertTrue(intent.getBooleanExtra(LinkIntents.HANDLED_EXTRA, false))
    }

    @Test
    fun `launcher intents and intents replayed from Recents carry no link`() {
        assertNull(LinkIntents.take(Intent(Intent.ACTION_MAIN)))
        assertNull(LinkIntents.take(null))
        val fromHistory = view("acmeshop://product/1").addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertNull(LinkIntents.take(fromHistory))
    }

    @Test
    fun `storage lives in noBackupFilesDir`() {
        val platform = AndroidPlatform(app, LanderLogger(false))
        assertNull(platform.read("install_id"))
        platform.write("install_id", "abc")
        assertEquals("abc", platform.read("install_id"))
        assertEquals("abc", File(app.noBackupFilesDir, "lander/install_id").readText())
        platform.delete("install_id")
        assertNull(platform.read("install_id"))
        try {
            platform.read("../escape")
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `initialize rejects secret and malformed keys without echoing them`() {
        val secret = "sk_live_SecretKey0000000000000000000000"
        for (bad in listOf(secret, "Bearer $TEST_KEY", "pk_live_short", "pk_prod_DemoPublishableKey000000000000", "")) {
            try {
                Lander.initialize(app, bad)
                fail("expected IllegalArgumentException for a bad key")
            } catch (e: IllegalArgumentException) {
                if (bad.isNotEmpty()) assertFalse(e.message!!.contains(bad))
            }
        }
        assertNull(Lander.installId)
    }

    @Test
    fun `calls before initialize fail with IllegalStateException`() {
        try {
            Lander.trackEvent("signup")
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("initialize"))
        }
    }

    @Test
    fun `end to end - a launch link is resolved and returned by getInitialLink`() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/v1/sdk/init" -> MockResponse().setBody("""{"deferredLink":null}""")
                "/v1/sdk/resolve" -> MockResponse().setBody(
                    """{"linkId":"11111111-1111-4111-8111-111111111111","url":"https://acme.golander.link/p-123",""" +
                        """"path":"/product/123","params":{"productId":"123"},"isDeferred":false,""" +
                        """"confidence":"deterministic","method":"app_link"}""",
                )
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            // A later launch, so the test does not wait on the Play Install Referrer.
            AndroidPlatform(app, LanderLogger(false)).write("first_launch_reported", "1")
            // Links handed over before initialize are kept.
            Lander.handleIntent(view("https://acme.golander.link/p-123"))
            Lander.initialize(app, "  $TEST_KEY  ", baseUrl = server.url("/").toString(), enableLogging = true)
            Lander.initialize(app, TEST_KEY) // ignored
            val link = runBlocking { withTimeout(15_000) { Lander.getInitialLink() } }!!
            assertEquals("/product/123", link.route)
            assertEquals(LanderConfidence.DETERMINISTIC, link.confidence)
            assertEquals("acme.golander.link", link.uri.host)
            assertTrue(Lander.installId != null)

            val init = server.takeRequest()
            assertEquals("/v1/sdk/init", init.path)
            assertEquals("Bearer $TEST_KEY", init.getHeader("Authorization"))
            assertEquals("android/${Lander.VERSION}", init.getHeader("X-Lander-Sdk"))
            val body = JSONObject(init.body.readUtf8())
            assertEquals("android", body.getString("platform"))
            assertFalse(body.getBoolean("isFirstLaunch"))
            assertEquals(Lander.installId, body.getString("installId"))
        } finally {
            server.shutdown()
        }
    }
}
