package io.github.shashankpatildev.kaffeine.bench

import java.util.zip.GZIPOutputStream
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimulatorTest {

    // Keys A=1, B=2, C=3, D=4. The trace "A B C A D B" from the learning notes.
    private val abcadb = longArrayOf(1, 2, 3, 1, 4, 2)

    @Test
    fun `reads LIRS traces and skips lines that are not numbers`() {
        val file = createTempFile(suffix = ".trace").apply { writeText("5\n7\n*\n5\n") }

        assertContentEquals(longArrayOf(5, 7, 5), TraceFormat.of(file).read(file))
    }

    @Test
    fun `reads gzipped LIRS traces`() {
        val file = createTempFile(suffix = ".trace.gz")
        GZIPOutputStream(file.toFile().outputStream()).bufferedWriter().use { it.write("1\n2\n") }

        assertContentEquals(longArrayOf(1, 2), TraceFormat.of(file).read(file))
    }

    @Test
    fun `reads ARC traces and expands block ranges`() {
        val file = createTempFile(suffix = ".lis").apply { writeText("100 3 0 1\n7 1 0 2\n") }

        assertContentEquals(longArrayOf(100, 101, 102, 7), TraceFormat.of(file).read(file))
    }

    @Test
    fun `LRU gets the hits worked out by hand`() {
        // Size 3: A B C miss, A hits, D evicts B, B misses again.
        assertEquals(1, simulateLru(abcadb, cacheSize = 3))
    }

    @Test
    fun `Belady gets the hits worked out by hand`() {
        // Size 3: at D it evicts A or C (never used again) instead of B, so B hits at the end.
        assertEquals(2, simulateBelady(abcadb, cacheSize = 3))
    }

    @Test
    fun `no policy beats Belady`() {
        val trace = skewedTrace(requests = 50_000, keys = 5_000)

        for (size in listOf(50, 500, 2_000)) {
            val best = simulateBelady(trace, size)
            val others = mapOf(
                "lru" to simulateLru(trace, size),
                "kaffeine" to simulateKaffeine(trace, size, doorkeeper = true),
                "kaffeine-no-doorkeeper" to simulateKaffeine(trace, size, doorkeeper = false),
                "caffeine" to simulateCaffeine(trace, size),
            )
            for ((name, hits) in others) {
                assertTrue(hits <= best, "$name got $hits hits at size $size, but Belady only got $best")
            }
        }
    }

    /** Some keys are much more popular than others, like real traffic. */
    private fun skewedTrace(requests: Int, keys: Int): LongArray {
        val random = Random(3)
        return LongArray(requests) {
            val r = random.nextDouble()
            (r * r * r * keys).toLong() // cubing pushes most requests towards the small keys
        }
    }
}
