package io.github.shashankpatildev.kaffeine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class LoadingTest {

    /** Loads run on the test's own scheduler, so delay() inside a loader uses virtual time. */
    private fun TestScope.cache(configure: KaffeineConfig<String, String>.() -> Unit = {}) =
        kaffeine<String, String> {
            maximumSize = 100
            loadContext = StandardTestDispatcher(testScheduler)
            configure()
        }

    @Test
    fun `loads a missing key once, then serves it from the cache`() = runTest {
        val cache = cache()
        var calls = 0
        val loader: suspend (String) -> String = { calls++; "value of $it" }

        assertEquals("value of a", cache.get("a", loader))
        assertEquals("value of a", cache.get("a", loader))

        assertEquals(1, calls)
        assertEquals("value of a", cache.getIfPresent("a"))
    }

    @Test
    fun `a thousand callers asking for the same key share one load`() = runTest {
        val cache = cache()
        val calls = AtomicInteger()

        val results = List(1_000) {
            async {
                cache.get("hot") {
                    delay(100) // a slow database call
                    calls.incrementAndGet()
                    "loaded"
                }
            }
        }.awaitAll()

        assertEquals(1, calls.get())
        assertTrue(results.all { it == "loaded" })
    }

    @Test
    fun `callers on many real threads still share one load`() {
        val cache = kaffeine<String, String> { maximumSize = 10 }
        val calls = AtomicInteger()
        val results = ConcurrentLinkedQueue<String>()
        val start = CountDownLatch(1)

        val threads = List(16) {
            thread {
                start.await()
                results += runBlocking { cache.get("hot") { delay(50); calls.incrementAndGet(); "loaded" } }
            }
        }
        start.countDown()
        threads.forEach { it.join() }

        assertEquals(1, calls.get())
        assertEquals(List(16) { "loaded" }, results.toList())
    }

    @Test
    fun `a failed load reaches every waiter and is not cached`() = runTest {
        val cache = cache()
        var calls = 0
        val failing: suspend (String) -> String = {
            calls++
            delay(10)
            throw IllegalStateException("database is down")
        }

        val outcomes = List(3) { async { runCatching { cache.get("a", failing) } } }.awaitAll()

        assertTrue(outcomes.all { it.exceptionOrNull() is IllegalStateException })
        assertEquals(1, calls)
        assertNull(cache.getIfPresent("a"))
        assertEquals("ok", cache.get("a") { "ok" }) // the next call tries again
    }

    @Test
    fun `cancelling one waiter does not cancel the load for the others`() = runTest {
        val cache = cache()
        val loader: suspend (String) -> String = { delay(100); "loaded" }
        val first = async { cache.get("a", loader) }
        val second = async { cache.get("a", loader) }
        runCurrent() // both are now waiting on the same load

        first.cancel()

        assertEquals("loaded", second.await())
        assertTrue(first.isCancelled)
    }

    @Test
    fun `the load still finishes and is cached when every waiter is cancelled`() = runTest {
        val cache = cache()
        val waiter = launch { cache.get("a") { delay(100); "loaded" } }
        runCurrent()

        waiter.cancel()
        advanceUntilIdle()

        assertEquals("loaded", cache.getIfPresent("a"))
    }

    @Test
    fun `invalidating a key while it loads keeps the value out of the cache`() = runTest {
        val cache = cache()
        val waiter = async { cache.get("a") { delay(100); "stale" } }
        runCurrent()

        cache.invalidate("a")

        assertEquals("stale", waiter.await()) // the caller still gets what it asked for
        assertNull(cache.getIfPresent("a"))
    }

    @Test
    fun `a put while loading wins over the slower load`() = runTest {
        val cache = cache()
        val waiter = async { cache.get("a") { delay(100); "old" } }
        runCurrent()

        cache.put("a", "new")

        assertEquals("old", waiter.await()) // the caller still gets what it waited for
        assertEquals("new", cache.getIfPresent("a"))
    }

    @Test
    fun `an expired value is loaded again`() = runTest {
        val ticker = FakeTicker()
        val cache = cache {
            expireAfterWrite = 1.minutes
            this.ticker = ticker
        }
        cache.get("a") { "first" }

        ticker.advance(2.minutes)

        assertEquals("second", cache.get("a") { "second" })
    }

    @Test
    fun `loaders run on the configured context`() = runTest {
        val loads = StandardTestDispatcher(testScheduler)
        val cache = kaffeine<String, String> {
            maximumSize = 10
            loadContext = loads
        }
        var dispatcherSeen: Any? = null

        cache.get("a") {
            dispatcherSeen = currentCoroutineContext()[ContinuationInterceptor]
            "value"
        }

        assertSame(loads, dispatcherSeen)
    }

    @Test
    fun `counts successful and failed loads`() = runTest {
        val ticker = FakeTicker()
        val cache = cache {
            recordStats = true
            this.ticker = ticker
        }

        cache.get("a") { ticker.advance(1.minutes); "value" }
        runCatching { cache.get("b") { error("database is down") } }
        cache.get("a") { "not called, this one is a hit" }

        val stats = cache.stats()
        assertEquals(1, stats.loadSuccessCount)
        assertEquals(1, stats.loadFailureCount)
        assertEquals(1.minutes.inWholeNanoseconds, stats.totalLoadTimeNanos)
        assertEquals(1, stats.hitCount)
        assertEquals(2, stats.missCount)
    }

    @Test
    fun `rejects a load context that has a Job`() {
        assertFailsWith<IllegalArgumentException> {
            kaffeine<String, String> {
                maximumSize = 10
                loadContext = Job()
            }
        }
    }
}
