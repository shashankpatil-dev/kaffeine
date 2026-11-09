package io.github.shashankpatildev.kaffeine

import io.github.shashankpatildev.kaffeine.internal.BoundedCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

/**
 * Creates a new cache.
 *
 * ```
 * val users = kaffeine<Long, User> {
 *     maximumSize = 10_000
 *     expireAfterWrite = 10.minutes
 *     recordStats = true
 * }
 * ```
 *
 * @throws IllegalArgumentException if the settings are not valid
 */
public fun <K : Any, V : Any> kaffeine(configure: KaffeineConfig<K, V>.() -> Unit): Cache<K, V> {
    val config = KaffeineConfig<K, V>().apply(configure)
    config.validate()
    return BoundedCache(config)
}

/** Settings for [kaffeine]. */
public class KaffeineConfig<K : Any, V : Any> internal constructor() {

    /** The most entries the cache may hold. Required, and must be above zero. */
    public var maximumSize: Long = -1

    /** Remove an entry this long after it was added, or after its value was last replaced. */
    public var expireAfterWrite: Duration? = null

    /** Remove an entry this long after it was last read or written. */
    public var expireAfterAccess: Duration? = null

    /**
     * Give each entry its own lifetime, worked out from its key and value every time it is added
     * or replaced. For example the max-age of an HTTP response. Zero or less expires it right away.
     */
    public var expireAfter: ((key: K, value: V) -> Duration)? = null

    /** Count hits, misses and evictions. Off by default, because counting costs a little time. */
    public var recordStats: Boolean = false

    /**
     * Put a small Bloom filter in front of the frequency sketch, so keys that are seen only
     * once never take space in it. This is the "doorkeeper" idea from the TinyLFU paper.
     *
     * Off by default. On our seven test traces it helped at fewer than half of the cache sizes
     * and lowered the hit ratio slightly on average, while costing about 10 extra bytes per entry.
     * See bench/RESULTS.md.
     */
    public var doorkeeper: Boolean = false

    /** Where the cache gets the time from. Tests can pass a fake one to control expiry. */
    public var ticker: Ticker = Ticker.SYSTEM

    /**
     * Where the loaders passed to [Cache.get] run. If your loader blocks a thread (JDBC, file
     * reads), use Dispatchers.IO. It must not contain a Job, because the cache owns its loads:
     * one caller being cancelled must never cancel a load that others are waiting for.
     */
    public var loadContext: CoroutineContext = Dispatchers.Default

    /**
     * Where the cache does its upkeep: applying buffered reads and writes to the eviction
     * policy, evicting, and expiring. Doing it here means the threads calling get and put
     * return sooner. Pass `Executor(Runnable::run)` to do it right away on the calling thread
     * instead, which makes tests and simulations repeat exactly.
     */
    public var executor: Executor = ForkJoinPool.commonPool()

    internal fun validate() {
        require(maximumSize > 0) { "maximumSize must be above zero, but was $maximumSize" }
        val expiryModes = listOfNotNull(expireAfterWrite, expireAfterAccess, expireAfter)
        require(expiryModes.size <= 1) { "Pick at most one of expireAfterWrite, expireAfterAccess and expireAfter" }
        expireAfterWrite?.let { require(it.isPositive()) { "expireAfterWrite must be above zero, but was $it" } }
        expireAfterAccess?.let { require(it.isPositive()) { "expireAfterAccess must be above zero, but was $it" } }
        require(loadContext[Job] == null) { "loadContext must not contain a Job, the cache manages its own loads" }
    }
}
