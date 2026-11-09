package io.github.shashankpatildev.kaffeine.bench

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import java.util.concurrent.ThreadLocalRandom
import kotlin.time.Duration.Companion.hours

/**
 * How many reads and writes per second each cache can do, at different thread counts.
 *
 * Every key is put in the cache up front, and the cache is big enough to hold all of them,
 * so reads always hit. That way we measure the cost of the cache's own bookkeeping
 * (locks, buffers, policy updates) and not the cost of misses.
 *
 * JMH needs these classes to be open, because it generates subclasses of them.
 */
@State(Scope.Benchmark)
open class GetPutBenchmark {

    @Param("kaffeine", "caffeine", "lhm-lock", "chm")
    lateinit var cache: String

    @Param("read_100", "read_75", "write_100")
    lateinit var workload: String

    /**
     * Run with `-p expiry=off,on` to see what expiry costs. "on" sets expireAfterWrite to one
     * hour, so the cache does all the expiry bookkeeping but nothing actually expires.
     */
    @Param("off")
    lateinit var expiry: String

    private lateinit var target: BenchCache<Int, Int>
    private var readsOutOfFour = 4

    @Setup
    fun setup() {
        val expireAfterWrite = when (expiry) {
            "off" -> null
            "on" -> 1.hours
            else -> error("Unknown expiry setting: $expiry")
        }
        target = BenchCache.create(cache, maximumSize = 2 * DISTINCT_KEYS, expireAfterWrite)
        for (key in KEYS) target.put(key, key)
        readsOutOfFour = when (workload) {
            "read_100" -> 4
            "read_75" -> 3
            "write_100" -> 0
            else -> error("Unknown workload: $workload")
        }
    }

    @State(Scope.Thread)
    open class ThreadState {
        // Each thread starts somewhere else in the key list, so threads do not move in lockstep.
        var index = ThreadLocalRandom.current().nextInt()
    }

    /** Returning the value tells JMH it is used, so the JIT cannot optimize the call away. */
    @Benchmark
    fun getPut(thread: ThreadState): Int? {
        val i = thread.index++
        val key = KEYS[i and MASK]
        if ((i and 3) < readsOutOfFour) return target.get(key)
        target.put(key, key)
        return null
    }

    private companion object {
        const val SIZE = 1 shl 20 // pre-generated key picks, walked through in a loop
        const val MASK = SIZE - 1
        const val DISTINCT_KEYS = 1 shl 16
        val KEYS: Array<Int> = zipfKeys(SIZE, DISTINCT_KEYS)
    }
}
