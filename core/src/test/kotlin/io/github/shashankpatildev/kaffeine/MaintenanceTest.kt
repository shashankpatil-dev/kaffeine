package io.github.shashankpatildev.kaffeine

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Where the cache does its upkeep: the background pool, a custom executor, or the calling thread. */
class MaintenanceTest {

    @Test
    fun `upkeep runs in the background by default`() {
        val cache = kaffeine<Int, Int> { maximumSize = 100 }

        repeat(10_000) { cache.put(it, it) }

        // No cleanUp(): the background pool alone must bring the cache back under its limit.
        val giveUpAt = System.nanoTime() + 5.seconds.inWholeNanoseconds
        while (cache.estimatedSize() > 100 && System.nanoTime() < giveUpAt) Thread.yield()
        assertTrue(cache.estimatedSize() <= 100, "the cache still holds ${cache.estimatedSize()} entries")
    }

    @Test
    fun `upkeep runs on the configured executor`() {
        val jobs = AtomicInteger()
        val cache = kaffeine<Int, Int> {
            maximumSize = 10
            executor = Executor { job -> jobs.incrementAndGet(); job.run() }
        }

        repeat(100) { cache.put(it, it) }

        assertTrue(jobs.get() > 0)
        assertEquals(10, cache.estimatedSize())
    }

    @Test
    fun `upkeep falls back to the calling thread when the executor refuses`() {
        val cache = kaffeine<Int, Int> {
            maximumSize = 10
            executor = Executor { throw RejectedExecutionException("shut down") }
        }

        repeat(100) { cache.put(it, it) }

        assertEquals(10, cache.estimatedSize())
    }
}
