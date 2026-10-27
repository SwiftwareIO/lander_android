## 0.1.0

First release of the native Android SDK (`link.golander:lander-android`), with the same behavior as `lander_flutter` 0.3.0:

* `Lander.initialize` in `Application.onCreate`: validates the publishable key (rejects `sk_` keys and never echoes the key), creates the random install ID and reports the launch with `POST /v1/sdk/init` in the background.
* `Lander.handleIntent` for App Links and custom scheme URLs on cold and warm start. Each intent is read once, so recreated activities (rotation, process death, reopening from Recents) do not deliver a link twice.
* `Lander.deepLinks` (a `Flow`) and `Lander.addDeepLinkListener` (Java): links are buffered until collected, the launch link is delivered once, duplicates within two seconds are dropped, and unknown links still arrive unresolved.
* `Lander.getInitialLink()` for the launch link, including a deferred link on first launch.
* Deferred deep links from the Play Install Referrer (`install_referrer`, deterministic). A link that opened the app wins over a deferred one.
* `Lander.claimHandoff(userId)` for authenticated handoffs.
* `Lander.createLink(...)` with retries under one `Idempotency-Key` and at most 10 seconds of total retry wait.
* `Lander.trackEvent(...)`: persistent, batched event queue with client event IDs, exponential backoff and `Retry-After`, flushed when the app goes to the background.
* `install_id_conflict` creates a new install ID; `install_not_found` on events registers the install again and keeps the events.
* State is stored in `noBackupFilesDir`, so it is never restored onto another device.
* Callback variants of every suspending call for Java.
