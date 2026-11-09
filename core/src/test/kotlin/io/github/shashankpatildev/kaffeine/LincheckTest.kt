package io.github.shashankpatildev.kaffeine

import io.github.shashankpatildev.kaffeine.internal.BoundedCache
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.Executor
import org.jetbrains.lincheck.datastructures.IntGen
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Param
import org.jetbrains.lincheck.datastructures.StressOptions
import org.jetbrains.lincheck.datastructures.Validate
import kotlin.test.Test

/**
 * Lincheck runs these operations from several threads in many different orders, then checks
 * that every result could also have come from running them one at a time (linearizability).
 *
 * The cache is big enough that nothing is ever evicted, so it has to behave exactly like a map.
 * Model checking also steps through our buffers and maintenance code, switching threads at
 * the worst possible moments. After each run, [policyMatchesMap] checks that the buffered
 * policy updates were applied correctly, whatever order they arrived in.
 */
@Param(name = "key", gen = IntGen::class, conf = "1:5")
class LincheckTest {

    private val cache = kaffeine<Int, Int> {
        maximumSize = 16
        // Run loads and upkeep right in the calling thread, so Lincheck controls every thread involved.
        loadContext = Dispatchers.Unconfined
        executor = Executor(Runnable::run)
    }

    @Operation
    fun put(@Param(name = "key") key: Int, value: Int) = cache.put(key, value)

    @Operation
    fun get(@Param(name = "key") key: Int) = cache.getIfPresent(key)

    @Operation
    fun invalidate(@Param(name = "key") key: Int) = cache.invalidate(key)

    @Operation
    suspend fun load(@Param(name = "key") key: Int) = cache.get(key) { it * 10 }

    @Validate
    fun policyMatchesMap() {
        cache.cleanUp()
        val mapSize = cache.estimatedSize().toInt()
        val policySize = (cache as BoundedCache).policySize
        check(mapSize == policySize) { "the map holds $mapSize entries but the policy tracks $policySize" }
    }

    // Small scenarios explored in many interleavings. Races usually need only a few operations
    // to show up, and this keeps the test well under a minute.
    @Test
    fun `model checking`() = ModelCheckingOptions()
        .iterations(10)
        .invocationsPerIteration(500)
        .actorsPerThread(3)
        .check(this::class)

    @Test
    fun `stress`() = StressOptions().check(this::class)
}
