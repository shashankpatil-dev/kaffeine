package io.github.shashankpatildev.kaffeine.internal

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class TimerWheelTest {

    private val wheel = TimerWheel<Int, Int>()
    private val expired = mutableListOf<Node<Int, Int>>()

    private fun node(key: Int, expiresAt: Duration) = Node(key, key, expiresAt.inWholeNanoseconds)

    private fun advanceTo(time: Duration) = wheel.advance(time.inWholeNanoseconds) { expired += it }

    @Test
    fun `expires a node once its time has passed`() {
        val node = node(1, 5.seconds)
        wheel.schedule(node)

        advanceTo(4.seconds)
        assertTrue(expired.isEmpty())

        advanceTo(7.seconds)
        assertEquals(listOf(node), expired)
    }

    @Test
    fun `cascades down from the slower levels`() {
        val node = node(1, 2.hours)
        wheel.schedule(node)

        advanceTo(1.hours)
        advanceTo(119.minutes)
        assertTrue(expired.isEmpty())

        advanceTo(2.hours + 2.seconds)
        assertEquals(listOf(node), expired)
    }

    @Test
    fun `holds nodes that are days away in the overflow bucket`() {
        val node = node(1, 10.days)
        wheel.schedule(node)

        advanceTo(9.days)
        assertTrue(expired.isEmpty())

        advanceTo(10.days + 1.minutes)
        assertEquals(listOf(node), expired)
    }

    @Test
    fun `a descheduled node never expires`() {
        val node = node(1, 5.seconds)
        wheel.schedule(node)

        wheel.deschedule(node)
        advanceTo(1.hours)

        assertTrue(expired.isEmpty())
    }

    @Test
    fun `scheduling again moves the node`() {
        val node = node(1, 5.seconds)
        wheel.schedule(node)

        node.expiresAt = 1.minutes.inWholeNanoseconds
        wheel.schedule(node)

        advanceTo(10.seconds)
        assertTrue(expired.isEmpty())
        advanceTo(1.minutes + 2.seconds)
        assertEquals(listOf(node), expired)
    }

    @Test
    fun `random expiry times all fire on time, whatever steps the clock takes`() {
        val random = Random(11)
        val nodes = List(2_000) { Node(it, it, expiresAt = random.nextLong(3.days.inWholeNanoseconds)) }
        nodes.forEach(wheel::schedule)
        val firedAt = HashMap<Node<Int, Int>, Long>()

        var now = 0L
        while (now < 4.days.inWholeNanoseconds) {
            now += random.nextLong(1, 30.minutes.inWholeNanoseconds)
            wheel.advance(now) { node ->
                assertTrue(node.expiresAt <= now, "node ${node.key} expired early")
                assertTrue(firedAt.put(node, now) == null, "node ${node.key} expired twice")
            }
            // Nothing may be left waiting more than one tick (about a second) past its time.
            val late = nodes.filter { it !in firedAt && it.expiresAt <= now - ONE_TICK }
            assertTrue(late.isEmpty(), "nodes still waiting after their time: ${late.map { it.key }}")
        }
        assertEquals(nodes.size, firedAt.size)
    }

    private companion object {
        const val ONE_TICK = 1L shl 30
    }
}
