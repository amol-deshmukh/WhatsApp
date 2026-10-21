package com.example.whatsapp.data.sync

import kotlin.math.min
import kotlin.random.Random

/**
 * Exponential backoff with "full jitter" (AWS architecture blog). Jitter matters at scale: after a
 * server/regional outage, millions of clients reconnecting on the same schedule is a self-inflicted
 * DDoS (thundering herd).
 */
class BackoffPolicy(
    private val baseMs: Long = 500,
    private val maxMs: Long = 30_000,
    private val random: Random = Random.Default,
) {
    fun ceilingFor(attempt: Int): Long {
        val exp = baseMs shl attempt.coerceIn(0, 20)
        return min(exp, maxMs)
    }

    fun delayFor(attempt: Int): Long {
        val ceiling = ceilingFor(attempt)
        return ceiling / 2 + random.nextLong(ceiling / 2 + 1)
    }
}
