@file:OptIn(ExperimentalCoroutinesApi::class)

package link.lander.android.internal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import link.lander.android.LanderLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkDispatcherTest {
    private val a = LanderLink.unresolved("acmeshop://a")
    private val b = LanderLink.unresolved("acmeshop://b")
    private val c = LanderLink.unresolved("acmeshop://c")

    @Test
    fun `links that arrive before anyone collects are replayed in order`() = runTest {
        val d = LinkDispatcher()
        d.add(a)
        d.add(b)
        val got = mutableListOf<LanderLink>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { d.flow.collect { got += it } }
        d.add(c)
        runCurrent()
        assertEquals(listOf(a, b, c), got)
    }

    @Test
    fun `links buffer again while nobody collects`() = runTest {
        val d = LinkDispatcher()
        val first = mutableListOf<LanderLink>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { d.flow.collect { first += it } }
        d.add(a)
        job.cancel()
        runCurrent()
        d.add(b)
        val second = mutableListOf<LanderLink>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { d.flow.collect { second += it } }
        runCurrent()
        assertEquals(listOf(a), first)
        assertEquals(listOf(b), second)
    }

    @Test
    fun `the launch link goes to the stream when nobody asks for it`() = runTest {
        val d = LinkDispatcher()
        d.setInitialLink(a)
        val got = mutableListOf<LanderLink>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { d.flow.collect { got += it } }
        runCurrent()
        assertEquals(listOf(a), got)
        assertNull(d.takeInitialLink())
    }

    @Test
    fun `takeInitialLink claims the launch link once and the stream skips it`() = runTest {
        val d = LinkDispatcher()
        val initial = async { d.takeInitialLink() }
        runCurrent()
        d.setInitialLink(a)
        d.add(b)
        assertEquals(a, initial.await())
        assertNull(d.takeInitialLink())
        val got = mutableListOf<LanderLink>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { d.flow.collect { got += it } }
        runCurrent()
        assertEquals(listOf(b), got)
    }

    @Test
    fun `a buffered launch link is removed from the stream when taken`() = runTest {
        val d = LinkDispatcher()
        d.setInitialLink(a)
        assertEquals(a, d.takeInitialLink())
        val got = mutableListOf<LanderLink>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { d.flow.collect { got += it } }
        runCurrent()
        assertTrue(got.isEmpty())
    }

    @Test
    fun `a second initial link is delivered as a normal link`() = runTest {
        val d = LinkDispatcher()
        d.setInitialLink(null)
        assertTrue(d.initialSettled)
        d.setInitialLink(a)
        val got = mutableListOf<LanderLink>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { d.flow.collect { got += it } }
        runCurrent()
        assertEquals(listOf(a), got)
        assertNull(d.takeInitialLink())
    }

    @Test
    fun `every subscriber gets live links`() = runTest {
        val d = LinkDispatcher()
        val one = mutableListOf<LanderLink>()
        val two = mutableListOf<LanderLink>()
        d.subscribe { one += it }
        val sub = d.subscribe { two += it }
        d.add(a)
        sub.remove()
        d.add(b)
        assertEquals(listOf(a, b), one)
        assertEquals(listOf(a), two)
    }
}