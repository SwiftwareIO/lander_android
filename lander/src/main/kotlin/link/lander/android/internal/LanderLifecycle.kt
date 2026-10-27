package link.lander.android.internal

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Watches activities so the app does not have to forward lifecycle events:
 * flushes events when the app goes to the background, retries a failed init
 * when it comes back, and keeps a link from being delivered twice after
 * process death.
 */
internal class LanderLifecycle(
    private val client: () -> LanderClient?,
) : Application.ActivityLifecycleCallbacks {
    private var started = 0

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        // Runs inside super.onCreate, before the app calls Lander.handleIntent.
        // After process death Android recreates the activity with its original
        // VIEW intent and the saved state. That link was delivered before the
        // process died, so do not deliver it again.
        if (savedInstanceState != null) LinkIntents.markHandled(activity.intent)
    }

    override fun onActivityStarted(activity: Activity) {
        if (started++ == 0) client()?.onForeground()
    }

    override fun onActivityResumed(activity: Activity) {
        client()?.settleLaunchIfNeeded()
    }

    override fun onActivityPaused(activity: Activity) {}

    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0 && !activity.isChangingConfigurations) client()?.onBackground()
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    override fun onActivityDestroyed(activity: Activity) {}
}
