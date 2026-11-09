package io.github.shashankpatildev.kaffeine.bench

import java.util.Locale

/** Hit ratios for one trace. Rows are cache sizes and columns are policies. */
class Report(private val trace: String, private val requests: Int, private val policies: List<String>) {

    private val hitRatios = sortedMapOf<Int, MutableMap<String, Double>>()

    fun add(cacheSize: Int, policy: String, hits: Long) {
        hitRatios.getOrPut(cacheSize) { mutableMapOf() }[policy] = 100.0 * hits / requests
    }

    fun toMarkdown(): String = buildString {
        appendLine("### $trace (${"%,d".format(Locale.US, requests)} requests)")
        appendLine()
        appendLine("Hit ratio in %.")
        appendLine()
        appendLine("| Cache size | ${policies.joinToString(" | ")} |")
        appendLine("|---:|${policies.joinToString("") { "---:|" }}")
        for ((size, row) in hitRatios) {
            val cells = policies.joinToString(" | ") { "%.2f".format(Locale.US, row.getValue(it)) }
            appendLine("| ${"%,d".format(Locale.US, size)} | $cells |")
        }
    }

    fun toCsv(): String = buildString {
        appendLine("cache_size,${policies.joinToString(",")}")
        for ((size, row) in hitRatios) {
            appendLine("$size,${policies.joinToString(",") { "%.4f".format(Locale.US, row.getValue(it)) }}")
        }
    }
}
