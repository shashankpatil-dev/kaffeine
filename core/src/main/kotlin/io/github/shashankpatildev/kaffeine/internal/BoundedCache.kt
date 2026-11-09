package io.github.shashankpatildev.kaffeine.internal

import io.github.shashankpatildev.kaffeine.Cache
import io.github.shashankpatildev.kaffeine.CacheStats
import io.github.shashankpatildev.kaffeine.KaffeineConfig
import io.github.shashankpatildev.kaffeine.internal.Node.Companion.NEVER
import io.github.shashankpatildev.kaffeine.internal.Node.State
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.LongAdder
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs

/**
 * The cache itself.
 *
 * The data lives in a ConcurrentHashMap, so reads never wait for anyone. The eviction policy
 * (its lists and the frequency sketch) and the timer wheel are not thread safe, so get and put
 * never touch them directly. Instead:
 *
 * - A read drops a note into [readBuffer]. This never blocks, and the note may get lost.
 *   Replacing the value of an existing key does the same, because to the policy it is just
 *   another use of the key.
 * - Adding or removing a key queues a task in [writeBuffer]. Tasks are never lost.
 * - Then one thread at a time takes [maintenanceLock] and applies all notes and tasks to the
 *   policy. If another thread already holds the lock, we just move on, because that thread
 *   will run our task too.
 *
 * The map is always the truth about what is cached. The policy may run a few operations
 * behind, and that is fine.
 *
 * Loads started by [get] are tracked in [inFlight], one per key, so callers asking for the
 * same missing key share a single load.
 */
internal class BoundedCache<K : Any, V : Any>(config: KaffeineConfig<K, V>) : Cache<K, V> {

    private val data = ConcurrentHashMap<K, Node<K, V>>()
    private val readBuffer = ReadBuffer<Node<K, V>>()
    private val writeBuffer = ConcurrentLinkedQueue<() -> Unit>()
    private val pendingWrites = AtomicInteger()
    private val maintenanceLock = ReentrantLock()
    private val policy = WTinyLfuPolicy(
        maximumSize = config.maximumSize,
        sketch = FrequencySketch(config.maximumSize, config.doorkeeper),
        evict = ::evict,
    )
    private val stats = if (config.recordStats) StatsCounter() else null

    // Loading. Loads run in the cache's own scope, not in a caller's, so a caller that is
    // cancelled cannot cancel a load others are waiting for. SupervisorJob keeps one failed
    // load from cancelling the rest.
    private val inFlight = ConcurrentHashMap<K, CompletableDeferred<V>>()
    private val loadScope = CoroutineScope(SupervisorJob() + config.loadContext)

    // Expiry. Times are nanoseconds since this cache was created, so they start at zero and
    // only grow. That keeps the comparisons and the wheel's bit shifts simple.
    private val ticker = config.ticker
    private val origin = ticker.read()
    private val expireAfterWrite = config.expireAfterWrite?.inWholeNanoseconds
    private val expireAfterAccess = config.expireAfterAccess?.inWholeNanoseconds
    private val expireAfter = config.expireAfter
    private val wheel = if (expireAfterWrite != null || expireAfterAccess != null || expireAfter != null) {
        TimerWheel<K, V>()
    } else {
        null // no expiry configured, so we never even read the clock
    }

    /** How many entries the policy tracks. Tests compare it with the map size after cleanUp(). */
    internal val policySize: Int get() = maintenanceLock.withLock { policy.size }

    override fun getIfPresent(key: K): V? {
        val node = data[key] ?: return miss()
        if (wheel != null) {
            val now = now()
            // The wheel removes expired entries a little later, but we never hand out a stale value.
            if (node.isExpired(now)) return miss()
            if (expireAfterAccess != null) node.expiresAt = deadline(now, expireAfterAccess)
        }
        afterRead(node)
        stats?.hits?.increment()
        return node.value
    }

    override suspend fun get(key: K, loader: suspend (K) -> V): V {
        getIfPresent(key)?.let { return it }

        var started = false
        val load = inFlight.computeIfAbsent(key) {
            started = true
            CompletableDeferred() // just an empty box for now, nothing slow happens inside the map's lock
        }
        if (started) {
            // Another load may have finished between our miss above and registering this one.
            val justLoaded = freshValue(key)
            if (justLoaded != null) {
                inFlight.remove(key, load)
                load.complete(justLoaded)
            } else {
                startLoad(key, load, loader)
            }
        }
        // Everyone asking for this key waits on the same box. Waiting suspends, it does not block a thread.
        return load.await()
    }

    override fun put(key: K, value: V) {
        store(key, value, load = null)
    }

    override fun invalidate(key: K) {
        inFlight.remove(key) // a load that is still running will not store its value
        val node = data.remove(key) ?: return
        node.state = State.RETIRED
        afterWrite {
            policy.onRemove(node)
            wheel?.deschedule(node)
            node.state = State.DEAD
        }
    }

    override fun invalidateAll() {
        inFlight.clear()
        for (key in data.keys) invalidate(key)
    }

    override fun estimatedSize(): Long = data.mappingCount()

    override fun cleanUp() {
        maintenanceLock.withLock { maintenance() }
    }

    override fun stats(): CacheStats = stats?.snapshot() ?: CacheStats(0, 0, 0)

    private fun startLoad(key: K, load: CompletableDeferred<V>, loader: suspend (K) -> V) {
        loadScope.launch {
            try {
                val value = loader(key)
                store(key, value, load)
                inFlight.remove(key, load)
                load.complete(value)
            } catch (e: Throwable) {
                // Failures are not cached: drop the load so the next caller tries again.
                inFlight.remove(key, load)
                load.completeExceptionally(e)
            }
        }
    }

    /**
     * Writes the value into the map and tells the policy about it.
     *
     * A finished load passes itself as [load], and its value is only stored if that load is
     * still the registered one for the key. invalidate() unregisters the load before it removes
     * the key, and we check inside the map's lock, so an invalidate during a load always wins.
     */
    private fun store(key: K, value: V, load: CompletableDeferred<V>?) {
        // Work out the expiry time first, so a slow expireAfter function never runs inside the map's lock.
        val expiresAt = if (wheel != null) expiresAt(now(), key, value) else NEVER
        var added: Node<K, V>? = null
        var updated: Node<K, V>? = null
        var oldExpiresAt = NEVER
        data.compute(key) { _, existing ->
            when {
                load != null && inFlight[key] !== load -> existing // invalidated while loading, leave the map alone
                existing == null -> Node(key, value, expiresAt).also { added = it }
                else -> existing.also {
                    oldExpiresAt = it.expiresAt
                    it.value = value
                    it.expiresAt = expiresAt
                    updated = it
                }
            }
        }

        added?.let { node ->
            // If the node was removed again before this task runs, the policy never needs to see it.
            afterWrite {
                if (node.state == State.ALIVE) {
                    wheel?.schedule(node)
                    policy.onAdd(node)
                }
            }
        }
        updated?.let { node ->
            // A new expiry time means the node has to move in the wheel, which only maintenance may do.
            // Small moves are skipped: if the wheel fires a little early, expire() checks again.
            if (wheel != null && abs(expiresAt - oldExpiresAt) > EXPIRY_TOLERANCE) {
                afterWrite {
                    if (node.state == State.ALIVE) {
                        wheel.schedule(node)
                        policy.onAccess(node)
                    }
                }
            } else {
                afterRead(node)
            }
        }
    }

    /** The cached value if it is there and not expired, without counting a hit or a miss. */
    private fun freshValue(key: K): V? {
        val node = data[key] ?: return null
        return if (wheel != null && node.isExpired(now())) null else node.value
    }

    private fun miss(): V? {
        stats?.misses?.increment()
        return null
    }

    private fun afterRead(node: Node<K, V>) {
        if (readBuffer.offer(node) == ReadBuffer.Result.FULL) tryMaintenance()
    }

    private fun afterWrite(task: () -> Unit) {
        writeBuffer.add(task)
        if (pendingWrites.incrementAndGet() > MAX_PENDING_WRITES) {
            // The thread doing maintenance is falling behind. Instead of letting the queue grow
            // without limit, wait for the lock and help. This slows writers down to the pace
            // the policy can keep up with.
            cleanUp()
        } else {
            tryMaintenance()
        }
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

    /** Applies everything buffered to the policy and the wheel. Only runs while holding [maintenanceLock]. */
    private fun maintenance() {
        readBuffer.drainTo { node ->
            if (node.state == State.ALIVE) {
                policy.onAccess(node)
                if (expireAfterAccess != null) wheel?.schedule(node) // the read pushed its expiry time back
            }
        }
        // Expire before adding, so the policy never evicts a live entry while an expired one still sits in the cache.
        wheel?.advance(now(), ::expire)
        while (true) {
            val task = writeBuffer.poll() ?: break
            pendingWrites.decrementAndGet()
            task()
        }
    }

    /** Called by the policy, under the lock, for every entry it throws out. */
    private fun evict(node: Node<K, V>) {
        // remove(key, node) only removes this exact node. If the user already removed the key, nothing happens.
        if (data.remove(node.key, node)) stats?.evictions?.increment()
        wheel?.deschedule(node)
        node.state = State.DEAD
    }

    /** Called by the wheel, under the lock, for a node whose time looks up. */
    private fun expire(node: Node<K, V>) {
        val now = now()
        var removed = false
        // Check again inside the map's lock: a put may have just given the node a later expiry time.
        data.computeIfPresent(node.key) { _, current ->
            if (current === node && current.isExpired(now)) {
                removed = true
                null
            } else {
                current
            }
        }
        if (removed) {
            policy.onRemove(node)
            node.state = State.DEAD
            stats?.evictions?.increment()
        } else if (node.state == State.ALIVE) {
            wheel?.schedule(node) // still alive, its time was pushed back
        }
    }

    private fun now(): Long = ticker.read() - origin

    private fun expiresAt(now: Long, key: K, value: V): Long {
        val lifetime = expireAfter?.invoke(key, value)?.inWholeNanoseconds
            ?: expireAfterWrite
            ?: expireAfterAccess
            ?: return NEVER
        return deadline(now, lifetime)
    }

    /** [now] plus [lifetime], stopping at [NEVER] instead of overflowing. */
    private fun deadline(now: Long, lifetime: Long): Long =
        if (lifetime >= NEVER - now) NEVER else now + lifetime

    /** LongAdder instead of AtomicLong, so threads bumping a counter do not fight over it. */
    private class StatsCounter {
        val hits = LongAdder()
        val misses = LongAdder()
        val evictions = LongAdder()

        fun snapshot() = CacheStats(hits.sum(), misses.sum(), evictions.sum())
    }

    private companion object {
        const val MAX_PENDING_WRITES = 1024
        val EXPIRY_TOLERANCE = TimeUnit.SECONDS.toNanos(1)
    }
}
