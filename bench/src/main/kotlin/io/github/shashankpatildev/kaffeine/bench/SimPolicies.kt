package io.github.shashankpatildev.kaffeine.bench

import com.github.benmanes.caffeine.cache.Caffeine
import io.github.shashankpatildev.kaffeine.kaffeine

/*
 * Each simulation replays a whole trace through one cache and returns the number of hits.
 * On a miss the key is put into the cache, just like a real app would do after loading it.
 */

/** Plain LRU, the baseline every smarter policy should beat. */
fun simulateLru(trace: LongArray, cacheSize: Int): Long {
    val cache = object : LinkedHashMap<Long, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Long>) = size > cacheSize
    }
    return replay(trace) { key ->
        val hit = cache.get(key) != null
        if (!hit) cache[key] = key
        hit
    }
}

fun simulateKaffeine(trace: LongArray, cacheSize: Int, doorkeeper: Boolean): Long {
    val cache = kaffeine<Long, Long> {
        maximumSize = cacheSize.toLong()
        this.doorkeeper = doorkeeper
    }
    return replay(trace) { key ->
        val hit = cache.getIfPresent(key) != null
        if (!hit) cache.put(key, key)
        hit
    }
}

fun simulateCaffeine(trace: LongArray, cacheSize: Int): Long {
    val cache = Caffeine.newBuilder()
        .maximumSize(cacheSize.toLong())
        .executor(Runnable::run) // do maintenance right away on this thread, so runs are repeatable
        .build<Long, Long>()
    return replay(trace) { key ->
        val hit = cache.getIfPresent(key) != null
        if (!hit) cache.put(key, key)
        hit
    }
}

private inline fun replay(trace: LongArray, access: (Long) -> Boolean): Long {
    var hits = 0L
    for (key in trace) if (access(key)) hits++
    return hits
}
