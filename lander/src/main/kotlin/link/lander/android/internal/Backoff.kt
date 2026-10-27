package link.lander.android.internal

import kotlin.math.min
import kotlin.random.Random

/** Exponential backoff with jitter: `base * 2^attempt`, capped at [maxMs]. */
internal class Backoff(
    val baseMs: Long = 1_000,
    val maxMs: Long = 5 * 60_000,
    /** Fraction of the delay that is randomized, between 0 and 1. */
    val jitter: Double = 0.2,
) {
    /** Delay in milliseconds before retry number [attempt] (0 based). */
    fun delayMs(attempt: Int, random: Random = Random.Default): Long {
        val exp = baseMs.toDouble() * (1L shl min(attempt, 20))
        val capped = min(exp, maxMs.toDouble())
        if (jitter <= 0) return Math.round(capped)
        // Spread over [capped * (1 - jitter), capped].
        return Math.round(capped * (1 - jitter * random.nextDouble()))
    }
}
