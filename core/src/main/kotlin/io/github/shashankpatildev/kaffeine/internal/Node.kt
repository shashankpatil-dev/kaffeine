package io.github.shashankpatildev.kaffeine.internal

/**
 * One entry in the cache.
 *
 * The [prev] and [next] links let a node sit inside an [AccessOrderDeque] without any
 * wrapper object around it, and [queue] tells us which deque that is. The wheel links do
 * the same for the [TimerWheel] bucket the node waits in.
 *
 * [value], [expiresAt] and [state] may be touched by any thread. Everything else belongs to
 * the policy and the wheel, and is touched only while holding the maintenance lock.
 */
internal class Node<K : Any, V : Any>(
    val key: K,
    @Volatile var value: V,
    /** When the entry expires, in nanoseconds on the cache's own clock. [NEVER] if it does not. */
    @Volatile var expiresAt: Long = NEVER,
) {
    /** Mixed hash of the key. Worked out once, because the sketch needs it on every access. */
    val hash: Int = spread(key.hashCode())

    @Volatile
    var state: State = State.ALIVE

    var queue: Queue = Queue.NONE
    var prev: Node<K, V>? = null
    var next: Node<K, V>? = null

    var prevInWheel: Node<K, V>? = null
    var nextInWheel: Node<K, V>? = null
    var wheelLevel: Int = -1 // -1 means the node is not in the wheel
    var wheelIndex: Int = 0

    fun isExpired(now: Long): Boolean = now >= expiresAt

    /**
     * Where the node is in its life. Policy updates run a little later than the map changes,
     * and not always in order, so a task checks the state before it touches the policy.
     */
    enum class State {
        /** In the map. */
        ALIVE,

        /** Removed from the map by the user. The policy has not heard about it yet. */
        RETIRED,

        /** Gone from both the map and the policy. */
        DEAD,
    }

    /** The policy region a node lives in. NONE means the policy does not know about it (yet, or any more). */
    enum class Queue { NONE, WINDOW, PROBATION, PROTECTED }

    override fun toString(): String = "Node($key, $state, $queue)"

    companion object {
        const val NEVER = Long.MAX_VALUE
    }
}
