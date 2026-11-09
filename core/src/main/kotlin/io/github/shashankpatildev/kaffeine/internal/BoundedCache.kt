package io.github.shashankpatildev.kaffeine.internal

import io.github.shashankpatildev.kaffeine.Cache
import io.github.shashankpatildev.kaffeine.CacheStats
import io.github.shashankpatildev.kaffeine.KaffeineConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The cache itself.
 *
 * The data lives in a ConcurrentHashMap. The eviction policy (its lists and the frequency
 * sketch) is not thread safe, so every change to the policy happens while holding [policyLock].
 *
 * This is the simple first version: one lock, taken on every read and every write.
 * It is easy to trust, and it gives us a baseline to measure the faster version against.
 * In that version, reads will drop a note in a buffer instead of taking the lock.
 */
internal class BoundedCache<K : Any, V : Any>(config: KaffeineConfig) : Cache<K, V> {

    private val data = ConcurrentHashMap<K, Node<K, V>>()
    private val policyLock = ReentrantLock()
    private val policy = WTinyLfuPolicy(
        maximumSize = config.maximumSize,
        sketch = FrequencySketch(config.maximumSize, config.doorkeeper),
        evict = ::evict,
    )
    private val stats = if (config.recordStats) StatsCounter() else null

    override fun getIfPresent(key: K): V? {
        val node = data[key]
        if (node == null) {
            stats?.misses?.increment()
            return null
        }
        // If the node got evicted after we read it from the map, onAccess just ignores it.
        policyLock.withLock { policy.onAccess(node) }
        stats?.hits?.increment()
        return node.value
    }

    override fun put(key: K, value: V) {
        // Every change to the map happens under this lock too, so the
        // "look up, then insert" below cannot race with another writer.
        policyLock.withLock {
            val existing = data[key]
            if (existing != null) {
                existing.value = value
                policy.onAccess(existing)
            } else {
                val node = Node(key, value)
                data[key] = node
                policy.onAdd(node)
            }
        }
    }

    override fun invalidate(key: K) {
        policyLock.withLock {
            val node = data.remove(key) ?: return
            policy.onRemove(node)
        }
    }

    override fun invalidateAll() {
        policyLock.withLock {
            for (node in data.values) policy.onRemove(node)
            data.clear()
        }
    }

    override fun estimatedSize(): Long = data.mappingCount()

    override fun stats(): CacheStats = stats?.snapshot() ?: CacheStats(0, 0, 0)

    /** Called by the policy, under the lock, for every entry it throws out. */
    private fun evict(node: Node<K, V>) {
        data.remove(node.key, node)
        stats?.evictions?.increment()
    }

    /** LongAdder instead of AtomicLong, so threads bumping a counter do not fight over it. */
    private class StatsCounter {
        val hits = LongAdder()
        val misses = LongAdder()
        val evictions = LongAdder()

        fun snapshot() = CacheStats(hits.sum(), misses.sum(), evictions.sum())
    }
}
