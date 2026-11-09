package io.github.shashankpatildev.kaffeine.internal

/**
 * One entry in the cache.
 *
 * The [prev] and [next] links let a node sit inside an [AccessOrderDeque] without any
 * wrapper object around it, and [queue] tells us which deque that is.
 *
 * Only [value] may be read without the policy lock. Everything else belongs to the
 * eviction policy and is touched only while holding that lock.
 */
internal class Node<K : Any, V : Any>(
    val key: K,
    @Volatile var value: V,
) {
    /** Mixed hash of the key. Worked out once, because the sketch needs it on every access. */
    val hash: Int = spread(key.hashCode())

    var queue: Queue = Queue.NONE
    var prev: Node<K, V>? = null
    var next: Node<K, V>? = null

    /** The policy region a node lives in. NONE means the policy does not know about it (yet, or any more). */
    enum class Queue { NONE, WINDOW, PROBATION, PROTECTED }

    override fun toString(): String = "Node($key, $queue)"
}
