package io.github.shashankpatildev.kaffeine.bench

import java.util.zip.GZIPOutputStream
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals

class SimulatorTest {

    @Test
    fun `reads LIRS traces and skips lines that are not numbers`() {
        val file = createTempFile(suffix = ".trace").apply { writeText("5\n7\n*\n5\n") }

        assertContentEquals(longArrayOf(5, 7, 5), TraceFormat.of(file).read(file))
    }

    @Test
    fun `reads gzipped LIRS traces`() {
        val file = createTempFile(suffix = ".trace.gz")
        GZIPOutputStream(file.toFile().outputStream()).bufferedWriter().use { it.write("1\n2\n") }

        assertContentEquals(longArrayOf(1, 2), TraceFormat.of(file).read(file))
    }

    @Test
    fun `reads ARC traces and expands block ranges`() {
        val file = createTempFile(suffix = ".lis").apply { writeText("100 3 0 1\n7 1 0 2\n") }

        assertContentEquals(longArrayOf(100, 101, 102, 7), TraceFormat.of(file).read(file))
    }
}
