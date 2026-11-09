package io.github.shashankpatildev.kaffeine.internal

import io.github.shashankpatildev.kaffeine.internal.Node.Queue
import kotlin.math.pow
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WTinyLfuPolicyTest {

    private val evicted = mutableListOf<Node<Int, Int>>()

    private fun policy(maximumSize: Long) = WTinyLfuPolicy(
        maximumSize = maximumSize,
        sketch = FrequencySketch(maximumSize, useDoorkeeper = true),
        random = Random(7), // fixed seed, so the tests always behave the same
        evict = { evicted += it },
    )

    @Test
    fun `never tracks more than maximum size`() {
        val policy = policy(maximumSize = 100)

        repeat(1_000) { policy.onAdd(Node(it, it)) }

        assertEquals(100, policy.size)
        assertEquals(900, evicted.size)
    }

    @Test
    fun `new entries start in the window`() {
        val policy = policy(maximumSize = 100)
        val node = Node(1, 1)

        policy.onAdd(node)

        assertEquals(Queue.WINDOW, node.queue)
    }

    @Test
    fun `window overflow goes to probation while the cache fills up`() {
        val policy = policy(maximumSize = 100) // window holds 1 entry
        val first = Node(1, 1)

        policy.onAdd(first)
        policy.onAdd(Node(2, 2))

        assertEquals(Queue.PROBATION, first.queue)
        assertTrue(evicted.isEmpty())
    }

    @Test
    fun `a hit on probation promotes to protected`() {
        val policy = policy(maximumSize = 100)
        val node = Node(1, 1)
        policy.onAdd(node)
        policy.onAdd(Node(2, 2)) // pushes node 1 from the window into probation

        policy.onAccess(node)

        assertEquals(Queue.PROTECTED, node.queue)
    }

    @Test
    fun `protected overflow is demoted back to probation`() {
        val policy = policy(maximumSize = 100) // window 1, main 99, protected 79
        val nodes = (0 until 100).map { Node(it, it) }
        nodes.forEach(policy::onAdd)

        // Promote 80 entries. Protected only has room for 79, so the oldest one is demoted.
        nodes.take(80).forEach(policy::onAccess)

        assertEquals(Queue.PROBATION, nodes[0].queue)
        assertEquals(Queue.PROTECTED, nodes[1].queue)
    }

    @Test
    fun `popular entries survive a one-time scan`() {
        val policy = policy(maximumSize = 100)
        val hot = (0 until 50).map { Node(it, it) }

        // Use each hot key 5 times.
        hot.forEach(policy::onAdd)
        repeat(4) { hot.forEach(policy::onAccess) }

        // Then 500 keys pass through once each. LRU would lose every hot key here.
        (1_000 until 1_500).forEach { policy.onAdd(Node(it, it)) }

        val lost = hot.filter { it.queue == Queue.NONE }
        assertTrue(lost.isEmpty(), "hot keys evicted by the scan: ${lost.map { it.key }}")
    }

    @Test
    fun `a cache of size one keeps the newest entry`() {
        val policy = policy(maximumSize = 1)
        val first = Node(1, 1)
        val second = Node(2, 2)

        policy.onAdd(first)
        policy.onAdd(second)

        assertEquals(1, policy.size)
        assertEquals(listOf(first), evicted)
        assertEquals(Queue.WINDOW, second.queue)
    }

    @Test
    fun `the window grows when recently used keys keep coming back`() {
        val policy = policy(maximumSize = 100)
        val nodes = HashMap<Int, Node<Int, Int>>()
        val random = Random(5)

        // A working set of 60 keys that slowly slides forward: each key is busy for a while,
        // then never seen again. Plain LRU does well here, so a bigger window should win.
        repeat(60_000) { t -> access(policy, nodes, key = t / 20 + random.nextInt(60)) }

        assertTrue(policy.windowCapacity > 1, "the window stayed at ${policy.windowCapacity}")
    }

    @Test
    fun `resizing the window keeps every entry and stays within maximum size`() {
        val policy = policy(maximumSize = 100)
        val nodes = HashMap<Int, Node<Int, Int>>()
        val random = Random(9)
        val windowSizesSeen = mutableSetOf<Long>()

        repeat(100_000) { t ->
            // Switch between frequency-friendly and recency-friendly traffic, so the window moves both ways.
            val key = if ((t / 10_000) % 2 == 0) (random.nextDouble().pow(3) * 1_000).toInt() else t / 20 + random.nextInt(60)
            access(policy, nodes, key)
            windowSizesSeen += policy.windowCapacity

            if (t % 1_000 == 0) {
                val tracked = nodes.values.filter { it.queue != Queue.NONE }
                assertEquals(policy.size, tracked.size, "the policy lost track of an entry")
                assertTrue(policy.size <= 100)
                assertTrue(tracked.count { it.queue == Queue.WINDOW } <= policy.windowCapacity)
            }
        }
        assertTrue(windowSizesSeen.size > 2, "the window never moved: $windowSizesSeen")
    }

    /** One request, like the cache does it: a hit if the policy still holds the key, otherwise add it. */
    private fun access(policy: WTinyLfuPolicy<Int, Int>, nodes: MutableMap<Int, Node<Int, Int>>, key: Int) {
        val node = nodes[key]
        if (node != null && node.queue != Queue.NONE) {
            policy.onAccess(node)
        } else {
            policy.onAdd(Node(key, key).also { nodes[key] = it })
        }
    }

    @Test
    fun `removing an entry unlinks it`() {
        val policy = policy(maximumSize = 100)
        val node = Node(1, 1)
        policy.onAdd(node)

        policy.onRemove(node)

        assertEquals(Queue.NONE, node.queue)
        assertEquals(0, policy.size)
    }
}
