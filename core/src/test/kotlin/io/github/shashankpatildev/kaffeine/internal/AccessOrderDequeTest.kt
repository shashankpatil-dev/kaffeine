package io.github.shashankpatildev.kaffeine.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccessOrderDequeTest {

    private val deque = AccessOrderDeque<String, Int>()
    private val a = Node("a", 1)
    private val b = Node("b", 2)
    private val c = Node("c", 3)

    private fun keys() = deque.map { it.key }

    @Test
    fun `starts empty`() {
        assertTrue(deque.isEmpty())
        assertEquals(0, deque.size)
        assertNull(deque.peekFirst())
        assertNull(deque.pollFirst())
    }

    @Test
    fun `keeps insertion order`() {
        deque.addLast(a)
        deque.addLast(b)
        deque.addLast(c)

        assertEquals(listOf("a", "b", "c"), keys())
        assertEquals(3, deque.size)
        assertEquals(a, deque.peekFirst())
    }

    @Test
    fun `removes from head, middle and tail`() {
        listOf(a, b, c).forEach(deque::addLast)

        deque.remove(b)
        assertEquals(listOf("a", "c"), keys())

        deque.remove(a)
        assertEquals(listOf("c"), keys())

        deque.remove(c)
        assertTrue(deque.isEmpty())
    }

    @Test
    fun `clears the links of a removed node`() {
        listOf(a, b, c).forEach(deque::addLast)

        deque.remove(b)

        assertNull(b.prev)
        assertNull(b.next)
    }

    @Test
    fun `moves a node to the tail`() {
        listOf(a, b, c).forEach(deque::addLast)

        deque.moveToLast(a)
        assertEquals(listOf("b", "c", "a"), keys())

        deque.moveToLast(a) // already last, nothing changes
        assertEquals(listOf("b", "c", "a"), keys())
    }

    @Test
    fun `polls the oldest node first`() {
        listOf(a, b, c).forEach(deque::addLast)

        assertEquals(a, deque.pollFirst())
        assertEquals(b, deque.pollFirst())
        assertEquals(listOf("c"), keys())
    }
}
