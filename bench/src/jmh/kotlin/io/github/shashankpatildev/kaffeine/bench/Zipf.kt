package io.github.shashankpatildev.kaffeine.bench

import kotlin.math.pow
import kotlin.random.Random

/**
 * Picks [count] keys out of [distinctKeys] following a Zipf distribution: a few keys are very
 * popular and most are rare, like real traffic. The key with rank r is picked with a
 * probability proportional to 1 / r^[exponent].
 *
 * The keys come back boxed, the way they would arrive in a real app, so the benchmark loop
 * does not spend its time boxing ints.
 */
fun zipfKeys(count: Int, distinctKeys: Int, exponent: Double = 0.99, seed: Long = 42): Array<Int> {
    // cumulative[r] = chance that a pick has rank r or lower
    val cumulative = DoubleArray(distinctKeys)
    var sum = 0.0
    for (rank in 0 until distinctKeys) {
        sum += 1.0 / (rank + 1.0).pow(exponent)
        cumulative[rank] = sum
    }
    for (rank in cumulative.indices) cumulative[rank] /= sum

    // Shuffle which key gets which rank, so the hot keys are not just the smallest numbers.
    val random = Random(seed)
    val keyForRank = (0 until distinctKeys).shuffled(random)

    return Array(count) {
        val found = cumulative.binarySearch(random.nextDouble())
        val rank = (if (found >= 0) found else -found - 1).coerceAtMost(distinctKeys - 1)
        keyForRank[rank]
    }
}
