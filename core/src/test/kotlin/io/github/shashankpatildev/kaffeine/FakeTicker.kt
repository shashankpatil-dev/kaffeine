package io.github.shashankpatildev.kaffeine

import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

/** A clock that only moves when the test says so. */
class FakeTicker : Ticker {
    private val nanos = AtomicLong()

    override fun read(): Long = nanos.get()

    fun advance(duration: Duration) {
        nanos.addAndGet(duration.inWholeNanoseconds)
    }
}
