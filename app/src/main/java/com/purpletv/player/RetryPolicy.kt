package com.purpletv.player

/** Exponential backoff for reconnecting: 2 s, 4 s, 8 s and so on, never above [maxMs]. */
class RetryPolicy(private val baseMs: Long = 2_000, private val maxMs: Long = 30_000) {
    fun delayFor(attempt: Int): Long {
        val shift = attempt.coerceIn(0, MAX_SHIFT)
        return (baseMs shl shift).coerceAtMost(maxMs)
    }

    private companion object {
        const val MAX_SHIFT = 20
    }
}
