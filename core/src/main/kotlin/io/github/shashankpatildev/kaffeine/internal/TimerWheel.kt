package io.github.shashankpatildev.kaffeine.internal

/**
 * A hierarchical timing wheel. It finds expired entries in O(1), even when every entry has
 * its own expiry time.
 *
 * Picture five clock faces, from fast to slow:
 *
 * ```
 * level  buckets  each bucket spans   holds entries due within
 *   0       64      ~1.07 seconds       ~1.1 minutes
 *   1       64      ~1.14 minutes       ~1.2 hours
 *   2       32      ~1.22 hours         ~19.5 hours
 *   3        4      ~19.5 hours         ~3.3 days
 *   4        1      (overflow)          anything later
 * ```
 *
 * An entry goes into the finest level that can still hold it. As time moves on, every bucket
 * we pass is emptied: entries that are due go to `onExpired`, and the rest are scheduled again,
 * which drops them into a finer level. That is called cascading. An entry moves at most a few
 * times in its life, so the cost per entry stays constant.
 *
 * Bucket spans are powers of two in nanoseconds, so finding a bucket is a bit shift and a mask
 * instead of a division. Each bucket is a doubly linked list through the node's wheel links,
 * so taking a node out is O(1) too.
 *
 * Not thread safe. The caller must hold the maintenance lock.
 */
internal class TimerWheel<K : Any, V : Any> {

    /** buckets[level][index] is the first node of that bucket's list, or null. */
    private val buckets: Array<Array<Node<K, V>?>> = Array(BUCKETS.size) { arrayOfNulls(BUCKETS[it]) }

    /** The time of the last [advance]. */
    private var nanos = 0L

    /** Puts the node in the bucket for its expiry time. A node already in the wheel is moved. */
    fun schedule(node: Node<K, V>) {
        deschedule(node)
        val delay = maxOf(0L, node.expiresAt - nanos)
        var level = 0
        while (level < OVERFLOW && delay >= 1L shl SHIFT[level + 1]) level++
        val index = ((node.expiresAt ushr SHIFT[level]) and (BUCKETS[level] - 1L)).toInt()
        link(node, level, index)
    }

    /** Takes the node out of the wheel, if it is in it. */
    fun deschedule(node: Node<K, V>) {
        if (node.wheelLevel < 0) return
        val before = node.prevInWheel
        val after = node.nextInWheel
        if (before == null) buckets[node.wheelLevel][node.wheelIndex] = after else before.nextInWheel = after
        after?.prevInWheel = before
        node.prevInWheel = null
        node.nextInWheel = null
        node.wheelLevel = -1
    }

    /**
     * Moves the clock to [now]. Every node that is due by then goes to [onExpired]. Nodes the
     * clock passed but that are not due yet are scheduled again in a finer level.
     */
    fun advance(now: Long, onExpired: (Node<K, V>) -> Unit) {
        val previous = nanos
        nanos = now
        for (level in SHIFT.indices) {
            val previousTicks = previous ushr SHIFT[level]
            val elapsedTicks = (now ushr SHIFT[level]) - previousTicks
            if (elapsedTicks <= 0) break // this level did not tick, so the slower ones did not either

            // Also look at the bucket we started in: it can hold nodes due between previous and now.
            val steps = minOf(elapsedTicks + 1, BUCKETS[level].toLong()).toInt()
            for (step in 0 until steps) {
                val index = ((previousTicks + step) and (BUCKETS[level] - 1L)).toInt()
                expireBucket(level, index, now, onExpired)
            }
        }
    }

    private fun expireBucket(level: Int, index: Int, now: Long, onExpired: (Node<K, V>) -> Unit) {
        // Take the whole list out first, so nodes scheduled back into this bucket are not seen twice.
        var node = buckets[level][index]
        buckets[level][index] = null
        while (node != null) {
            val next = node.nextInWheel
            node.prevInWheel = null
            node.nextInWheel = null
            node.wheelLevel = -1
            if (node.isExpired(now)) onExpired(node) else schedule(node)
            node = next
        }
    }

    private fun link(node: Node<K, V>, level: Int, index: Int) {
        val head = buckets[level][index]
        node.nextInWheel = head
        head?.prevInWheel = node
        buckets[level][index] = node
        node.wheelLevel = level
        node.wheelIndex = index
    }

    private companion object {
        /** Bucket span of each level as a power of two in nanoseconds. 2^30 ns is about 1.07 seconds. */
        val SHIFT = intArrayOf(30, 36, 42, 46, 48)
        val BUCKETS = intArrayOf(64, 64, 32, 4, 1)
        const val OVERFLOW = 4
    }
}
