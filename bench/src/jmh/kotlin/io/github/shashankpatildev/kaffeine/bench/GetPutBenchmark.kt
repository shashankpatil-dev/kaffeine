package io.github.shashankpatildev.kaffeine.bench

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import java.util.concurrent.ThreadLocalRandom

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

    private lateinit var target: BenchCache<Int, Int>
    private var readsOutOfFour = 4

    @Setup
    fun setup() {
        target = BenchCache.create(cache, maximumSize = 2 * DISTINCT_KEYS)
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
