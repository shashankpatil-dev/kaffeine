package io.github.shashankpatildev.kaffeine.bench

import com.github.benmanes.caffeine.cache.Caffeine
import io.github.shashankpatildev.kaffeine.kaffeine
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/** The small surface every cache in the benchmark shares. */
interface BenchCache<K : Any, V : Any> {
    fun get(key: K): V?
    fun put(key: K, value: V)

    companion object {
        fun <K : Any, V : Any> create(type: String, maximumSize: Int, expireAfterWrite: Duration?): BenchCache<K, V> {
            require(expireAfterWrite == null || type == "kaffeine" || type == "caffeine") { "$type has no expiry" }
            return when (type) {
                "kaffeine" -> KaffeineCache(maximumSize, expireAfterWrite)
                "caffeine" -> CaffeineCache(maximumSize, expireAfterWrite)
                "lhm-lock" -> LinkedHashMapCache(maximumSize)
                "chm" -> ConcurrentHashMapCache()
                else -> error("Unknown cache type: $type")
            }
        }
    }
}

private class KaffeineCache<K : Any, V : Any>(maximumSize: Int, expireAfterWrite: Duration?) : BenchCache<K, V> {
    private val cache = kaffeine<K, V> {
        this.maximumSize = maximumSize.toLong()
        this.expireAfterWrite = expireAfterWrite
    }
    override fun get(key: K): V? = cache.getIfPresent(key)
    override fun put(key: K, value: V) = cache.put(key, value)
}

private class CaffeineCache<K : Any, V : Any>(maximumSize: Int, expireAfterWrite: Duration?) : BenchCache<K, V> {
    private val cache = Caffeine.newBuilder()
        .maximumSize(maximumSize.toLong())
        .apply { if (expireAfterWrite != null) expireAfterWrite(expireAfterWrite.toJavaDuration()) }
        .build<K, V>()
    override fun get(key: K): V? = cache.getIfPresent(key)
    override fun put(key: K, value: V) = cache.put(key, value)
}

/** The naive way: an LRU map behind one big lock. This is the floor. */
private class LinkedHashMapCache<K : Any, V : Any>(maximumSize: Int) : BenchCache<K, V> {
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>) = size > maximumSize
    }
    override fun get(key: K): V? = synchronized(map) { map[key] }
    override fun put(key: K, value: V) {
        synchronized(map) { map[key] = value }
    }
}

/** A plain map with no eviction at all. No cache can be faster, so this is the ceiling. */
private class ConcurrentHashMapCache<K : Any, V : Any> : BenchCache<K, V> {
    private val map = ConcurrentHashMap<K, V>()
    override fun get(key: K): V? = map[key]
    override fun put(key: K, value: V) {
        map[key] = value
    }
}
