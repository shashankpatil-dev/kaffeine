package io.github.shashankpatildev.kaffeine.internal

/**
 * A Bloom filter answers "have I seen this key before?" using only a few bits per key.
 *
 * It may say yes for a key it never saw (a false positive), but it never says no for a
 * key it did see. We use it as the "doorkeeper" in front of [FrequencySketch]: a key has
 * to knock once before the sketch starts counting it.
 *
 * Not thread safe. The caller must hold the policy lock.
 *
 * @param expectedInsertions how many keys we expect to add between two calls to [clear]
 */
internal class BloomFilter(expectedInsertions: Long) {

    // About 8 bits per key with 4 hash functions gives roughly 2% false positives.
    private val bits = LongArray(
        ceilingPowerOfTwo(expectedInsertions * BITS_PER_KEY / Long.SIZE_BITS)
            .coerceAtMost(MAX_LONGS)
            .toInt()
    )
    private val bitMask = bits.size.toLong() * Long.SIZE_BITS - 1

    /** Adds the key. Returns true if the key looked new, meaning at least one of its bits was still 0. */
    fun put(hash: Int): Boolean {
        var added = false
        forEachBit(hash) { index, mask ->
            if (bits[index] and mask == 0L) {
                bits[index] = bits[index] or mask
                added = true
            }
        }
        return added
    }

    /** True if the key was probably added. False means it was definitely not added. */
    fun mightContain(hash: Int): Boolean {
        var allSet = true
        forEachBit(hash) { index, mask ->
            if (bits[index] and mask == 0L) allSet = false
        }
        return allSet
    }

    fun clear() {
        bits.fill(0L)
    }

    /**
     * Calls [action] for each of the key's bits, with the Long that holds the bit and a
     * mask for it. The positions come from double hashing: `h1 + i * h2` gives as many
     * positions as we like from just two hashes.
     */
    private inline fun forEachBit(hash: Int, action: (index: Int, mask: Long) -> Unit) {
        val h1 = hash.toLong()
        val h2 = spread(hash).toLong() or 1L // odd, so the steps never get stuck in a small cycle
        for (i in 0 until HASHES) {
            val bit = (h1 + i * h2) and bitMask
            // "1L shl bit" only looks at the low 6 bits of the shift, which is the bit inside the Long.
            action((bit ushr 6).toInt(), 1L shl bit.toInt())
        }
    }

    private companion object {
        const val HASHES = 4
        const val BITS_PER_KEY = 8
        const val MAX_LONGS = 1L shl 24 // 128 MB. Plenty for any cache that fits in memory.
    }
}
