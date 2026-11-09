package io.github.shashankpatildev.kaffeine.internal

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BloomFilterTest {

    @Test
    fun `never forgets a key it has seen`() {
        val filter = BloomFilter(expectedInsertions = 10_000)
        repeat(10_000) { filter.put(spread(it)) }

        repeat(10_000) { assertTrue(filter.mightContain(spread(it)), "forgot key $it") }
    }

    @Test
    fun `rarely says yes to keys it has not seen`() {
        val filter = BloomFilter(expectedInsertions = 10_000)
        repeat(10_000) { filter.put(spread(it)) }

        val falsePositives = (10_000 until 110_000).count { filter.mightContain(spread(it)) }
        val rate = falsePositives / 100_000.0

        assertTrue(rate < 0.03, "false positive rate was $rate")
    }

    @Test
    fun `put returns true only the first time`() {
        val filter = BloomFilter(expectedInsertions = 100)

        assertTrue(filter.put(spread(42)))
        assertFalse(filter.put(spread(42)))
    }

    @Test
    fun `clear forgets everything`() {
        val filter = BloomFilter(expectedInsertions = 100)
        filter.put(spread(42))

        filter.clear()

        assertFalse(filter.mightContain(spread(42)))
    }
}
