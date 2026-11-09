package io.github.shashankpatildev.kaffeine.internal

import io.github.shashankpatildev.kaffeine.internal.Node.Queue
import kotlin.math.abs
import kotlin.math.sign
import kotlin.random.Random

/**
 * W-TinyLFU decides which entry leaves when the cache is full.
 *
 * The cache is split into three LRU lists:
 *
 * ```
 * | Window | Probation ~20% of main | Protected ~80% of main |
 * ```
 *
 * - New entries go into the small Window first, so a brand new key gets a little time
 *   to prove itself.
 * - When the Window is too big, its oldest entry (the candidate) tries to get into the
 *   main region. It is compared with the oldest entry of Probation (the victim) using
 *   the frequency sketch. The more popular one stays and the other is evicted.
 * - An entry that is used again while in Probation moves up to Protected. When Protected
 *   is too big, its oldest entry is moved back down to Probation.
 *
 * A one-time scan cannot push out popular entries this way, because the scanned keys
 * have a frequency of 1 and lose every comparison.
 *
 * The Window starts at 1% of the cache, and a hill climber resizes it while the cache runs
 * (see [climbIfDue]). Traffic where recently used keys come back soon does better with a big
 * Window, which behaves like plain LRU. Traffic where popular keys matter more does better
 * with a small one.
 *
 * Not thread safe. The caller must hold the policy lock.
 *
 * @param evict called for each entry the policy throws out, so the cache can drop it from its map
 */
internal class WTinyLfuPolicy<K : Any, V : Any>(
    private val maximumSize: Long,
    private val sketch: FrequencySketch,
    private val random: Random = Random.Default,
    private val evict: (Node<K, V>) -> Unit,
) {
    private var windowMax = maxOf(1, maximumSize / 100)
    private var mainMax = maximumSize - windowMax
    private var protectedMax = mainMax * 80 / 100

    private val windowDeque = AccessOrderDeque<K, V>()
    private val probationDeque = AccessOrderDeque<K, V>()
    private val protectedDeque = AccessOrderDeque<K, V>()

    // Hill climber. stepSize carries a sign: plus grows the Window, minus shrinks it.
    private var hitsInSample = 0L
    private var missesInSample = 0L
    private var previousHitRate = 0.0
    // The first step shrinks. The window starts at its floor, so nothing moves until the hit rate
    // drops and the climber turns around. Starting with a grow step cost up to 2 points on
    // traces that want a small window, and caches with few samples never recovered from it.
    private var stepSize = -STEP_PERCENT * maximumSize

    /** How many entries the policy is tracking right now. */
    val size: Int get() = windowDeque.size + probationDeque.size + protectedDeque.size

    /** How many entries the Window may hold right now. */
    val windowCapacity: Long get() = windowMax

    /** A new entry was added to the cache, which means a lookup missed. This may evict other entries. */
    fun onAdd(node: Node<K, V>) {
        missesInSample++
        sketch.increment(node.hash)
        node.queue = Queue.WINDOW
        windowDeque.addLast(node)
        evictIfNeeded()
        climbIfDue()
    }

    /** An existing entry was read or updated, which means a lookup hit. */
    fun onAccess(node: Node<K, V>) {
        hitsInSample++
        sketch.increment(node.hash)
        when (node.queue) {
            Queue.WINDOW -> windowDeque.moveToLast(node)
            Queue.PROBATION -> promote(node)
            Queue.PROTECTED -> protectedDeque.moveToLast(node)
            Queue.NONE -> Unit // already evicted, nothing to do
        }
        climbIfDue()
    }

    /** An entry was removed from the cache by the user. */
    fun onRemove(node: Node<K, V>) {
        dequeOf(node.queue)?.remove(node)
        node.queue = Queue.NONE
    }

    /** Used again while on probation, so it has earned a place in Protected. */
    private fun promote(node: Node<K, V>) {
        probationDeque.remove(node)
        node.queue = Queue.PROTECTED
        protectedDeque.addLast(node)
        demoteProtectedOverflow()
    }

    private fun demoteProtectedOverflow() {
        while (protectedDeque.size > protectedMax) {
            val demoted = protectedDeque.pollFirst()!!
            demoted.queue = Queue.PROBATION
            probationDeque.addLast(demoted)
        }
    }

    private fun evictIfNeeded() {
        while (windowDeque.size > windowMax) {
            val candidate = windowDeque.pollFirst()!!

            // While the cache is still filling up, every candidate gets in for free.
            if (probationDeque.size + protectedDeque.size < mainMax) {
                candidate.queue = Queue.PROBATION
                probationDeque.addLast(candidate)
                continue
            }

            val victim = probationDeque.peekFirst() ?: protectedDeque.peekFirst()
            if (victim != null && admit(candidate, victim)) {
                onRemove(victim)
                evict(victim)
                candidate.queue = Queue.PROBATION
                probationDeque.addLast(candidate)
            } else {
                candidate.queue = Queue.NONE
                evict(candidate)
            }
        }
    }

    /** Should the candidate replace the victim? */
    private fun admit(candidate: Node<K, V>, victim: Node<K, V>): Boolean {
        val candidateFrequency = sketch.frequency(candidate.hash)
        val victimFrequency = sketch.frequency(victim.hash)
        if (candidateFrequency > victimFrequency) return true

        // An attacker could keep one victim artificially popular so nothing new ever gets in.
        // Giving a warm candidate a small random chance breaks that.
        return candidateFrequency >= WARM_CANDIDATE && random.nextInt(128) == 0
    }

    /**
     * Every `10 x maximumSize` requests, compares the hit rate with the previous period and
     * resizes the Window:
     *
     * - If the hit rate went up (or stayed), the last move helped, so move the same way again.
     * - If it went down, the last move hurt, so turn around.
     * - Each step is a little smaller than the one before, so the size settles. A big jump in
     *   the hit rate means the traffic changed, so the steps start big again.
     */
    private fun climbIfDue() {
        val requests = hitsInSample + missesInSample
        if (requests < sketch.sampleSize) return

        val hitRate = hitsInSample.toDouble() / requests
        val change = hitRate - previousHitRate
        val amount = if (change >= 0) stepSize else -stepSize
        stepSize = if (abs(change) >= RESTART_THRESHOLD) {
            amount.sign * STEP_PERCENT * maximumSize
        } else {
            amount * STEP_DECAY
        }
        previousHitRate = hitRate
        hitsInSample = 0
        missesInSample = 0

        resizeWindow(amount.toLong())
    }

    /** Moves capacity between the Window and the main region. Entries change lists, nothing is evicted. */
    private fun resizeWindow(amount: Long) {
        if (maximumSize < 2 || amount == 0L) return
        val newWindowMax = (windowMax + amount).coerceIn(1, maximumSize - 1)
        if (newWindowMax == windowMax) return
        windowMax = newWindowMax
        mainMax = maximumSize - windowMax
        protectedMax = mainMax * 80 / 100

        // The Window shrank: its oldest entries move down to Probation.
        while (windowDeque.size > windowMax) {
            val node = windowDeque.pollFirst()!!
            node.queue = Queue.PROBATION
            probationDeque.addLast(node)
        }
        // The main region shrank: its oldest entries move up into the Window.
        while (probationDeque.size + protectedDeque.size > mainMax) {
            val node = probationDeque.pollFirst() ?: protectedDeque.pollFirst()!!
            node.queue = Queue.WINDOW
            windowDeque.addLast(node)
        }
        demoteProtectedOverflow()
    }

    private fun dequeOf(queue: Queue): AccessOrderDeque<K, V>? = when (queue) {
        Queue.WINDOW -> windowDeque
        Queue.PROBATION -> probationDeque
        Queue.PROTECTED -> protectedDeque
        Queue.NONE -> null
    }

    private companion object {
        const val WARM_CANDIDATE = 6

        // The same numbers Caffeine's hill climber uses.
        const val STEP_PERCENT = 0.0625 // first step: 6.25% of the cache
        const val STEP_DECAY = 0.98 // each step is 2% smaller than the last
        const val RESTART_THRESHOLD = 0.05 // a 5 point jump in hit rate restarts with big steps
    }
}
