package io.github.shashankpatildev.kaffeine

/**
 * Counters for a cache. See [Cache.stats].
 *
 * @property evictionCount entries the cache removed by itself, because it was full or because they expired
 */
public data class CacheStats(
    public val hitCount: Long,
    public val missCount: Long,
    public val evictionCount: Long,
) {
    /** Hits plus misses. */
    public val requestCount: Long get() = hitCount + missCount

    /** Share of requests that were hits, from 0.0 to 1.0. It is 1.0 before the first request. */
    public val hitRate: Double get() = if (requestCount == 0L) 1.0 else hitCount.toDouble() / requestCount
}
