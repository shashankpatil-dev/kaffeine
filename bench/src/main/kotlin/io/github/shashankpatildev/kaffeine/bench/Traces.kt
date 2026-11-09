package io.github.shashankpatildev.kaffeine.bench

import java.nio.file.Path
import java.util.StringTokenizer
import java.util.zip.GZIPInputStream
import kotlin.io.path.inputStream
import kotlin.io.path.name

/**
 * The trace file formats the simulator understands. A trace is just a long list of
 * requested keys, recorded from a real system.
 */
enum class TraceFormat {

    /** LIRS traces: one block number per line. Lines that are not numbers (like "*") are skipped. */
    LIRS {
        override fun parse(line: String, out: LongList) {
            line.trim().toLongOrNull()?.let(out::add)
        }
    },

    /** ARC traces: "startBlock blockCount ignored requestId". One line stands for blockCount requests in a row. */
    ARC {
        override fun parse(line: String, out: LongList) {
            val parts = StringTokenizer(line)
            if (parts.countTokens() < 2) return
            val start = parts.nextToken().toLong()
            val count = parts.nextToken().toInt()
            for (i in 0 until count) out.add(start + i)
        }
    };

    abstract fun parse(line: String, out: LongList)

    /** Reads every request in the file. Files ending in .gz are unpacked on the fly. */
    fun read(path: Path): LongArray {
        val input = path.inputStream().let { if (path.name.endsWith(".gz")) GZIPInputStream(it) else it }
        val keys = LongList()
        input.bufferedReader().useLines { lines -> lines.forEach { parse(it, keys) } }
        return keys.toArray()
    }

    companion object {
        /** Picks the format from the file name: ".lis" is ARC, ".trace" is LIRS. */
        fun of(path: Path): TraceFormat = when {
            ".lis" in path.name -> ARC
            ".trace" in path.name -> LIRS
            else -> error("Unknown trace format for ${path.name}. Expected a .lis (ARC) or .trace (LIRS) file.")
        }
    }
}

/** A growing list of longs. A List<Long> would box every one of the tens of millions of keys. */
class LongList {
    private var items = LongArray(1 shl 16)
    private var size = 0

    fun add(value: Long) {
        if (size == items.size) items = items.copyOf(size * 2)
        items[size++] = value
    }

    fun toArray(): LongArray = items.copyOf(size)
}
