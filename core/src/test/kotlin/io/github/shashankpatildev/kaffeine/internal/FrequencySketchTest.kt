package io.github.shashankpatildev.kaffeine.internal

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrequencySketchTest {

    @Test
    fun `counts how often a key was used`() {
        val sketch = FrequencySketch(maximumSize = 1_000, useDoorkeeper = false)
        val key = spread(42)

        repeat(5) { sketch.increment(key) }

        assertEquals(5, sketch.frequency(key))
    }

    @Test
    fun `never counts too low`() {
        val sketch = FrequencySketch(maximumSize = 512, useDoorkeeper = false)
        val random = Random(1)
        val trueCounts = IntArray(1_000)

        // Stay below the sample size so no halving happens in the middle.
        repeat(4_000) {
            val key = random.nextInt(trueCounts.size)
            trueCounts[key]++
            sketch.increment(spread(key))
        }

        for (key in trueCounts.indices) {
            val expected = minOf(trueCounts[key], 15)
            val estimate = sketch.frequency(spread(key))
            assertTrue(estimate >= expected, "key $key: estimate $estimate is below the true count $expected")
        }
    }

    @Test
    fun `stops counting at 15`() {
        val sketch = FrequencySketch(maximumSize = 1_000, useDoorkeeper = false)
        val key = spread(42)

        repeat(100) { sketch.increment(key) }

        assertEquals(15, sketch.frequency(key))
    }

    @Test
    fun `halves every counter after sample size additions`() {
        val sketch = FrequencySketch(maximumSize = 64, useDoorkeeper = false)
        val key = spread(42)

        repeat((sketch.sampleSize - 1).toInt()) { sketch.increment(key) }
        assertEquals(15, sketch.frequency(key))

        sketch.increment(key) // this one reaches the sample size

        assertEquals(7, sketch.frequency(key)) // 15 / 2, rounded down
        assertEquals(sketch.sampleSize / 2, sketch.size)
    }

    @Test
    fun `doorkeeper holds the first sighting and the sketch counts the rest`() {
        val sketch = FrequencySketch(maximumSize = 1_000, useDoorkeeper = true)
        val key = spread(42)

        assertEquals(0, sketch.frequency(key))

        sketch.increment(key)
        assertEquals(1, sketch.frequency(key))

        repeat(4) { sketch.increment(key) }
        assertEquals(5, sketch.frequency(key))
    }
}
