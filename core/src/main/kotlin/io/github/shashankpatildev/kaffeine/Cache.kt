package io.github.shashankpatildev.kaffeine

/**
 * An in-memory cache that holds at most `maximumSize` entries.
 *
 * When the cache is full it uses W-TinyLFU to decide which entry to drop, which keeps
 * popular entries around even when lots of one-time keys pass through.
 *
 * Every method is safe to call from many threads at the same time.
 * Create a cache with [kaffeine].
 */
public interface Cache<K : Any, V : Any> {

    /** Returns the value for [key], or null if the key is not in the cache. */
    public fun getIfPresent(key: K): V?

    /**
     * Returns the value for [key], loading it with [loader] if it is missing or expired.
     *
     * However many callers ask for the same missing key at once, [loader] runs only once and
     * they all get its result. This stops a crowd of requests from hitting the database at
     * the same moment (a "cache stampede").
     *
     * - If the loader throws, every waiting caller gets the exception and nothing is cached,
     *   so the next call tries again.
     * - If a caller is cancelled while waiting, only that caller stops. The load keeps going
     *   for the others, and its value is still cached.
     * - If the key is invalidated, or given a new value with [put], while it loads, the callers
     *   still get the loaded value, but it is not cached. Newer data always wins.
     * - The loader must not call [get] for the same key, or it would wait for itself forever.
     */
    public suspend fun get(key: K, loader: suspend (K) -> V): V

    /** Adds the value, or replaces the old one. Adding may evict another entry. */
    public fun put(key: K, value: V)

    /** Removes [key] from the cache, if it is there. */
    public fun invalidate(key: K)

    /** Removes every entry. */
    public fun invalidateAll()

    /** Number of entries right now. While other threads are writing, treat it as a close guess. */
    public fun estimatedSize(): Long

    /** A snapshot of the counters. Always zero unless `recordStats` was turned on. */
    public fun stats(): CacheStats

    /**
     * Does any pending bookkeeping right now, such as evicting entries above the maximum size.
     * The cache does this by itself as it is used, so you rarely need to call it. Handy in tests.
     */
    public fun cleanUp()
}
