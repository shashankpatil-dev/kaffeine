package io.github.shashankpatildev.kaffeine.internal

import io.github.shashankpatildev.kaffeine.internal.Node.Queue
import kotlin.random.Random

/**
 * W-TinyLFU decides which entry leaves when the cache is full.
 *
 * The cache is split into three LRU lists:
 *
 * ```
 * | Window 1% | Probation ~20% of main | Protected ~80% of main |
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
 * Not thread safe. The caller must hold the policy lock.
 *
 * @param evict called for each entry the policy throws out, so the cache can drop it from its map
 */
internal class WTinyLfuPolicy<K : Any, V : Any>(
    maximumSize: Long,
    private val sketch: FrequencySketch,
    private val random: Random = Random.Default,
    private val evict: (Node<K, V>) -> Unit,
) {
    private val windowMax = maxOf(1, maximumSize / 100)
    private val mainMax = maximumSize - windowMax
    private val protectedMax = mainMax * 80 / 100

    private val windowDeque = AccessOrderDeque<K, V>()
    private val probationDeque = AccessOrderDeque<K, V>()
    private val protectedDeque = AccessOrderDeque<K, V>()

    /** How many entries the policy is tracking right now. */
    val size: Int get() = windowDeque.size + probationDeque.size + protectedDeque.size

    /** A new entry was added to the cache. This may evict other entries. */
    fun onAdd(node: Node<K, V>) {
        sketch.increment(node.hash)
        node.queue = Queue.WINDOW
        windowDeque.addLast(node)
        evictIfNeeded()
    }

    /** An existing entry was read or updated. */
    fun onAccess(node: Node<K, V>) {
        sketch.increment(node.hash)
        when (node.queue) {
            Queue.WINDOW -> windowDeque.moveToLast(node)
            Queue.PROBATION -> promote(node)
            Queue.PROTECTED -> protectedDeque.moveToLast(node)
            Queue.NONE -> Unit // already evicted, nothing to do
        }
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

        if (protectedDeque.size > protectedMax) {
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

    private fun dequeOf(queue: Queue): AccessOrderDeque<K, V>? = when (queue) {
        Queue.WINDOW -> windowDeque
        Queue.PROBATION -> probationDeque
        Queue.PROTECTED -> protectedDeque
        Queue.NONE -> null
    }

    private companion object {
        const val WARM_CANDIDATE = 6
    }
}
