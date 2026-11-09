package io.github.shashankpatildev.kaffeine

/**
 * Where the cache gets the time from. Only the difference between two readings matters,
 * so the zero point can be anything.
 *
 * Tests can pass a fake ticker and move time forward by hand, so expiry can be tested
 * instantly, without sleeping.
 */
public fun interface Ticker {

    /** The current time in nanoseconds. */
    public fun read(): Long

    public companion object {
        /** The real clock, [System.nanoTime]. */
        public val SYSTEM: Ticker = Ticker { System.nanoTime() }
    }
}
