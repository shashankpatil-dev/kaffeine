package io.github.shashankpatildev.kaffeine.internal

/**
 * Mixes the bits of a hash code so they look random.
 *
 * Many hashCode() methods are weak. Int.hashCode() is just the number itself, so keys
 * 1, 2, 3 would sit right next to each other in our tables. After mixing, nearby keys
 * end up far apart.
 */
internal fun spread(x: Int): Int {
    var h = ((x ushr 16) xor x) * 0x45d9f3b
    h = ((h ushr 16) xor h) * 0x45d9f3b
    return (h ushr 16) xor h
}

/**
 * The smallest power of two that is >= [x], for example 100 -> 128.
 *
 * Tables sized as a power of two let us use `hash and (size - 1)` instead of the slower `hash % size`.
 */
internal fun ceilingPowerOfTwo(x: Long): Long =
    if (x <= 1) 1 else java.lang.Long.highestOneBit(x - 1) shl 1
