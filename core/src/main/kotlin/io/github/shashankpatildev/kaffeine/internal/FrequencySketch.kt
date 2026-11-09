package io.github.shashankpatildev.kaffeine.internal

/**
 * A Count-Min Sketch that guesses how often each key was used, in very little memory.
 *
 * How it works:
 * - Counters are 4 bits each (0 to 15), and 16 of them are packed into one Long.
 * - Every key maps to 4 counters, picked by 4 different hashes.
 * - Adding a key bumps all 4 counters. Reading takes the smallest of the 4, because
 *   collisions with other keys can only push a counter up, never down.
 *
 * Aging: after `10 x maximumSize` additions, every counter is halved. This lets old
 * popularity fade, so yesterday's hot key does not stay hot forever.
 *
 * Doorkeeper: when turned on, a key's first sighting only goes into a small Bloom filter.
 * The sketch starts counting from the second sighting. Most keys are seen just once, so
 * this keeps them from crowding the counters.
 *
 * Not thread safe. The caller must hold the policy lock.
 */
internal class FrequencySketch(maximumSize: Long, useDoorkeeper: Boolean) {

    private val table: LongArray
    private val tableMask: Int
    private val doorkeeper: BloomFilter?

    /** After this many additions, all counters get halved. */
    val sampleSize: Long

    /** Additions since the last halving. */
    var size: Long = 0
        private set

    init {
        val capacity = maximumSize.coerceIn(1, MAX_TABLE_SIZE)
        table = LongArray(ceilingPowerOfTwo(capacity).toInt())
        tableMask = table.size - 1
        sampleSize = 10 * capacity
        doorkeeper = if (useDoorkeeper) BloomFilter(sampleSize) else null
    }

    /** Records one use of the key with this (already spread) hash. */
    fun increment(hash: Int) {
        val firstSighting = doorkeeper?.put(hash) ?: false
        if (!firstSighting) {
            val start = (hash and 3) shl 2 // which group of 4 counters inside each Long
            for (i in 0 until DEPTH) {
                val index = indexOf(hash, i)
                val shift = counterShift(start, i)
                if ((table[index] ushr shift) and COUNTER_MAX != COUNTER_MAX) {
                    table[index] += 1L shl shift
                }
            }
        }
        if (++size >= sampleSize) reset()
    }

    /** Estimated number of uses of the key, from 0 to 16. It may be a little high, but never too low. */
    fun frequency(hash: Int): Int {
        val start = (hash and 3) shl 2
        var min = COUNTER_MAX.toInt()
        for (i in 0 until DEPTH) {
            val count = (table[indexOf(hash, i)] ushr counterShift(start, i)) and COUNTER_MAX
            min = minOf(min, count.toInt())
        }
        // The first sighting lives in the doorkeeper instead of the counters, so add it back.
        val seenBefore = doorkeeper?.mightContain(hash) ?: false
        return if (seenBefore) min + 1 else min
    }

    /** Halves every counter. This is the "aging" step. */
    private fun reset() {
        for (i in table.indices) {
            // Shifting right by one halves all 16 counters at once. The mask clears the bit
            // that slid down into each counter from its neighbour.
            table[i] = (table[i] ushr 1) and RESET_MASK
        }
        size /= 2
        doorkeeper?.clear()
    }

    /** Which Long in the table holds the key's counter for row [i]. */
    private fun indexOf(hash: Int, i: Int): Int {
        var h = (hash + SEED[i]) * SEED[i]
        h += h ushr 32
        return h.toInt() and tableMask
    }

    /** Bit position of the key's counter for row [i] inside its Long: 0, 4, 8, ... 60. */
    private fun counterShift(start: Int, i: Int): Int = (start + i) shl 2

    private companion object {
        const val DEPTH = 4
        const val COUNTER_MAX = 0xFL
        const val RESET_MASK = 0x7777777777777777L
        const val MAX_TABLE_SIZE = 1L shl 30

        // Well known mixing constants. Any large odd numbers would do.
        val SEED = longArrayOf(
            0xc3a5c85c97cb3127uL.toLong(),
            0xb492b66fbe98f273uL.toLong(),
            0x9ae16a3b2f90404fuL.toLong(),
            0xcbf29ce484222325uL.toLong(),
        )
    }
}
