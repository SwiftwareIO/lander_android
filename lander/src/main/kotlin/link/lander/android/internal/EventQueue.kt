package link.lander.android.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import link.lander.android.LanderException
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Persistent, deduplicating event queue.
 *
 * Events are stored before they are sent, sent in batches of up to
 * [batchSize], and removed only after the API accepts them. Retryable failures
 * back off exponentially; events keep their client generated `id`, so a batch
 * that is re-sent after a lost response is deduplicated by the API.
 *
 * Every method runs on [scope], which is single threaded (see LanderClient).
 */
internal class EventQueue(
    private val scope: CoroutineScope,
    private val read: suspend (String) -> String?,
    private val write: suspend (String, String) -> Unit,
    /** Sends one batch. Throws [LanderException] on failure. */
    private val sender: suspend (List<JSONObject>) -> Unit,
    private val logger: LanderLogger,
    private val batchSize: Int = 100,
    /**
     * Oldest events are dropped beyond this size so a long offline period
     * cannot grow storage without bound.
     */
    private val maxQueueSize: Int = 1000,
    /** Delay between an [add] and the flush it schedules, so bursts go out as one batch. */
    private val flushDelayMs: Long = 2_000,
    private val backoff: Backoff = Backoff(),
) {
    private val events = ArrayList<JSONObject>()
    private val ids = HashSet<String>()
    private val writeLock = Mutex()
    private val addLock = Mutex()
    private var loaded: Deferred<Unit>? = null
    private var flushing: Deferred<Unit>? = null
    private var timer: Job? = null
    private var failures = 0
    private var halted = false
    private var disposed = false

    /** Events waiting to be sent, oldest first. */
    val pending: List<JSONObject> get() = events.toList()

    /** Loads the persisted queue. Safe to call more than once. */
    suspend fun load() {
        val d = loaded ?: scope.async { doLoad() }.also { loaded = it }
        d.await()
    }

    private suspend fun doLoad() {
        try {
            val raw = read(STORAGE_KEY)
            if (raw.isNullOrEmpty()) return
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val e = array.opt(i)
                if (e is JSONObject && e.opt("id") is String) insert(e)
            }
            logger.log("Loaded ${events.size} queued events")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.log("Could not load the event queue, starting empty: $e")
        }
    }

    /**
     * Adds [event] (which must have a unique `id`) and schedules a flush.
     * Events with an `id` that is already queued are ignored.
     */
    suspend fun add(event: JSONObject) {
        // The lock keeps events in the order add was called while the queue loads.
        addLock.withLock {
            load()
            if (!insert(event)) return
            while (events.size > maxQueueSize) {
                ids.remove(events.removeAt(0).getString("id"))
            }
        }
        persist()
        schedule(flushDelayMs)
    }

    private fun insert(event: JSONObject): Boolean {
        val id = event.getString("id")
        if (!ids.add(id)) return false
        events.add(event)
        return true
    }

    /** Sends everything that is queued now. Concurrent calls share one flush. */
    suspend fun flush() {
        val d = flushing ?: scope.async {
            try {
                doFlush()
            } finally {
                flushing = null
            }
        }.also { flushing = it }
        d.await()
    }

    private suspend fun doFlush() {
        load()
        timer?.cancel()
        timer = null
        while (events.isNotEmpty() && !halted && !disposed) {
            val batch = events.take(batchSize)
            try {
                sender(batch)
                failures = 0
                remove(batch)
                logger.log("Sent ${batch.size} events")
            } catch (e: LanderException) {
                if (e.isRetryable) {
                    var wait = backoff.delayMs(failures++)
                    val retryAfter = e.retryAfter?.inWholeMilliseconds
                    if (retryAfter != null && retryAfter > wait) wait = retryAfter
                    logger.log("Event batch failed (${e.code}), retrying in ${wait / 1000}s")
                    schedule(wait, retry = true)
                    return
                }
                if (e.statusCode == 401 || e.statusCode == 403) {
                    // A bad key will not fix itself. Keep the events for the next launch.
                    logger.log("Event batch rejected (${e.code}): check the API key. Pausing until next launch.")
                    halted = true
                    return
                }
                // Validation errors: the batch will never be accepted, drop it so it
                // does not block the queue forever.
                logger.log("Dropping ${batch.size} events rejected by the API (${e.code}): ${e.message}")
                remove(batch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.log("Event batch failed unexpectedly: $e")
                schedule(backoff.delayMs(failures++), retry = true)
                return
            } finally {
                persist()
            }
        }
    }

    private fun remove(batch: List<JSONObject>) {
        val sent = batch.map { it.getString("id") }.toSet()
        events.removeAll { it.getString("id") in sent }
        ids.removeAll(sent)
    }

    /**
     * Schedules a flush. A pending timer (a scheduled flush or a backoff) is
     * kept, so new events never cut a backoff short; a [retry] replaces it.
     */
    private fun schedule(delayMs: Long, retry: Boolean = false) {
        if (disposed || halted) return
        if (timer != null && !retry) return
        timer?.cancel()
        timer = scope.launch {
            delay(delayMs)
            timer = null
            flush()
        }
    }

    private suspend fun persist() {
        val snapshot = JSONArray(events).toString()
        // Writes run in call order (the mutex is fair), so an older snapshot
        // never overwrites a newer one.
        writeLock.withLock {
            try {
                write(STORAGE_KEY, snapshot)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.log("Could not persist the event queue: $e")
            }
        }
    }

    fun dispose() {
        disposed = true
        timer?.cancel()
        timer = null
    }

    companion object {
        const val STORAGE_KEY = "event_queue"
    }
}
