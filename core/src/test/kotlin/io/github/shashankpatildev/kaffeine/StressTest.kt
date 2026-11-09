package io.github.shashankpatildev.kaffeine

import io.github.shashankpatildev.kaffeine.internal.BoundedCache
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ForkJoinPool
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Many threads hammer one small cache with random operations, then we check that nothing broke.
 *
 * Every value is `key * 31`, so if a read ever returns a value that belongs to another key,
 * we notice right away.
 */
class StressTest {

    @Test
    fun `stays correct and bounded under many threads`() {
        val cache = kaffeine<Int, Int> {
            maximumSize = 100
            executor = ForkJoinPool.commonPool() // upkeep on a pool too: the setup with the most moving parts
        }
        val failures = ConcurrentLinkedQueue<Throwable>()
        val start = CountDownLatch(1)

        val workers = (0 until 8).map { id ->
            thread {
                val random = Random(id)
                start.await()
                try {
                    repeat(100_000) { randomOperation(cache, random) }
                } catch (e: Throwable) {
                    failures += e
                }
            }
        }
        start.countDown()
        workers.forEach { it.join() }

        assertTrue(failures.isEmpty(), "worker failed: ${failures.firstOrNull()}")

        cache.cleanUp()
        val size = cache.estimatedSize()
        assertTrue(size <= 100, "cache holds $size entries, more than its maximum of 100")
        assertEquals(size.toInt(), (cache as BoundedCache).policySize, "the map and the policy disagree")
    }

    private fun randomOperation(cache: Cache<Int, Int>, random: Random) {
        val key = random.nextInt(1_000)
        when (random.nextInt(100)) {
            in 0 until 60 -> {
                val value = cache.getIfPresent(key)
                check(value == null || value == key * 31) { "key $key returned $value" }
            }
            in 60 until 95 -> cache.put(key, key * 31)
            in 95 until 99 -> cache.invalidate(key)
            else -> cache.invalidateAll()
        }
    }
}
