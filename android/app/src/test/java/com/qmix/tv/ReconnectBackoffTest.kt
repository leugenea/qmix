package com.qmix.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectBackoffTest {
    @Test
    fun exponential_delays_use_equal_jitter_and_stop_growing_at_the_cap() {
        val minimum = ReconnectBackoff(randomFraction = { 0.0 })
        val maximum = ReconnectBackoff(randomFraction = { 0.999999 })

        assertEquals(listOf(500L, 1_000L, 2_000L, 15_000L, 15_000L), listOf(0, 1, 2, 20, Int.MAX_VALUE).map(minimum::delayMillis))
        assertEquals(999L, maximum.delayMillis(0))
        assertEquals(29_999L, maximum.delayMillis(Int.MAX_VALUE))
    }
}
