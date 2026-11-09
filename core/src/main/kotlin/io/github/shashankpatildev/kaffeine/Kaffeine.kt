package io.github.shashankpatildev.kaffeine

import io.github.shashankpatildev.kaffeine.internal.BoundedCache

/**
 * Creates a new cache.
 *
 * ```
 * val users = kaffeine<Long, User> {
 *     maximumSize = 10_000
 *     recordStats = true
 * }
 * ```
 *
 * @throws IllegalArgumentException if the settings are not valid
 */
public fun <K : Any, V : Any> kaffeine(configure: KaffeineConfig.() -> Unit): Cache<K, V> {
    val config = KaffeineConfig().apply(configure)
    config.validate()
    return BoundedCache(config)
}

/** Settings for [kaffeine]. */
public class KaffeineConfig internal constructor() {

    /** The most entries the cache may hold. Required, and must be above zero. */
    public var maximumSize: Long = -1

    /** Count hits, misses and evictions. Off by default, because counting costs a little time. */
    public var recordStats: Boolean = false

    /**
     * Put a small Bloom filter in front of the frequency sketch, so keys that are seen only
     * once never take space in it. This is the "doorkeeper" idea from the TinyLFU paper.
     */
    public var doorkeeper: Boolean = true

    internal fun validate() {
        require(maximumSize > 0) { "maximumSize must be above zero, but was $maximumSize" }
    }
}
