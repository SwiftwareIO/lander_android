package link.lander.android

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import link.lander.android.internal.AndroidPlatform
import link.lander.android.internal.LanderClient
import link.lander.android.internal.LanderLifecycle
import link.lander.android.internal.LanderLogger
import link.lander.android.internal.LinkDispatcher
import link.lander.android.internal.LinkIntents
import link.lander.android.internal.UrlConnectionTransport
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * Entry point of the Lander SDK.
 *
 * ```kotlin
 * class App : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         Lander.initialize(this, apiKey = "pk_live_...")
 *     }
 * }
 *
 * class MainActivity : ComponentActivity() {
 *     override fun onCreate(savedInstanceState: Bundle?) {
 *         super.onCreate(savedInstanceState)
 *         Lander.handleIntent(intent)
 *         lifecycleScope.launch { Lander.deepLinks.collect { link -> navigate(link.route) } }
 *     }
 *
 *     override fun onNewIntent(intent: Intent) {
 *         super.onNewIntent(intent)
 *         Lander.handleIntent(intent)
 *     }
 * }
 * ```
 */
public object Lander {
    /** Version of this SDK, reported to the API as `sdkVersion`. */
    public const val VERSION: String = BuildConfig.SDK_VERSION

    /** The production API. */
    public const val DEFAULT_BASE_URL: String = "https://api.golander.link"

    /** The format of a publishable key, as in `packages/shared/src/keys.ts`. */
    private val publishableKey = Regex("^pk_(test|live)_[A-Za-z0-9]{24,}$")

    private val lock = Any()
    private var dispatcher = LinkDispatcher()

    @Volatile
    private var client: LanderClient? = null
    private val pendingLinks = mutableListOf<String?>()
    private var lifecycleRegistered = false
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val callbackScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

    /**
     * Starts the SDK. Call it once, in `Application.onCreate`.
     *
     * Returns immediately: loading the install ID, reporting the launch with
     * `POST /v1/sdk/init` and reading the Play Install Referrer (first launch
     * only) all happen in the background. Network failures are logged (when
     * [enableLogging] is on) and never thrown. A second call is ignored.
     *
     * [apiKey] must be a publishable key (`pk_test_...` or `pk_live_...`).
     * Surrounding whitespace is ignored. Secret keys are rejected because
     * anything shipped in an app is public.
     *
     * @throws IllegalArgumentException for a key that is not a publishable
     *   key. The message never contains the key.
     */
    @JvmStatic
    @JvmOverloads
    public fun initialize(
        context: Context,
        apiKey: String,
        baseUrl: String = DEFAULT_BASE_URL,
        enableLogging: Boolean = false,
    ) {
        synchronized(lock) {
            if (client != null) {
                if (enableLogging) Log.d(LanderLogger.TAG, "initialize called twice; ignoring the second call")
                return
            }
            val key = validateApiKey(apiKey)
            val app = context.applicationContext ?: context
            val logger = LanderLogger(enableLogging)
            val c = LanderClient(
                apiKey = key,
                baseUrl = baseUrl,
                dispatcher = dispatcher,
                platform = AndroidPlatform(app, logger),
                transport = UrlConnectionTransport(),
                logger = logger,
                serial = Dispatchers.Default.limitedParallelism(1),
                io = Dispatchers.IO,
                sdkVersion = VERSION,
            )
            if (!lifecycleRegistered && app is Application) {
                app.registerActivityLifecycleCallbacks(LanderLifecycle { client })
                lifecycleRegistered = true
            }
            start(c)
        }
    }

    /**
     * Hands an intent to Lander. Call it from the `onCreate` and `onNewIntent`
     * of every activity with Lander intent filters (usually the launcher
     * activity). Intents without a link are fine: the first one tells Lander
     * the app was not opened by a link.
     *
     * Safe to call before [initialize]: the link is kept until then. Each
     * intent is read once, so a recreated activity does not deliver its link
     * again.
     */
    @JvmStatic
    public fun handleIntent(intent: Intent?) {
        val raw = LinkIntents.take(intent)
        synchronized(lock) {
            val c = client
            if (c == null) pendingLinks += raw else c.onLink(raw)
        }
    }

    /**
     * Every deep link the app receives: App Links, custom scheme URLs and
     * deferred links (recovered on first launch, with [LanderLink.isDeferred]
     * true and their [LanderLink.confidence]).
     *
     * Safe to collect at any time, even before [initialize]. Links that arrive
     * while nobody collects are buffered and replayed in order to the next
     * collector, so nothing is lost while the app (or its navigation) starts.
     * The launch link is included unless [getInitialLink] claimed it first.
     *
     * Unknown links (not created in Lander) and links that could not be
     * resolved because of the network are still delivered, with
     * [LanderLink.isResolved] false and only the URI set.
     */
    @JvmStatic
    public val deepLinks: Flow<LanderLink>
        get() = dispatcher.flow

    /**
     * [deepLinks] for Java, or Kotlin without coroutines: [listener] is called
     * on the main thread for every link, starting with the buffered ones.
     * Call [LanderSubscription.remove] to stop, for example in `onDestroy`.
     */
    @JvmStatic
    public fun addDeepLinkListener(listener: LanderLinkListener): LanderSubscription {
        val active = AtomicBoolean(true)
        val subscription = dispatcher.subscribe { link ->
            mainHandler.post { if (active.get()) listener.onDeepLink(link) }
        }
        return LanderSubscription {
            active.set(false)
            subscription.remove()
        }
    }

    /**
     * The link that launched the app, or null.
     *
     * Waits until the launch intent reached [handleIntent] (or the first
     * activity resumed). When the app was opened by a link, waits for its
     * resolve request; on a first launch, for the init request (a deferred
     * link may come back). The launch link is delivered once: if [deepLinks]
     * or an earlier call already delivered it, this returns null, and once
     * this has been called [deepLinks] will not deliver it.
     *
     * @throws IllegalStateException before [initialize].
     */
    public suspend fun getInitialLink(): LanderLink? = requireClient("getInitialLink").getInitialLink()

    /** [getInitialLink] with a callback on the main thread, for Java. */
    @JvmStatic
    public fun getInitialLink(callback: LanderCallback<LanderLink?>) {
        runAsync(callback) { getInitialLink() }
    }

    /**
     * Creates a short link from inside the app, for share and invite flows.
     *
     * [path] is the in app route the link opens. [params] values must be
     * `String`, `Number` or `Boolean`. The slug is always random and the
     * domain is the project's default domain. Retries wait at most 10 seconds
     * in total, so a long `Retry-After` comes back as a [LanderException]
     * with [LanderException.retryAfter] instead.
     *
     * @throws LanderException when the request fails.
     * @throws IllegalArgumentException for a [path] without a leading `/` or bad [params].
     * @throws IllegalStateException before [initialize].
     */
    public suspend fun createLink(
        path: String = "/",
        params: Map<String, Any> = emptyMap(),
        title: String? = null,
        campaign: String? = null,
        channel: String? = null,
        tags: List<String> = emptyList(),
        iosFallbackUrl: String? = null,
        androidFallbackUrl: String? = null,
        webFallbackUrl: String? = null,
        ogTitle: String? = null,
        ogDescription: String? = null,
        ogImageUrl: String? = null,
        expiresAt: Date? = null,
    ): LanderShortLink {
        val c = requireClient("createLink")
        val options = LanderLinkOptions.Builder()
            .path(path)
            .params(params)
            .title(title)
            .campaign(campaign)
            .channel(channel)
            .tags(tags)
            .iosFallbackUrl(iosFallbackUrl)
            .androidFallbackUrl(androidFallbackUrl)
            .webFallbackUrl(webFallbackUrl)
            .ogTitle(ogTitle)
            .ogDescription(ogDescription)
            .ogImageUrl(ogImageUrl)
            .expiresAt(expiresAt)
            .build()
        return c.createLink(options)
    }

    /** Creates a short link; see the suspending [createLink]. */
    public suspend fun createLink(options: LanderLinkOptions): LanderShortLink =
        requireClient("createLink").createLink(options)

    /** [createLink] with a callback on the main thread, for Java. */
    @JvmStatic
    public fun createLink(options: LanderLinkOptions, callback: LanderCallback<LanderShortLink>) {
        runAsync(callback) { createLink(options) }
    }

    /**
     * Claims the link your backend handed off to this user, after sign in.
     *
     * Your server binds a link to one of its users with `POST /v1/handoffs`
     * (secret key). After that user signs in to the app, call this with the
     * same [userId] to get that link (`isDeferred` true, confidence
     * deterministic, method `authenticated_handoff`), or null when nothing is
     * pending. The newest pending handoff wins and every pending one for the
     * user is cleared, so a second call returns null.
     *
     * The link is only returned, never also emitted on [deepLinks], so
     * navigate with it yourself. Use an ID that cannot be guessed (your auth
     * system's user UUID, not an email address or a counter).
     *
     * @throws LanderException when the request fails after retries, or when
     *   the install could not be registered yet (`install_not_registered`).
     * @throws IllegalArgumentException for an empty [userId] or one over 256 characters.
     * @throws IllegalStateException before [initialize].
     */
    public suspend fun claimHandoff(userId: String): LanderLink? {
        val c = requireClient("claimHandoff")
        val id = userId.trim()
        require(id.isNotEmpty() && id.length <= 256) { "userId must be 1 to 256 characters" }
        return c.claimHandoff(id)
    }

    /** [claimHandoff] with a callback on the main thread, for Java. */
    @JvmStatic
    public fun claimHandoff(userId: String, callback: LanderCallback<LanderLink?>) {
        runAsync(callback) { claimHandoff(userId) }
    }

    /**
     * Records a custom event, for example a signup or a purchase. Fire and
     * forget: returns immediately.
     *
     * The event is stored on the device first and sent in batches, with
     * retries, so it survives offline periods and app restarts. Each event has
     * a client generated ID, so it is counted once even if it is sent twice.
     * Pass [linkId] (from [LanderLink.linkId]) to attribute the event to the
     * link that brought the user in.
     *
     * @throws IllegalArgumentException for an empty or too long [name] (max
     *   100), a [linkId] that is not a UUID, or properties that cannot be
     *   encoded as JSON. Never throws for network errors.
     * @throws IllegalStateException before [initialize].
     */
    @JvmStatic
    @JvmOverloads
    public fun trackEvent(name: String, properties: Map<String, Any?> = emptyMap(), linkId: String? = null) {
        requireClient("trackEvent").trackEvent(name, properties, linkId)
    }

    /**
     * The random ID Lander created for this install, once it is loaded
     * (shortly after [initialize]). Useful when asking for support; it is not
     * tied to the device.
     */
    @JvmStatic
    public val installId: String?
        get() = client?.installId

    /**
     * Trims [apiKey] and checks it is a publishable key. The error message
     * never contains the key.
     */
    internal fun validateApiKey(apiKey: String): String {
        val key = apiKey.trim()
        require(!key.contains("sk_")) { "apiKey: secret keys must never ship in an app; use a pk_ key" }
        require(publishableKey.matches(key)) {
            "apiKey: expected a publishable key such as pk_live_..., with no \"Bearer\" prefix or spaces"
        }
        return key
    }

    private fun requireClient(method: String): LanderClient =
        client ?: throw IllegalStateException("Call Lander.initialize() before Lander.$method()")

    private fun <T> runAsync(callback: LanderCallback<T>, block: suspend () -> T) {
        callbackScope.launch {
            val result = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                callback.onError(e)
                return@launch
            }
            callback.onSuccess(result)
        }
    }

    private fun start(c: LanderClient) {
        client = c
        c.start()
        pendingLinks.forEach(c::onLink)
        pendingLinks.clear()
    }

    /** Starts the SDK with a test client. */
    internal fun initializeForTesting(factory: (LinkDispatcher) -> LanderClient): LanderClient {
        synchronized(lock) {
            check(client == null) { "already initialized" }
            val c = factory(dispatcher)
            start(c)
            return c
        }
    }

    /** Tears the SDK down so tests can initialize it again. */
    internal fun resetForTesting() {
        synchronized(lock) {
            client?.dispose()
            client = null
            pendingLinks.clear()
            dispatcher = LinkDispatcher()
        }
    }
}
