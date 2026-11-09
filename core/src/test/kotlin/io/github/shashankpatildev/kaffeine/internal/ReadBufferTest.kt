package io.github.shashankpatildev.kaffeine.internal

import io.github.shashankpatildev.kaffeine.internal.ReadBuffer.Result
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadBufferTest {

    private val buffer = ReadBuffer<Int>()

    private fun drain(): List<Int> = mutableListOf<Int>().also { out -> buffer.drainTo { out += it } }

    @Test
    fun `drains what was offered, in order`() {
        repeat(10) { assertEquals(Result.ADDED, buffer.offer(it)) }

        assertEquals((0 until 10).toList(), drain())
        assertEquals(emptyList(), drain())
    }

    @Test
    fun `says full when the stripe has no room`() {
        repeat(ReadBuffer.RING_SIZE) { assertEquals(Result.ADDED, buffer.offer(it)) }

        assertEquals(Result.FULL, buffer.offer(99))
        assertEquals(ReadBuffer.RING_SIZE, drain().size)
    }

    @Test
    fun `starts with a single stripe`() {
        assertEquals(1, buffer.stripeCount)
    }

    @Test
    fun `has room again after a drain`() {
        repeat(ReadBuffer.RING_SIZE) { buffer.offer(it) }
        drain()

        assertEquals(Result.ADDED, buffer.offer(1))
    }

    @Test
    fun `every added element is drained exactly once while threads keep offering`() {
        val threads = 8
        val perThread = 20_000
        val added = AtomicInteger()
        val timesSeen = AtomicIntegerArray(threads * perThread)
        val writersDone = AtomicBoolean(false)

        val drainer = thread {
            while (!writersDone.get()) buffer.drainTo { timesSeen.incrementAndGet(it) }
        }
        val writers = (0 until threads).map { t ->
            thread {
                repeat(perThread) {
                    if (buffer.offer(t * perThread + it) == Result.ADDED) added.incrementAndGet()
                }
            }
        }
        writers.forEach { it.join() }
        writersDone.set(true)
        drainer.join()
        buffer.drainTo { timesSeen.incrementAndGet(it) } // whatever is left

        val seen = (0 until timesSeen.length()).map { timesSeen[it] }
        assertTrue(seen.none { it > 1 }, "an element was drained twice")
        assertEquals(added.get(), seen.count { it == 1 })
        assertTrue(buffer.stripeCount <= ReadBuffer.MAX_STRIPES)
    }
}
