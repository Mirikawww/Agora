package com.newoether.agora.viewmodel

import com.newoether.agora.api.StreamEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationUsageAccumulatorTest {
    @Test
    fun `usage from tool continuation rounds is accumulated without double counting`() {
        val accumulator = GenerationUsageAccumulator()

        accumulator.update(
            StreamEvent.UsageUpdate(
                tokenCount = 10_500,
                promptTokens = 10_000,
                cachedPromptTokens = 9_000,
                cacheTelemetryAvailable = true,
                completionTokens = 500,
            ),
        )
        accumulator.settleRound()
        accumulator.update(
            StreamEvent.UsageUpdate(
                tokenCount = 2_250,
                promptTokens = 2_000,
                cachedPromptTokens = 1_500,
                cacheTelemetryAvailable = true,
                completionTokens = 250,
            ),
        )

        val live = accumulator.snapshot()
        assertEquals(12_750, live.tokenCount)
        assertEquals(12_000, live.promptTokens)
        assertEquals(10_500, live.cachedPromptTokens)
        assertTrue(live.cacheTelemetryAvailable)
        assertEquals(750, live.completionTokens)

        accumulator.settleRound()
        assertEquals(live, accumulator.snapshot())
    }

    @Test
    fun `cache split is unavailable if any prompt-bearing round omits telemetry`() {
        val accumulator = GenerationUsageAccumulator()
        accumulator.update(
            StreamEvent.UsageUpdate(
                tokenCount = 10_500,
                promptTokens = 10_000,
                cachedPromptTokens = 9_000,
                cacheTelemetryAvailable = true,
                completionTokens = 500,
            ),
        )
        accumulator.settleRound()
        accumulator.update(
            StreamEvent.UsageUpdate(
                tokenCount = 2_250,
                promptTokens = 2_000,
                completionTokens = 250,
            ),
        )

        val usage = accumulator.snapshot()
        assertEquals(12_000, usage.promptTokens)
        assertEquals(9_000, usage.cachedPromptTokens)
        assertFalse(usage.cacheTelemetryAvailable)
    }
}
