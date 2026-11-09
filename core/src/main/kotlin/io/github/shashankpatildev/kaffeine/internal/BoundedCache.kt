package io.github.shashankpatildev.kaffeine.internal

import io.github.shashankpatildev.kaffeine.Cache
import io.github.shashankpatildev.kaffeine.CacheStats
import io.github.shashankpatildev.kaffeine.KaffeineConfig
import io.github.shashankpatildev.kaffeine.internal.Node.State
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.LongAdder
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The cache itself.
 *
 * The data lives in a ConcurrentHashMap, so reads never wait for anyone. The eviction policy
 * (its lists and the frequency sketch) is not thread safe, so get and put never touch it
 * directly. Instead:
 *
 * - A read drops a note into [readBuffer]. This never blocks, and the note may get lost.
 * - A write queues a task in [writeBuffer]. Tasks are never lost.
 * - Then one thread at a time takes [maintenanceLock] and applies all notes and tasks to the
 *   policy. If another thread already holds the lock, we just move on, because that thread
 *   will run our task too.
 *
 * The map is always the truth about what is cached. The policy may run a few operations
 * behind, and that is fine.
 */
internal class BoundedCache<K : Any, V : Any>(config: KaffeineConfig) : Cache<K, V> {

    private val data = ConcurrentHashMap<K, Node<K, V>>()
    private val readBuffer = ReadBuffer<Node<K, V>>()
    private val writeBuffer = ConcurrentLinkedQueue<() -> Unit>()
    private val maintenanceLock = ReentrantLock()
    private val policy = WTinyLfuPolicy(
        maximumSize = config.maximumSize,
        sketch = FrequencySketch(config.maximumSize, config.doorkeeper),
        evict = ::evict,
    )
    private val stats = if (config.recordStats) StatsCounter() else null

    /** How many entries the policy tracks. Tests compare it with the map size after cleanUp(). */
    internal val policySize: Int get() = maintenanceLock.withLock { policy.size }

    override fun getIfPresent(key: K): V? {
        val node = data[key]
        if (node == null) {
            stats?.misses?.increment()
            return null
        }
        if (readBuffer.offer(node) == ReadBuffer.Result.FULL) tryMaintenance()
        stats?.hits?.increment()
        return node.value
    }

    override fun put(key: K, value: V) {
        var added: Node<K, V>? = null
        val node = data.compute(key) { _, existing ->
            existing?.also { it.value = value } ?: Node(key, value).also { added = it }
        }!!

        // If the node was removed again before its task runs, the policy never needs to see it.
        if (node === added) {
            afterWrite { if (node.state == State.ALIVE) policy.onAdd(node) }
        } else {
            afterWrite { if (node.state == State.ALIVE) policy.onAccess(node) }
        }
    }

    override fun invalidate(key: K) {
        val node = data.remove(key) ?: return
        node.state = State.RETIRED
        afterWrite {
            policy.onRemove(node)
            node.state = State.DEAD
        }
    }

    override fun invalidateAll() {
        for (key in data.keys) invalidate(key)
    }

    override fun estimatedSize(): Long = data.mappingCount()

    override fun cleanUp() {
        maintenanceLock.withLock { maintenance() }
    }

    override fun stats(): CacheStats = stats?.snapshot() ?: CacheStats(0, 0, 0)

    private fun afterWrite(task: () -> Unit) {
        writeBuffer.add(task)
        tryMaintenance()
    }

    /**
     * Runs maintenance, unless another thread is already doing it.
     *
     * The loop closes a small gap. A writer can add its task right after the lock holder
     * finished draining, but before it let go of the lock. That writer's tryLock fails, so it
     * leaves. By checking the queue again after unlocking, the lock holder runs that task
     * instead of leaving it stuck until the next write.
     */
    private fun tryMaintenance() {
        do {
            if (!maintenanceLock.tryLock()) return
            try {
                maintenance()
            } finally {
                maintenanceLock.unlock()
            }
        } while (writeBuffer.isNotEmpty())
    }

    /** Applies everything buffered to the policy. Only runs while holding [maintenanceLock]. */
    private fun maintenance() {
        readBuffer.drainTo { node -> if (node.state == State.ALIVE) policy.onAccess(node) }
        while (true) {
            val task = writeBuffer.poll() ?: break
            task()
        }
    }

    /** Called by the policy, under the lock, for every entry it throws out. */
    private fun evict(node: Node<K, V>) {
        // remove(key, node) only removes this exact node. If the user already removed the key, nothing happens.
        if (data.remove(node.key, node)) stats?.evictions?.increment()
        node.state = State.DEAD
    }

    /** LongAdder instead of AtomicLong, so threads bumping a counter do not fight over it. */
    private class StatsCounter {
        val hits = LongAdder()
        val misses = LongAdder()
        val evictions = LongAdder()

        fun snapshot() = CacheStats(hits.sum(), misses.sum(), evictions.sum())
    }
}
