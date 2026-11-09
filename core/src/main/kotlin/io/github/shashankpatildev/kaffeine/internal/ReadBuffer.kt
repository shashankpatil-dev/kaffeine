package io.github.shashankpatildev.kaffeine.internal

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * Where reads leave a note ("this node was read") for the eviction policy, without taking a lock.
 *
 * It is a set of small ring buffers called stripes. Each thread picks a stripe from its own
 * identity, so threads mostly write to different stripes and do not fight over one counter.
 * There is just one stripe at first, and the count doubles whenever two threads collide.
 * So a cache used by one thread stays cheap to drain, and a busy one spreads out.
 *
 * The buffer is allowed to lose notes. If a stripe is full, or another thread grabbed the
 * same slot first, the note is dropped. That only makes the LRU order a little less exact,
 * which costs far less than making a reader wait.
 *
 * Many threads may call [offer] at once. Only one thread at a time may call [drainTo].
 * The cache makes sure of that by draining only under its maintenance lock.
 */
internal class ReadBuffer<E : Any> {

    enum class Result {
        ADDED,

        /** The stripe has no room. The note was dropped, and it is time to drain. */
        FULL,

        /** Another thread took the slot at the same moment. The note was dropped. */
        LOST_RACE,
    }

    @Volatile
    private var stripes: List<Ring<E>> = listOf(Ring())
    private val growing = AtomicBoolean()

    /** How many stripes there are right now. */
    val stripeCount: Int get() = stripes.size

    fun offer(element: E): Result {
        val current = stripes
        val stripe = spread(System.identityHashCode(Thread.currentThread())) and (current.size - 1)
        val result = current[stripe].offer(element)
        if (result == Result.LOST_RACE) grow(current)
        return result
    }

    /** Hands every note to [consumer] and empties the buffer. */
    fun drainTo(consumer: (E) -> Unit) {
        for (ring in stripes) ring.drainTo(consumer)
    }

    /**
     * Two threads wanted the same slot at once, so there are too few stripes for the threads
     * using the cache. Double them. The old stripes stay at the front of the new list, so
     * nothing in them is lost. Only one thread grows the list; the others just carry on.
     */
    private fun grow(seen: List<Ring<E>>) {
        if (seen.size >= MAX_STRIPES || stripes !== seen || !growing.compareAndSet(false, true)) return
        try {
            if (stripes === seen) stripes = seen + List(seen.size) { Ring() }
        } finally {
            growing.set(false)
        }
    }

    /**
     * One stripe. Readers claim a slot by moving [writeCounter] forward with a compare-and-set,
     * then store their element in it. The drainer reads from [readCounter] up to [writeCounter].
     * Both counters only grow. `counter and (RING_SIZE - 1)` turns them into a slot index.
     */
    private class Ring<E : Any> {
        private val slots = AtomicReferenceArray<E?>(RING_SIZE)
        private val writeCounter = AtomicLong()

        @Volatile
        private var readCounter = 0L // only the drainer moves this

        fun offer(element: E): Result {
            val head = readCounter
            val tail = writeCounter.get()
            if (tail - head >= RING_SIZE) return Result.FULL
            if (!writeCounter.compareAndSet(tail, tail + 1)) return Result.LOST_RACE
            slots.lazySet(slotOf(tail), element)
            return Result.ADDED
        }

        fun drainTo(consumer: (E) -> Unit) {
            var head = readCounter
            val tail = writeCounter.get()
            while (head < tail) {
                val slot = slotOf(head)
                // A reader claimed this slot but has not stored its element yet. Pick it up next time.
                val element = slots.get(slot) ?: break
                slots.lazySet(slot, null)
                consumer(element)
                head++
            }
            readCounter = head
        }

        private fun slotOf(counter: Long): Int = (counter and (RING_SIZE - 1L)).toInt()
    }

    companion object {
        const val RING_SIZE = 16

        // shortcut: no padding between stripes against false sharing. Revisit if profiling shows contention.
        val MAX_STRIPES = ceilingPowerOfTwo(4L * Runtime.getRuntime().availableProcessors()).toInt()
    }
}
