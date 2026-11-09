package io.github.shashankpatildev.kaffeine.internal

/**
 * A doubly linked list of nodes. The oldest node is at the head, the newest at the tail.
 *
 * The links are stored inside [Node] itself (an "intrusive" list). So when we already hold
 * a node, removing or moving it is O(1) and creates no garbage. A node can only be in one
 * deque at a time, which is fine because a cache entry lives in exactly one policy region.
 *
 * Not thread safe. The caller must hold the policy lock.
 */
internal class AccessOrderDeque<K : Any, V : Any> : Iterable<Node<K, V>> {

    private var head: Node<K, V>? = null
    private var tail: Node<K, V>? = null

    var size: Int = 0
        private set

    fun isEmpty(): Boolean = head == null

    /** The oldest node, or null if the deque is empty. */
    fun peekFirst(): Node<K, V>? = head

    /** Removes and returns the oldest node, or null if the deque is empty. */
    fun pollFirst(): Node<K, V>? = head?.also { remove(it) }

    fun addLast(node: Node<K, V>) {
        val last = tail
        node.prev = last
        node.next = null
        if (last == null) head = node else last.next = node
        tail = node
        size++
    }

    /** Unlinks [node]. The node must be in this deque. */
    fun remove(node: Node<K, V>) {
        val before = node.prev
        val after = node.next
        if (before == null) head = after else before.next = after
        if (after == null) tail = before else after.prev = before
        node.prev = null
        node.next = null
        size--
    }

    /** Marks [node] as the most recently used one. */
    fun moveToLast(node: Node<K, V>) {
        if (node === tail) return
        remove(node)
        addLast(node)
    }

    override fun iterator(): Iterator<Node<K, V>> = generateSequence(head) { it.next }.iterator()
}
