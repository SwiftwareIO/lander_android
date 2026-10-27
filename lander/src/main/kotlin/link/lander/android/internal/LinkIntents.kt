package link.lander.android.internal

import android.content.Intent

/** Reads links out of intents, once. */
internal object LinkIntents {
    /** Extra put on an intent whose link was handed to the SDK. */
    const val HANDLED_EXTRA = "link.lander.android.HANDLED"

    /**
     * The URL of a VIEW [intent], or null. Marks the intent so the same link
     * is not delivered again when the activity is recreated (rotation) and
     * passes the same intent to `handleIntent` a second time.
     */
    fun take(intent: Intent?): String? {
        if (intent == null || intent.action != Intent.ACTION_VIEW) return null
        if (intent.getBooleanExtra(HANDLED_EXTRA, false)) return null
        // Reopening from Recents replays the original intent. It was handled already.
        if ((intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return null
        val data = intent.dataString?.takeIf { it.isNotEmpty() } ?: return null
        intent.putExtra(HANDLED_EXTRA, true)
        return data
    }

    /** Marks [intent] as handled without reading it. */
    fun markHandled(intent: Intent?) {
        intent?.putExtra(HANDLED_EXTRA, true)
    }
}
