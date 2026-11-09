package io.github.shashankpatildev.kaffeine

/**
 * Counters for a cache. See [Cache.stats].
 *
 * @property evictionCount entries the cache removed by itself, because it was full or because they expired
 * @property loadSuccessCount loads started by [Cache.get] that returned a value
 * @property loadFailureCount loads started by [Cache.get] that threw
 * @property totalLoadTimeNanos time spent inside loaders, whether they succeeded or not
 */
public data class CacheStats(
    public val hitCount: Long,
    public val missCount: Long,
    public val evictionCount: Long,
    public val loadSuccessCount: Long,
    public val loadFailureCount: Long,
    public val totalLoadTimeNanos: Long,
) {
    /** Hits plus misses. */
    public val requestCount: Long get() = hitCount + missCount

    /** Share of requests that were hits, from 0.0 to 1.0. It is 1.0 before the first request. */
    public val hitRate: Double get() = if (requestCount == 0L) 1.0 else hitCount.toDouble() / requestCount

    /** How long a load took on average, in nanoseconds. It is 0.0 before the first load. */
    public val averageLoadPenaltyNanos: Double
        get() {
            val loads = loadSuccessCount + loadFailureCount
            return if (loads == 0L) 0.0 else totalLoadTimeNanos.toDouble() / loads
        }
}
