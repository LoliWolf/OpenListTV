package io.github.loliwolf.openlisttv.core

import kotlin.random.Random

/** Algorithm R: uniform inclusion over a stable, duplicate-free stream, not full coverage.
 * Only a slot number is retained here; payloads live in bounded SQLite storage. */
class ReservoirSampler(val capacity: Int, private val random: Random = Random.Default) {
    var seen: Long = 0
        private set
    init { require(capacity > 0) }
    fun nextSlot(): Int? {
        check(seen < Long.MAX_VALUE)
        seen++
        if (seen <= capacity) return seen.toInt()
        return random.nextLong(seen).takeIf { it < capacity }?.toInt()?.plus(1)
    }
}
