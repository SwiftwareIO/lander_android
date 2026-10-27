# lander-android

Deep linking without the fuss. The native Android SDK for [Lander](https://golander.link), in Kotlin: receive App Links and custom scheme URLs, resolve them to the link you created in Lander, recover deferred links after install, create short links from inside the app, and report conversions.

- One `Flow` for every link: cold start, warm start, deferred (after install).
- Every link says how sure Lander is: `DETERMINISTIC`, `PROBABILISTIC` or `UNATTRIBUTED`.
- Links are buffered until you collect, so nothing is lost during startup.
- Unknown links still reach your app with the raw URL.
- Offline event queue with batching, retries and deduplication.
- Privacy first: a random install ID, no fingerprinting, no advertising ID.
- Small: kotlinx-coroutines and the Play Install Referrer library, nothing else. HTTP uses `HttpURLConnection`.

Requires Android API 24+ (minSdk 24). Works from Kotlin and Java.

## Install

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("link.lander:lander-android:0.1.0")
}
```

The SDK declares the `INTERNET` permission and brings `com.android.installreferrer:installreferrer`, so there is nothing else to add.

## Manifest setup

Inside the `<activity>` that should open links (usually your launcher activity), in `AndroidManifest.xml`:

```xml
<activity
    android:name=".MainActivity"
    android:exported="true"
    android:launchMode="singleTop">

    <!-- App Links for your Lander domain. autoVerify makes Android check
         https://<domain>/.well-known/assetlinks.json, which Lander serves. -->
    <intent-filter android:autoVerify="true">
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="https" android:host="acme.golander.link" />
    </intent-filter>

    <!-- Optional: custom scheme -->
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="acmeshop" />
    </intent-filter>
</activity>
```

`android:launchMode="singleTop"` makes links that arrive while the app runs go to `onNewIntent` instead of starting a second activity.

In the Lander dashboard (**Apps**, Android), add your package name and the SHA-256 fingerprints of every signing key (debug, upload and the Play App Signing key) so `assetlinks.json` verifies. Check verification with:

```bash
adb shell pm get-app-links com.acme.shop
```

## Usage

### Initialize

Call `initialize` once, in `Application.onCreate`. It returns immediately: loading the install ID, the launch report (`POST /v1/sdk/init`) and, on first launch, the Play Install Referrer all run in the background.

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Lander.initialize(
            this,
            apiKey = "pk_live_...", // publishable key from the dashboard
            enableLogging = BuildConfig.DEBUG,
        )
    }
}
```

Only publishable keys (`pk_test_...`, `pk_live_...`) are accepted. Secret keys (`sk_...`) are rejected, because anything shipped in an app is public. Surrounding whitespace is ignored. A bad key throws `IllegalArgumentException`, and the message never contains the key.

### Hand over intents and receive links

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lander.handleIntent(intent)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Lander.deepLinks.collect { link -> navigate(link.route) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Lander.handleIntent(intent)
    }
}
```

Call `handleIntent` in both places, for every intent: an intent without a link tells Lander the app was not opened by one. The SDK marks each intent it read, so an activity recreated after a rotation or process death does not deliver its link again.

`LanderLink` mirrors the API's `ResolvedLink`:

| Field | Meaning |
|---|---|
| `uri`, `uriString` | The URL that opened the app, exactly as Android delivered it |
| `linkId` | Lander link ID, or `null` when the link is unknown to Lander |
| `url` | Canonical short link, for example `https://acme.golander.link/p-123` |
| `path` | The in app route set on the link, for example `/product/123` |
| `params` | Link params, with the opened URL's query parameters merged over them |
| `campaign`, `channel` | Attribution labels set on the link |
| `isDeferred` | True when the link was recovered after an install or handed off after sign in |
| `confidence` | `DETERMINISTIC`, `PROBABILISTIC` or `UNATTRIBUTED` |
| `method` | How the link was tied to the open: `app_link`, `install_referrer`, `landing_page`, `authenticated_handoff` or `probabilistic_match` |
| `isResolved` | Whether Lander recognized the link |
| `route` | `path` when resolved, otherwise the raw URL's path and query |

How delivery works:

- **Buffered.** Links that arrive while nothing collects `deepLinks` (cold start, or a stopped activity with `repeatOnLifecycle`) are kept and replayed, in order, to the next collector. You can even collect before `initialize`.
- **The launch link is delivered once.** Either collect `deepLinks` only (simplest), or call `getInitialLink()` for the launch link, for example to pick the first screen. Whichever gets it first wins; the other does not see it again, and later `getInitialLink()` calls return `null`.
- **Unknown links still arrive.** When the API answers 404 (not a Lander link), or the network is down, you get a `LanderLink` with `isResolved == false` and only the URI set.
- **Custom scheme URLs** (`acmeshop://product/123`) are delivered as is, without a network call. `route` treats the host as the first path segment, so this one routes to `/product/123`. The exception is a scheme URL carrying a short link in `lander_link`: that link is resolved, and `uri` stays the scheme URL.
- The same URL reported twice within two seconds counts as one open.

```kotlin
// Option: decide the first screen from the launch link.
val initial = Lander.getInitialLink() // waits for the resolve call
```

`getInitialLink` waits until the launch intent reached `handleIntent` (or the first activity resumed), then for the resolve request when the app was opened by a link, and on a first launch for the init request (a deferred link may come back). Each request has a 10 second timeout; collecting `deepLinks` never blocks anything.

Opens are reported by the API when the SDK resolves the link, so you do not need to track them yourself.

### Deferred deep links

- **Play Install Referrer, automatic and deterministic.** The redirector sends the click to the Play Store with a single use token in the install referrer. On first launch the SDK sends the referrer to `POST /v1/sdk/init`, and the link comes back through `getInitialLink` / `deepLinks` with `isDeferred == true`, `confidence == DETERMINISTIC` and `method == "install_referrer"`. Nothing to code. A link that opened the app wins over a deferred one.
- **Authenticated handoff (deterministic).** Your server binds a link to a user, the app claims it after sign in:

  ```kotlin
  val user = auth.signIn()
  val link = Lander.claimHandoff(user.id) // null when nothing is pending
  if (link != null) navigate(link.route)
  ```

  `claimHandoff` returns the link and never also emits it on `deepLinks`, so navigation happens once. Use an ID that cannot be guessed (your auth system's user UUID, not an email address or a counter).
- **Everything else is `UNATTRIBUTED`:** no deferred link is delivered, and the install is reported as unattributed.

### Create links

```kotlin
try {
    val link = Lander.createLink(
        path = "/product/123",
        params = mapOf("productId" to "123"),
        title = "Trail runner",
        channel = "share",
    )
    share(link.url)
} catch (e: LanderException) {
    // e.code, e.statusCode, e.message, e.retryAfter
}
```

The slug is random and the domain is the project's default domain. The install ID is sent along so the API rate limits per install (20 links per minute). Requests are retried on network and server errors with the same `Idempotency-Key`, so a retry never creates two links. Retries wait at most 10 seconds in total: a longer `Retry-After` (and `link_quota_exceeded`) comes back as a `LanderException` with `retryAfter` instead.

### Track events

```kotlin
Lander.trackEvent(
    "purchase",
    mapOf("amount" to 49.99, "currency" to "USD"),
    linkId = link?.linkId, // optional: attribute to the link that opened the app
)
```

Fire and forget. Events are written to a queue on the device first and sent in batches of up to 100 to `POST /v1/sdk/events`:

- They survive offline periods and app restarts (up to 1000 queued events; the oldest are dropped beyond that).
- They wait until the API knows the install (the first successful init).
- Failed batches are retried with exponential backoff (1 s doubling up to 5 min, with jitter, honoring `Retry-After`).
- Every event carries a client generated UUID, so a batch sent twice is counted once.
- The queue is flushed shortly after each event and when the app goes to the background.
- A batch the API rejects as invalid is dropped; an auth error pauses sending until the next launch, keeping the events.

`trackEvent` throws `IllegalArgumentException` for programming errors (empty name, name over 100 characters, values that are not JSON, a `linkId` that is not a UUID) and never throws for network errors.

### Java

Everything is available from Java through static methods, with callbacks on the main thread:

```java
Lander.initialize(this, "pk_live_...");
Lander.handleIntent(getIntent());

LanderSubscription sub = Lander.addDeepLinkListener(link -> navigate(link.getRoute()));

Lander.createLink(
    new LanderLinkOptions.Builder().path("/product/123").channel("share").build(),
    new LanderCallback<LanderShortLink>() {
        @Override public void onSuccess(LanderShortLink link) { share(link.getUrl()); }
        @Override public void onError(Exception error) { }
    });

Lander.trackEvent("signup");
```

`getInitialLink(callback)` and `claimHandoff(userId, callback)` work the same way.

### Install ID

`Lander.installId` is the random UUID v4 created on first launch. It is stored in `noBackupFilesDir`, so it is never restored onto another device, and it is gone when the app is uninstalled. If the API reports that the ID belongs to another project or environment (for example after switching from a `pk_test_` to a `pk_live_` key), the SDK creates a new one and reports a first launch.

## Privacy

- **Install ID only.** A random UUID v4 per install. No device model, no advertising ID (GAID), no device fingerprint.
- **What is sent:** the install ID, platform, app version, OS version, SDK version, the URLs that opened the app, the events you track, on first launch the Play Install Referrer string, and the user ID you pass to `claimHandoff` (the API stores only a hash of it).
- **Storage** lives in `noBackupFilesDir`, excluded from backups.

## Local development

With the Lander monorepo running (`pnpm dev`), the API listens on port 8787. Use the seeded test key and the emulator's address for the host:

```kotlin
Lander.initialize(
    this,
    apiKey = "pk_test_DemoPublishableKey000000000000",
    baseUrl = "http://10.0.2.2:8787",
    enableLogging = true,
)
```

Plain HTTP needs a debug network security config that permits cleartext to `10.0.2.2` (the example app has one). Open a link without tapping one:

```bash
adb shell am start -a android.intent.action.VIEW -d "https://acme.golander.link/p-123" com.acme.shop
adb shell am start -a android.intent.action.VIEW -d "acmeshop://product/123" com.acme.shop
```

## Example

[`example/`](example) is "Acme Shop", with a product screen at `/product/:id`. The seeded link `https://acme.golander.link/p-123` resolves to `/product/123`. It shows what Lander resolved for each link, creates share links and tracks purchases.

```bash
./gradlew :example:installDebug
```

Point it at another API with `-PlanderBaseUrl=https://api.golander.link -PlanderApiKey=pk_live_...`.

## Development

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :lander:testDebugUnitTest :lander:lint :example:assembleDebug
./gradlew :lander:publishToMavenLocal   # link.lander:lander-android:0.1.0 in ~/.m2
```

The SDK core (`LanderClient`) runs behind a `LanderPlatform` interface and an `HttpTransport`, so most tests run on the JVM with fakes and virtual time; Robolectric covers intents and storage.

## API

The SDK only talks to Lander's public API (`/v1/sdk/init`, `/v1/sdk/resolve`, `/v1/sdk/events`, `/v1/sdk/links`, `/v1/sdk/handoffs/claim`), never to the database. Requests carry `X-Lander-Sdk: android/<version>`. Every request has a 10 second timeout; every write carries an `Idempotency-Key` that stays the same across retries.
