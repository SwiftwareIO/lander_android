package link.lander.android.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import link.lander.android.LanderException
import link.lander.android.LanderLink
import link.lander.android.LanderLinkOptions
import link.lander.android.LanderShortLink
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date
import kotlin.coroutines.cancellation.CancellationException

/**
 * The SDK engine behind the `Lander` facade. One per process.
 *
 * Every piece of state below is only touched from [serial], a single threaded
 * dispatcher, so the logic reads like the Flutter SDK's single isolate. The
 * one exception is [onLink], called from the main thread, which only hands
 * the URL over through a channel.
 */
internal class LanderClient(
    apiKey: String,
    baseUrl: String,
    private val dispatcher: LinkDispatcher,
    private val platform: LanderPlatform,
    transport: HttpTransport,
    val logger: LanderLogger,
    private val serial: CoroutineDispatcher,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sdkVersion: String,
    requestTimeoutMs: Long = 10_000,
    flushDelayMs: Long = 2_000,
    eventBackoff: Backoff = Backoff(),
    requestBackoff: Backoff = Backoff(baseMs = 500, maxMs = 4_000),
) {
    val scope = CoroutineScope(
        SupervisorJob() + serial + CoroutineExceptionHandler { _, e -> logger.log("Unexpected error: $e") },
    )

    val api = LanderApi(
        apiKey = apiKey,
        baseUrl = baseUrl,
        transport = transport,
        logger = logger,
        sdkVersion = sdkVersion,
        timeoutMs = requestTimeoutMs,
        backoff = requestBackoff,
    )

    val events = EventQueue(
        scope = scope,
        read = ::read,
        write = ::write,
        sender = ::sendEvents,
        logger = logger,
        flushDelayMs = flushDelayMs,
        backoff = eventBackoff,
    )

    /** The random install ID, once loaded. */
    @Volatile
    var installId: String? = null
        private set

    private val ready = CompletableDeferred<Unit>()

    /** Completes when `POST /v1/sdk/init` has finished (or failed). */
    val initDone = CompletableDeferred<Unit>()

    private var isFirstLaunch = false
    private var firstLaunchAtStart = false

    /**
     * Whether the API knows this install (an init succeeded, now or on an
     * earlier launch). Events wait for it: the API rejects events from
     * unknown installs.
     */
    private var registered = false
    private var registering: Deferred<JSONObject?>? = null
    private var reRegisterTried = false
    private var conflictRetried = false

    /** Whether the launch intent carried a link; null until the launch is known. */
    private var launchHasLink: Boolean? = null
    private val afterLaunch = mutableListOf<(Boolean) -> Unit>()

    private class Incoming(val raw: String?, val launch: Boolean)

    private val incoming = Channel<Incoming>(Channel.UNLIMITED)
    private val intentLock = Any()
    private var launchSeen = false
    private var lastRawLink: String? = null
    private var lastRawLinkAt = 0L

    /**
     * Loads local state, then reports the launch with `POST /v1/sdk/init` and
     * processes incoming links, all in the background. Returns immediately.
     */
    fun start() {
        scope.launch {
            loadInstall()
            ready.complete(Unit)
            launch { init() }
            launch {
                events.load()
                events.flush()
            }
            for (message in incoming) handleIncoming(message)
        }
    }

    // Links

    /**
     * A URL from an intent ([raw] is null when the intent carried none). The
     * first call describes the launch; later ones are links that arrived
     * while the app was running. Any thread.
     */
    fun onLink(raw: String?) {
        synchronized(intentLock) {
            val now = clock()
            if (!launchSeen) {
                launchSeen = true
                lastRawLink = raw
                lastRawLinkAt = now
                incoming.trySend(Incoming(raw, launch = true))
                return
            }
            if (raw == null) return
            if (raw == lastRawLink && now - lastRawLinkAt < DUPLICATE_LINK_WINDOW_MS) {
                logger.log("Ignoring duplicate link $raw")
                return
            }
            lastRawLink = raw
            lastRawLinkAt = now
            incoming.trySend(Incoming(raw, launch = false))
        }
    }

    /**
     * Called when the first activity resumes: if no intent was handed over by
     * then, the app was not opened by a link. Keeps [getInitialLink] from
     * waiting forever when `handleIntent` is not called.
     */
    fun settleLaunchIfNeeded() {
        synchronized(intentLock) {
            if (launchSeen) return
            launchSeen = true
            incoming.trySend(Incoming(null, launch = true))
        }
    }

    private suspend fun handleIncoming(message: Incoming) {
        val raw = message.raw
        if (!message.launch) {
            if (raw != null) deliver(raw, initial = false)
            return
        }
        val hasLink = raw != null
        launchHasLink = hasLink
        if (!hasLink && !firstLaunchAtStart) {
            // Only a first launch can produce a deferred link, so do not make
            // getInitialLink wait for the network otherwise.
            dispatcher.setInitialLink(null)
        }
        val waiting = afterLaunch.toList()
        afterLaunch.clear()
        waiting.forEach { it(hasLink) }
        if (raw != null) deliver(raw, initial = true)
    }

    /** Runs [action] with whether the launch had a link, once that is known. */
    private fun onLaunchSettled(action: (Boolean) -> Unit) {
        val has = launchHasLink
        if (has != null) action(has) else afterLaunch += action
    }

    private suspend fun deliver(raw: String, initial: Boolean) {
        try {
            val link = resolve(raw)
            if (initial) {
                dispatcher.setInitialLink(link)
            } else if (link != null) {
                dispatcher.add(link)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.log("Link delivery failed for $raw: $e")
            if (initial && !dispatcher.initialSettled) dispatcher.setInitialLink(null)
        }
    }

    /**
     * Resolves [raw] through the API. Never throws: unknown links and network
     * failures produce an unresolved link carrying the raw URL.
     */
    private suspend fun resolve(raw: String): LanderLink? {
        if (raw.isBlank()) {
            logger.log("Ignoring empty link")
            return null
        }
        val uri = ParsedUri.parse(raw)
        var target = raw
        if (uri.scheme != "http" && uri.scheme != "https") {
            // Custom scheme URLs are not Lander short links, unless they carry one
            // in `lander_link` (the "Continue in app" page's open button does).
            val carried = uri.queryParameter(LANDER_LINK_PARAM)
            val carriedScheme = carried?.let { ParsedUri.parse(it).scheme }
            if (carried == null || (carriedScheme != "https" && carriedScheme != "http")) {
                return LanderLink.unresolved(raw)
            }
            target = carried
        }
        // Let init register this install first, but never hold a link for long.
        withTimeoutOrNull(api.timeoutMs) { initDone.await() }
        return try {
            val json = api.post(
                "/v1/sdk/resolve",
                JSONObject().put("installId", installId).put("url", target),
                idempotencyKey = Uuids.v4(),
                maxAttempts = 2,
            )
            LanderLink.fromJson(json, uri = raw).also { logger.log("Resolved $it") }
        } catch (e: LanderException) {
            logger.log(if (e.statusCode == 404) "Not a Lander link: $raw" else "Resolve failed for $raw: $e")
            LanderLink.unresolved(raw)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.log("Resolve returned an unexpected response for $raw: $e")
            LanderLink.unresolved(raw)
        }
    }

    suspend fun getInitialLink(): LanderLink? = dispatcher.takeInitialLink()

    // Install and init

    private suspend fun loadInstall() {
        var id = safeRead(INSTALL_ID_KEY)
        if (!Uuids.isValid(id)) {
            id = Uuids.v4()
            write(INSTALL_ID_KEY, id)
            logger.log("Created install ID $id")
        }
        installId = id
        isFirstLaunch = safeRead(FIRST_LAUNCH_REPORTED_KEY) != "1"
        firstLaunchAtStart = isFirstLaunch
        registered = !isFirstLaunch
    }

    /** Starts over with a new install ID, reported as a first launch. */
    private suspend fun resetInstall() {
        val id = Uuids.v4()
        installId = id
        write(INSTALL_ID_KEY, id)
        try {
            withContext(io) { platform.delete(FIRST_LAUNCH_REPORTED_KEY) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.log("Storage delete failed: $e")
        }
        isFirstLaunch = true
        registered = false
        logger.log("Created install ID $id")
    }

    private suspend fun init() {
        try {
            registerOnce()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.log("Init failed unexpectedly: $e")
        } finally {
            initDone.complete(Unit)
        }
        onLaunchSettled { hasLink ->
            if (!hasLink && !dispatcher.initialSettled) dispatcher.setInitialLink(null)
        }
    }

    /**
     * Registers the install and hands a deferred link from the response to
     * the app. Concurrent callers share one request. Never throws.
     */
    private suspend fun registerOnce(): JSONObject? {
        registering?.let { return it.await() }
        val request = scope.async {
            try {
                registerAndDeliver()
            } finally {
                registering = null
            }
        }
        registering = request
        return request.await()
    }

    private suspend fun registerAndDeliver(): JSONObject? {
        val json = register()
        val deferred = json?.opt("deferredLink") as? JSONObject ?: return json
        val link = LanderLink.fromJson(deferred)
        logger.log("Deferred link: $link")
        onLaunchSettled { hasLink ->
            // A link that opened the app wins over a deferred one. Once the launch
            // link is settled (a retried init that succeeded later in the
            // session), this delivers it on deepLinks instead.
            if (hasLink) logger.log("The app was opened by a link; ignoring the deferred link")
            else dispatcher.setInitialLink(link)
        }
        return json
    }

    /** Retries the registration if no init has succeeded yet. Returns whether the install is registered. */
    private suspend fun ensureRegistered(): Boolean {
        if (!registered) registerOnce()
        return registered
    }

    /** `POST /v1/sdk/init`. Returns the response, or null on failure. Never throws. */
    private suspend fun register(): JSONObject? {
        try {
            var referrer: String? = null
            if (isFirstLaunch) {
                referrer = try {
                    withTimeoutOrNull(INSTALL_REFERRER_TIMEOUT_MS) { platform.installReferrer() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.log("Install referrer failed: $e")
                    null
                }
                if (referrer != null && referrer.length > 2048) referrer = referrer.substring(0, 2048)
            }
            val body = JSONObject()
                .put("installId", installId)
                .put("platform", "android")
                .put("appVersion", truncate(platform.appVersion()))
                .put("sdkVersion", sdkVersion)
                .put("isFirstLaunch", isFirstLaunch)
            platform.osVersion()?.let { body.put("osVersion", truncate(it)) }
            if (!referrer.isNullOrEmpty()) body.put("installReferrer", referrer)

            val json = api.post(
                "/v1/sdk/init",
                body,
                // A stable key for the install report, so a retry after a lost
                // response (even on the next launch) records one install, not two.
                idempotencyKey = if (isFirstLaunch) Uuids.v5("lander:install:$installId") else Uuids.v4(),
                maxAttempts = if (isFirstLaunch) 3 else 1,
            )
            if (isFirstLaunch) {
                // If this marker is lost, the next launch reports a first launch
                // again and gets the same deferred link a second time, so try
                // harder than for other writes before the link is handed over.
                if (!writeWithRetry(FIRST_LAUNCH_REPORTED_KEY, "1")) {
                    logger.log("Could not save the first launch marker; the next launch may get the deferred link again")
                }
                isFirstLaunch = false
            }
            registered = true
            logger.log("Init ok")
            // Send events that waited for the install to be known.
            scope.launch { events.flush() }
            return json
        } catch (e: LanderException) {
            if (e.code == "install_id_conflict" && !conflictRetried) {
                // The ID belongs to another project or environment, for example
                // after switching from a test key to a live key. Start over.
                conflictRetried = true
                logger.log("Install ID belongs to another project, creating a new one")
                resetInstall()
                return register()
            }
            logger.log("Init failed: $e")
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.log("Init failed unexpectedly: $e")
            return null
        }
    }

    // Handoffs, links and events

    /**
     * `POST /v1/sdk/handoffs/claim`. Returns the link the customer's backend
     * bound to [userId], or null. The link is returned only, never also sent
     * to deepLinks, so the app navigates once.
     */
    suspend fun claimHandoff(userId: String): LanderLink? = withContext(serial) {
        ready.await()
        // The API only knows installs that went through init. If it failed,
        // try again now.
        initDone.await()
        if (!ensureRegistered()) {
            throw LanderException(
                code = "install_not_registered",
                message = "Lander could not register this install yet (offline?); try again later",
            )
        }
        val json = api.post(
            "/v1/sdk/handoffs/claim",
            JSONObject().put("installId", installId).put("userId", userId),
            // One key per call, reused across its retries: a retry after a lost
            // response gets the same link instead of nothing.
            idempotencyKey = Uuids.v4(),
            maxAttempts = 3,
        )
        val deferred = json.opt("deferredLink")
        if (deferred == null || deferred == JSONObject.NULL) {
            logger.log("No pending handoff")
            return@withContext null
        }
        val link = (deferred as? JSONObject)?.let { LanderLink.fromJson(it) }
        if (link == null || !link.isResolved) {
            throw LanderException(code = "invalid_response", message = "Unexpected claimHandoff response")
        }
        logger.log("Handoff: $link")
        link
    }

    suspend fun createLink(options: LanderLinkOptions): LanderShortLink = withContext(serial) {
        ready.await()
        val json = api.post(
            "/v1/sdk/links",
            // installId lets the API rate limit per install instead of per IP.
            options.toJson().put("installId", installId),
            idempotencyKey = Uuids.v4(),
            maxAttempts = 3,
        )
        try {
            LanderShortLink.fromJson(json)
        } catch (e: Exception) {
            throw LanderException(code = "invalid_response", message = "Unexpected createLink response: $e")
        }
    }

    /**
     * Validates and queues a custom event. Throws [IllegalArgumentException]
     * for programming errors; never throws for network errors.
     */
    fun trackEvent(name: String, properties: Map<String, Any?>, linkId: String?) {
        require(name.isNotEmpty() && name.length <= 100) { "name must be 1 to 100 characters" }
        // Validate here: one bad event would make the API reject its whole batch.
        if (linkId != null) require(Uuids.isValid(linkId)) { "linkId must be a Lander link ID (UUID)" }
        val props = toJsonValue(properties, "properties") as JSONObject
        val event = JSONObject()
            .put("id", Uuids.v4())
            .put("type", "custom")
            .put("name", name)
            .put("properties", props)
            .put("occurredAt", Iso8601.format(Date(clock())))
        if (linkId != null) event.put("linkId", linkId)
        scope.launch { events.add(event) }
    }

    private suspend fun sendEvents(batch: List<JSONObject>) {
        ready.await()
        // An init that failed is retried here, so events do not wait for the
        // next launch.
        if (!ensureRegistered()) {
            // Retried with backoff, and flushed as soon as init succeeds.
            throw LanderException(code = "install_not_registered", message = "Waiting for init to succeed")
        }
        val ids = batch.map { it.getString("id") }.sorted()
        try {
            api.post(
                "/v1/sdk/events",
                JSONObject().put("installId", installId).put("events", JSONArray(batch)),
                idempotencyKey = Uuids.v5("lander:events:${ids.joinToString(",")}"),
            )
        } catch (e: LanderException) {
            if (e.code != "install_not_found" || reRegisterTried) throw e
            // The API lost this install (for example a reset local database).
            // Register it again once, and keep the events meanwhile.
            reRegisterTried = true
            registered = false
            isFirstLaunch = true
            logger.log("API does not know this install, registering it again")
            scope.launch { registerOnce() }
            throw LanderException(code = e.code, message = e.message)
        }
    }

    // App lifecycle

    /** The app came to the foreground. */
    fun onForeground() {
        scope.launch {
            // The init at launch failed (offline?). Back in the foreground is a
            // good moment to try again, without waiting for the next launch.
            if (ready.isCompleted && !registered) registerOnce()
        }
    }

    /** The app went to the background: send what is queued. */
    fun onBackground() {
        scope.launch { events.flush() }
    }

    // Storage

    private suspend fun read(key: String): String? = withContext(io) { platform.read(key) }

    private suspend fun safeRead(key: String): String? = try {
        read(key)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.log("Storage read failed for $key: $e")
        null
    }

    private suspend fun write(key: String, value: String) {
        try {
            withContext(io) { platform.write(key, value) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.log("Storage write failed for $key: $e")
        }
    }

    /** [write] with up to [attempts] tries. Never throws. */
    private suspend fun writeWithRetry(key: String, value: String, attempts: Int = 3): Boolean {
        for (i in 0 until attempts) {
            try {
                withContext(io) { platform.write(key, value) }
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.log("Storage write failed for $key (try ${i + 1} of $attempts): $e")
                if (i < attempts - 1) delay(100L * (i + 1))
            }
        }
        return false
    }

    /** Stops timers and background work. */
    fun dispose() {
        events.dispose()
        incoming.close()
        scope.cancel()
    }

    companion object {
        const val INSTALL_ID_KEY = "install_id"
        const val FIRST_LAUNCH_REPORTED_KEY = "first_launch_reported"

        /** Query parameter of a custom scheme URL carrying the Lander link it stands for. */
        const val LANDER_LINK_PARAM = "lander_link"

        /**
         * Repeated identical URLs inside this window count as one open. Android
         * can report the same launch URL through more than one callback.
         */
        const val DUPLICATE_LINK_WINDOW_MS = 2_000L

        /** Longest `appVersion` and `osVersion` the API accepts. */
        const val MAX_VERSION_LENGTH = 50

        const val INSTALL_REFERRER_TIMEOUT_MS = 5_000L

        fun truncate(version: String): String =
            if (version.length > MAX_VERSION_LENGTH) version.substring(0, MAX_VERSION_LENGTH) else version
    }
}
