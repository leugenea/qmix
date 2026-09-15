package com.qmix.tv

class ReconnectBackoff(
    private val initialMillis: Long = 1_000L,
    private val maximumMillis: Long = 30_000L,
    private val randomFraction: () -> Double = Math::random,
) {
    init {
        require(initialMillis > 0)
        require(maximumMillis >= initialMillis)
    }

    fun delayMillis(attempt: Int): Long {
        var raw = initialMillis
        var remaining = attempt.coerceAtLeast(0)
        while (remaining > 0 && raw < maximumMillis) {
            raw = (raw * 2).coerceAtMost(maximumMillis)
            remaining--
        }
        val minimum = raw / 2
        val fraction = randomFraction().coerceIn(0.0, Math.nextDown(1.0))
        return minimum + ((raw - minimum) * fraction).toLong()
    }
}
