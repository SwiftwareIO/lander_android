@file:OptIn(ExperimentalCoroutinesApi::class)

package link.lander.android.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import link.lander.android.LanderException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class EventQueueTest {
    private val storage = mutableMapOf<String, String>()
    private val sent = mutableListOf<List<String>>()
    private var failWith: MutableList<Exception> = mutableListOf()

    private fun TestScope.queue(batchSize: Int = 100, maxQueueSize: Int = 1000): Pair<EventQueue, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val q = EventQueue(
            scope = scope,
            read = { storage[it] },
            write = { k, v -> storage[k] = v },
            sender = { batch ->
                if (failWith.isNotEmpty()) throw failWith.removeAt(0)
                sent += batch.map { it.getString("id") }
            },
            logger = TestLog().logger,
            batchSize = batchSize,
            maxQueueSize = maxQueueSize,
            flushDelayMs = 2_000,
            backoff = Backoff(baseMs = 1_000, maxMs = 300_000, jitter = 0.0),
        )
        return q to scope
    }

    private fun event(id: String) = JSONObject().put("id", id).put("type", "custom").put("name", "e")

    private fun stored(): List<String> {
        val array = JSONArray(storage[EventQueue.STORAGE_KEY] ?: "[]")
        return List(array.length()) { array.getJSONObject(it).getString("id") }
    }

    @Test
    fun `persists before sending and sends one batch after the flush delay`() = runTest {
        val (q, scope) = queue()
        scope.launch {
            q.add(event("a"))
            q.add(event("b"))
        }
        runCurrent()
        assertEquals(listOf("a", "b"), stored())
        assertTrue(sent.isEmpty())
        advanceTimeBy(2_001)
        assertEquals(listOf(listOf("a", "b")), sent)
        assertEquals(emptyList<String>(), stored())
        scope.cancel()
    }

    @Test
    fun `duplicate ids are ignored`() = runTest {
        val (q, scope) = queue()
        scope.launch {
            q.add(event("a"))
            q.add(event("a"))
        }
        runCurrent()
        assertEquals(listOf("a"), stored())
        scope.cancel()
    }

    @Test
    fun `sends in batches and drops the oldest beyond the max size`() = runTest {
        val (q, scope) = queue(batchSize = 2, maxQueueSize = 3)
        scope.launch { listOf("a", "b", "c", "d").forEach { q.add(event(it)) } }
        runCurrent()
        assertEquals(listOf("b", "c", "d"), stored())
        advanceTimeBy(2_001)
        assertEquals(listOf(listOf("b", "c"), listOf("d")), sent)
        scope.cancel()
    }

    @Test
    fun `loads events persisted by an earlier launch`() = runTest {
        storage[EventQueue.STORAGE_KEY] = JSONArray().put(event("old")).toString()
        val (q, scope) = queue()
        scope.launch { q.flush() }
        runCurrent()
        assertEquals(listOf(listOf("old")), sent)
        scope.cancel()
    }

    @Test
    fun `a corrupt queue file starts empty`() = runTest {
        storage[EventQueue.STORAGE_KEY] = "{not json"
        val (q, scope) = queue()
        scope.launch { q.add(event("a")) }
        runCurrent()
        assertEquals(listOf("a"), stored())
        scope.cancel()
    }

    @Test
    fun `retryable failures back off exponentially and honor Retry-After`() = runTest {
        failWith = mutableListOf(
            LanderException("network_error", "offline"),
            LanderException("rate_limited", "slow", statusCode = 429, retryAfter = 10.seconds),
        )
        val (q, scope) = queue()
        scope.launch { q.add(event("a")) }
        advanceTimeBy(2_001) // first try fails, backoff 1 s
        assertTrue(sent.isEmpty())
        advanceTimeBy(1_000) // second try: 429, Retry-After 10 s beats the 2 s backoff
        assertTrue(sent.isEmpty())
        advanceTimeBy(9_000)
        assertTrue(sent.isEmpty())
        advanceTimeBy(1_001)
        assertEquals(listOf(listOf("a")), sent)
        scope.cancel()
    }

    @Test
    fun `new events do not cut a backoff short`() = runTest {
        failWith = mutableListOf(LanderException("network_error", "offline"), LanderException("network_error", "offline"))
        val (q, scope) = queue()
        scope.launch { q.add(event("a")) }
        advanceTimeBy(3_001) // fails at 2 s and at 3 s, next try at 5 s
        scope.launch { q.add(event("b")) }
        advanceTimeBy(1_500)
        assertTrue(sent.isEmpty())
        advanceTimeBy(1_000)
        assertEquals(listOf(listOf("a", "b")), sent)
        scope.cancel()
    }

    @Test
    fun `validation errors drop the batch, auth errors pause and keep it`() = runTest {
        failWith = mutableListOf(LanderException("validation_error", "bad", statusCode = 400))
        val (q, scope) = queue()
        scope.launch { q.add(event("a")) }
        advanceTimeBy(2_001)
        assertEquals(emptyList<String>(), stored())

        failWith = mutableListOf(LanderException("invalid_api_key", "bad key", statusCode = 401))
        scope.launch { q.add(event("b")) }
        advanceTimeBy(2_001)
        assertEquals(listOf("b"), stored())
        scope.launch { q.add(event("c")) }
        advanceTimeBy(60_000)
        assertTrue(sent.isEmpty())
        assertEquals(listOf("b", "c"), stored())
        scope.cancel()
    }
}