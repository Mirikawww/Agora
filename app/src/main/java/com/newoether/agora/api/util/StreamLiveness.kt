package com.newoether.agora.api.util

import com.newoether.agora.api.GenerationError

/**
 * Turns the short socket read ticks of [com.newoether.agora.api.HttpClient.streamClient] into a
 * real stall decision.
 *
 * A read timeout on a streaming response is not itself an error — it only means no bytes arrived
 * during the last tick, which is normal while a reasoning model thinks before its first token.
 * It becomes an error once the silence outlasts the budget.
 *
 * Every provider used to answer a read timeout with a bare `continue`, so a dead upstream held the
 * UI on a spinner for the full socket timeout and then surfaced as a generic "Unknown error"
 * instead of a timeout. Budgets are split because the two silences mean different things: nothing
 * yet can legitimately take a while, whereas silence *after* content has started is a broken stream.
 */
class StreamLiveness(
    private val firstTokenTimeoutMs: Long = 120_000L,
    private val idleTimeoutMs: Long = 90_000L,
) {
    private val startedMs = System.currentTimeMillis()
    private var lastActivityMs = startedMs
    private var sawContent = false

    /** Every line handed back by the reader, including blank SSE separators. */
    fun onLine(line: String) {
        lastActivityMs = System.currentTimeMillis()
        if (line.isNotBlank()) sawContent = true
    }

    /** Null while the stream is merely quiet; non-null once it should be abandoned. */
    fun stalled(): GenerationError.StreamStalled? {
        val idle = System.currentTimeMillis() - lastActivityMs
        val limit = if (sawContent) idleTimeoutMs else firstTokenTimeoutMs
        return if (idle >= limit) GenerationError.StreamStalled(idle, sawContent) else null
    }

    val elapsedMs: Long get() = System.currentTimeMillis() - startedMs
}
