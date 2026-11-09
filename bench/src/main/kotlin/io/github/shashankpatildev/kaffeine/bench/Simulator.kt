package io.github.shashankpatildev.kaffeine.bench

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.system.exitProcess
import kotlin.time.measureTimedValue

/** Every policy the simulator can run, by the name used on the command line. */
private val POLICIES: Map<String, (LongArray, Int) -> Long> = linkedMapOf(
    "lru" to ::simulateLru,
    "kaffeine" to { trace, size -> simulateKaffeine(trace, size, doorkeeper = false) },
    "kaffeine-doorkeeper" to { trace, size -> simulateKaffeine(trace, size, doorkeeper = true) },
    "caffeine" to ::simulateCaffeine,
    "belady" to ::simulateBelady,
)

private const val USAGE = """
Usage: simulator <trace file> <cache sizes> [policies]

  trace file   an ARC (.lis) or LIRS (.trace or .trace.gz) trace, see scripts/download-traces.sh
  cache sizes  comma separated, for example 1000,2000,5000
  policies     comma separated, default is all of: lru, kaffeine, kaffeine-doorkeeper, caffeine, belady

Example:
  ./gradlew :bench:run --args="traces/OLTP.lis 1000,2000,5000"
"""

/**
 * Replays a trace through each policy at each cache size and prints the hit ratios.
 * The same table is saved under bench/results/hit-ratio/ as Markdown and CSV.
 */
fun main(args: Array<String>) {
    if (args.size !in 2..3) {
        println(USAGE)
        exitProcess(1)
    }
    val tracePath = Path(args[0])
    val sizes = args[1].split(",").map { it.trim().toInt() }
    val policies = args.getOrNull(2)?.split(",")?.map { it.trim() } ?: POLICIES.keys.toList()
    policies.firstOrNull { it !in POLICIES }?.let {
        println("Unknown policy '$it'. Known policies: ${POLICIES.keys.joinToString()}")
        exitProcess(1)
    }

    val trace = TraceFormat.of(tracePath).read(tracePath)
    val name = tracePath.name.substringBefore('.')
    println("Loaded $name: ${trace.size} requests")

    val report = Report(name, trace.size, policies)
    for (size in sizes) {
        for (policy in policies) {
            val (hits, took) = measureTimedValue { POLICIES.getValue(policy)(trace, size) }
            report.add(size, policy, hits)
            println("  size $size, $policy: ${"%.2f".format(100.0 * hits / trace.size)}% ($took)")
        }
    }

    println()
    println(report.toMarkdown())
    save(report, name)
}

private fun save(report: Report, name: String) {
    val dir = Path.of("bench", "results", "hit-ratio").createDirectories()
    dir.resolve("$name.md").writeText(report.toMarkdown())
    dir.resolve("$name.csv").writeText(report.toCsv())
    println("Saved to $dir/$name.md and $name.csv")
}
