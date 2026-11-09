package io.github.shashankpatildev.kaffeine.bench

/**
 * Belady's optimal policy: on a miss with a full cache, evict the key whose next request
 * is furthest in the future.
 *
 * No real cache can do this, because it needs to know the future. We use it as the upper
 * bound: it shows the best hit ratio any cache of this size could reach on the trace.
 */
fun simulateBelady(trace: LongArray, cacheSize: Int): Long {
    val n = trace.size
    val nextUse = nextUsePositions(trace)

    val cached = LongIntMap()  // key -> position of its next request, or -1 if not cached
    val heap = IntMaxHeap()    // next request positions of cached keys; old entries are skipped lazily
    var cachedCount = 0
    var hits = 0L

    for (i in 0 until n) {
        val key = trace[i]
        if (cached.get(key, -1) >= 0) {
            hits++
        } else if (cachedCount == cacheSize) {
            evictFurthest(trace, cached, heap)
        } else {
            cachedCount++
        }
        cached.put(key, nextUse[i])
        heap.add(nextUse[i])
    }
    return hits
}

/**
 * For each request, the position where the same key is requested again. A key that is
 * never requested again gets `n + i`: past the end of the trace, but still unique, so two
 * keys never tie. We can always get the key back from a position with [keyAt].
 */
private fun nextUsePositions(trace: LongArray): IntArray {
    val n = trace.size
    val nextUse = IntArray(n)
    val seenAt = LongIntMap()
    for (i in n - 1 downTo 0) {
        nextUse[i] = seenAt.get(trace[i], n + i)
        seenAt.put(trace[i], i)
    }
    return nextUse
}

private fun evictFurthest(trace: LongArray, cached: LongIntMap, heap: IntMaxHeap) {
    while (true) {
        val position = heap.poll()
        val victim = keyAt(trace, position)
        // The heap entry is still valid only if the key is cached and still waits for this position.
        if (cached.get(victim, -1) == position) {
            cached.put(victim, -1)
            return
        }
    }
}

private fun keyAt(trace: LongArray, position: Int): Long =
    if (position >= trace.size) trace[position - trace.size] else trace[position]

/** Open addressing map from Long to Int. A HashMap<Long, Int> would box millions of entries. */
private class LongIntMap {
    private var keys = LongArray(1 shl 16)
    private var values = IntArray(1 shl 16)
    private var used = BooleanArray(1 shl 16)
    private var size = 0

    fun get(key: Long, default: Int): Int {
        var i = slot(key, keys.size)
        while (used[i]) {
            if (keys[i] == key) return values[i]
            i = (i + 1) and (keys.size - 1)
        }
        return default
    }

    fun put(key: Long, value: Int) {
        var i = slot(key, keys.size)
        while (used[i] && keys[i] != key) i = (i + 1) and (keys.size - 1)
        if (!used[i]) {
            used[i] = true
            keys[i] = key
            size++
        }
        values[i] = value
        if (size * 2 > keys.size) grow() // stay at most half full, so probing stays short
    }

    private fun grow() {
        val oldKeys = keys
        val oldValues = values
        val oldUsed = used
        keys = LongArray(oldKeys.size * 2)
        values = IntArray(oldKeys.size * 2)
        used = BooleanArray(oldKeys.size * 2)
        size = 0
        for (i in oldKeys.indices) if (oldUsed[i]) put(oldKeys[i], oldValues[i])
    }

    private fun slot(key: Long, capacity: Int): Int {
        val h = key * -0x61c8864680b583ebL // golden ratio constant, spreads nearby keys apart
        return (h xor (h ushr 32)).toInt() and (capacity - 1)
    }
}

/** A binary max-heap of ints. */
private class IntMaxHeap {
    private var items = IntArray(1 shl 16)
    private var size = 0

    fun add(value: Int) {
        if (size == items.size) items = items.copyOf(size * 2)
        var i = size++
        while (i > 0) {
            val parent = (i - 1) / 2
            if (items[parent] >= value) break
            items[i] = items[parent]
            i = parent
        }
        items[i] = value
    }

    fun poll(): Int {
        val top = items[0]
        val last = items[--size]
        var i = 0
        while (true) {
            var child = 2 * i + 1
            if (child >= size) break
            if (child + 1 < size && items[child + 1] > items[child]) child++
            if (items[child] <= last) break
            items[i] = items[child]
            i = child
        }
        items[i] = last
        return top
    }
}
