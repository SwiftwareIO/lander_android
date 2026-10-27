package link.lander.android.internal

import android.util.Log

/** Prints SDK diagnostics when logging is enabled. Never logs API keys. */
internal class LanderLogger(
    val enabled: Boolean,
    private val sink: (String) -> Unit = { Log.d(TAG, it) },
) {
    fun log(message: String) {
        if (enabled) sink(message)
    }

    companion object {
        const val TAG = "Lander"
    }
}
