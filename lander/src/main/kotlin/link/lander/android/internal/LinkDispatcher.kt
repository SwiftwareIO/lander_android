package link.lander.android.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import link.lander.android.LanderLink
import link.lander.android.LanderSubscription

/**
 * Delivers links to the app without losing or doubling any.
 *
 * - Links that arrive while nobody collects [flow] (or listens) are buffered
 *   and replayed, in order, to the next subscriber. This covers cold starts
 *   and links that arrive before the UI is ready.
 * - The link that launched the app is delivered exactly once: through
 *   [takeInitialLink] when the app asks for it before a subscriber got it,
 *   otherwise to the subscribers. Later [takeInitialLink] calls return null.
 *
 * Thread safe. Subscribers are called with the lock held, so they must not
 * block: the flow sends into an unlimited channel and listeners post to the
 * main thread.
 */
internal class LinkDispatcher {
    private class Pending(val link: LanderLink, val initial: Boolean = false)

    private val lock = Any()
    private val subscribers = LinkedHashSet<(LanderLink) -> Unit>()
    private val buffer = ArrayDeque<Pending>()
    private val initial = CompletableDeferred<LanderLink?>()
    private var initialRequested = false
    private var initialDelivered = false
    private var initialTaken = false

    /** Every link, buffered while nobody collects. */
    val flow: Flow<LanderLink> = callbackFlow {
        val subscription = subscribe { trySend(it) }
        awaitClose { subscription.remove() }
    }.buffer(Channel.UNLIMITED)

    /** Whether the launch link is known (it may be null). */
    val initialSettled: Boolean get() = initial.isCompleted

    /** Sets the link that launched the app. Only the first call counts; later links are added. */
    fun setInitialLink(link: LanderLink?) {
        synchronized(lock) {
            if (initial.isCompleted) {
                if (link != null) emit(Pending(link))
                return
            }
            initial.complete(link)
            if (link == null || initialRequested) return
            emit(Pending(link, initial = true))
        }
    }

    /** Delivers a link that arrived while the app was running. */
    fun add(link: LanderLink) {
        synchronized(lock) { emit(Pending(link)) }
    }

    /** Returns the launch link unless a subscriber or an earlier call already got it. */
    suspend fun takeInitialLink(): LanderLink? {
        synchronized(lock) { initialRequested = true }
        val link = initial.await()
        synchronized(lock) {
            if (initialDelivered || initialTaken) return null
            initialTaken = true
            buffer.removeAll { it.initial }
            return link
        }
    }

    fun subscribe(deliver: (LanderLink) -> Unit): LanderSubscription {
        synchronized(lock) {
            subscribers.add(deliver)
            drain()
        }
        return LanderSubscription { synchronized(lock) { subscribers.remove(deliver) } }
    }

    private fun emit(pending: Pending) {
        if (subscribers.isNotEmpty() && buffer.isEmpty()) {
            deliver(pending)
        } else {
            buffer.addLast(pending)
            drain()
        }
    }

    private fun drain() {
        while (buffer.isNotEmpty() && subscribers.isNotEmpty()) deliver(buffer.removeFirst())
    }

    private fun deliver(pending: Pending) {
        if (pending.initial) {
            if (initialRequested) return
            initialDelivered = true
        }
        // Copy: a subscriber may remove itself while being called.
        for (s in subscribers.toList()) s(pending.link)
    }
}
