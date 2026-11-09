package io.github.shashankpatildev.kaffeine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ExpiryTest {

    private val ticker = FakeTicker()

    @Test
    fun `an entry expires a while after it was written`() {
        val cache = kaffeine<String, Int> {
            maximumSize = 100
            expireAfterWrite = 10.seconds
            ticker = this@ExpiryTest.ticker
        }
        cache.put("a", 1)

        ticker.advance(9.seconds)
        assertEquals(1, cache.getIfPresent("a"))

        ticker.advance(2.seconds)
        assertNull(cache.getIfPresent("a"))
    }

    @Test
    fun `replacing the value restarts expire after write`() {
        val cache = kaffeine<String, Int> {
            maximumSize = 100
            expireAfterWrite = 10.seconds
            ticker = this@ExpiryTest.ticker
        }
        cache.put("a", 1)
        ticker.advance(8.seconds)

        cache.put("a", 2)
        ticker.advance(8.seconds)

        assertEquals(2, cache.getIfPresent("a"))
    }

    @Test
    fun `reading keeps an entry alive with expire after access`() {
        val cache = kaffeine<String, Int> {
            maximumSize = 100
            expireAfterAccess = 10.seconds
            ticker = this@ExpiryTest.ticker
        }
        cache.put("a", 1)

        repeat(5) {
            ticker.advance(6.seconds)
            assertEquals(1, cache.getIfPresent("a"), "read number $it")
        }

        ticker.advance(11.seconds)
        assertNull(cache.getIfPresent("a"))
    }

    @Test
    fun `each entry can have its own lifetime`() {
        val cache = kaffeine<String, Int> {
            maximumSize = 100
            expireAfter = { _, seconds -> seconds.seconds }
            ticker = this@ExpiryTest.ticker
        }
        cache.put("short", 5)
        cache.put("long", 60)

        ticker.advance(10.seconds)

        assertNull(cache.getIfPresent("short"))
        assertEquals(60, cache.getIfPresent("long"))
    }

    @Test
    fun `a lifetime of zero or less expires right away`() {
        val cache = kaffeine<String, Duration> {
            maximumSize = 100
            expireAfter = { _, lifetime -> lifetime }
            ticker = this@ExpiryTest.ticker
        }
        cache.put("zero", Duration.ZERO)
        cache.put("negative", (-5).seconds)

        assertNull(cache.getIfPresent("zero"))
        assertNull(cache.getIfPresent("negative"))
    }

    @Test
    fun `cleanUp removes expired entries from the map`() {
        val cache = kaffeine<Int, Int> {
            maximumSize = 1_000
            expireAfterWrite = 1.minutes
            recordStats = true
            ticker = this@ExpiryTest.ticker
        }
        repeat(100) { cache.put(it, it) }

        ticker.advance(2.minutes)
        cache.cleanUp()

        assertEquals(0, cache.estimatedSize())
        assertEquals(100, cache.stats().evictionCount)
    }

    @Test
    fun `entries that are not due stay after cleanUp`() {
        val cache = kaffeine<Int, Int> {
            maximumSize = 1_000
            expireAfter = { key, _ -> if (key < 50) 1.minutes else 1.hours }
            ticker = this@ExpiryTest.ticker
        }
        repeat(100) { cache.put(it, it) }

        ticker.advance(2.minutes)
        cache.cleanUp()

        assertEquals(50, cache.estimatedSize())
        assertNull(cache.getIfPresent(0))
        assertEquals(99, cache.getIfPresent(99))
    }

    @Test
    fun `putting again brings an expired key back`() {
        val cache = kaffeine<String, Int> {
            maximumSize = 100
            expireAfterWrite = 10.seconds
            ticker = this@ExpiryTest.ticker
        }
        cache.put("a", 1)
        ticker.advance(11.seconds)

        cache.put("a", 2)

        assertEquals(2, cache.getIfPresent("a"))
    }

    @Test
    fun `rejects bad expiry settings`() {
        assertFailsWith<IllegalArgumentException> {
            kaffeine<String, Int> {
                maximumSize = 10
                expireAfterWrite = 1.minutes
                expireAfterAccess = 1.minutes
            }
        }
        assertFailsWith<IllegalArgumentException> {
            kaffeine<String, Int> {
                maximumSize = 10
                expireAfterWrite = Duration.ZERO
            }
        }
    }
}
