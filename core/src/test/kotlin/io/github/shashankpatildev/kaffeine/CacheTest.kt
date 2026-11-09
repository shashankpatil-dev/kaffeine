package io.github.shashankpatildev.kaffeine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CacheTest {

    private fun cache(maximumSize: Long = 100) = kaffeine<String, Int> {
        this.maximumSize = maximumSize
        recordStats = true
    }

    @Test
    fun `returns what was put`() {
        val cache = cache()

        cache.put("a", 1)

        assertEquals(1, cache.getIfPresent("a"))
    }

    @Test
    fun `returns null for a missing key`() {
        assertNull(cache().getIfPresent("missing"))
    }

    @Test
    fun `put replaces the old value`() {
        val cache = cache()
        cache.put("a", 1)

        cache.put("a", 2)

        assertEquals(2, cache.getIfPresent("a"))
        assertEquals(1, cache.estimatedSize())
    }

    @Test
    fun `invalidate removes one key`() {
        val cache = cache()
        cache.put("a", 1)
        cache.put("b", 2)

        cache.invalidate("a")

        assertNull(cache.getIfPresent("a"))
        assertEquals(2, cache.getIfPresent("b"))
    }

    @Test
    fun `invalidateAll removes everything`() {
        val cache = cache()
        repeat(50) { cache.put("key$it", it) }

        cache.invalidateAll()

        assertEquals(0, cache.estimatedSize())
        assertNull(cache.getIfPresent("key0"))
    }

    @Test
    fun `never holds more than maximum size`() {
        val cache = cache(maximumSize = 100)

        repeat(10_000) { cache.put("key$it", it) }

        assertEquals(100, cache.estimatedSize())
    }

    @Test
    fun `keeps popular keys while many one-time keys pass through`() {
        val cache = cache(maximumSize = 100)
        repeat(5) { (0 until 50).forEach { cache.put("hot$it", it) } }

        repeat(500) { cache.put("once$it", it) }

        val lost = (0 until 50).filter { cache.getIfPresent("hot$it") == null }
        assertTrue(lost.isEmpty(), "hot keys lost: $lost")
    }

    @Test
    fun `counts hits, misses and evictions`() {
        val cache = cache(maximumSize = 10)
        repeat(20) { cache.put("key$it", it) }

        cache.getIfPresent("key19") // hit, the newest key is still in the window
        cache.getIfPresent("missing")

        val stats = cache.stats()
        assertEquals(1, stats.hitCount)
        assertEquals(1, stats.missCount)
        assertEquals(10, stats.evictionCount)
        assertEquals(0.5, stats.hitRate)
    }

    @Test
    fun `stats stay at zero when not turned on`() {
        val cache = kaffeine<String, Int> { maximumSize = 10 }
        cache.put("a", 1)
        cache.getIfPresent("a")

        assertEquals(CacheStats(0, 0, 0, 0, 0, 0), cache.stats())
    }

    @Test
    fun `rejects a missing or bad maximum size`() {
        assertFailsWith<IllegalArgumentException> { kaffeine<String, Int> { } }
        assertFailsWith<IllegalArgumentException> { kaffeine<String, Int> { maximumSize = 0 } }
    }
}
