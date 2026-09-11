package io.github.loliwolf.openlisttv.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class ReservoirSamplerTest {
    @Test fun boundedSlotsAndLateItemsParticipate() {
        val sampler = ReservoirSampler(100, Random(7)); val sample = IntArray(100)
        repeat(1_000_000) { i -> sampler.nextSlot()?.let { slot -> assertTrue(slot in 1..100); sample[slot-1]=i } }
        assertEquals(1_000_000L, sampler.seen)
        assertEquals(100, sample.toSet().size)
        assertTrue(sample.any { it > 900_000 })
    }
    @Test fun deterministicTrialsGiveEqualInclusionAcrossTheStream() {
        val frequency = IntArray(100)
        repeat(5000) { seed ->
            val sampler = ReservoirSampler(10, Random(seed)); val sample = IntArray(10)
            repeat(100) { i -> sampler.nextSlot()?.let { sample[it-1] = i } }
            assertEquals(10, sample.toSet().size); sample.forEach { frequency[it]++ }
        }
        // Deterministic seeds, broad statistical guard: expected 500 inclusions/item.
        assertTrue(frequency.toList().toString(), frequency.all { it in 400..600 })
    }
    @Test fun smallStreamsAreKeptEntirely() {
        assertThrows(IllegalArgumentException::class.java) { ReservoirSampler(0) }
        val sampler = ReservoirSampler(100)
        assertEquals((1..50).toList(), (1..50).map { sampler.nextSlot() })
    }
}
