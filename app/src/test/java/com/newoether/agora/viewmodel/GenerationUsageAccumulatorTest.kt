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

    @Test
    fun `settled requests retain local per-round usage and tool cost metadata`() {
        val accumulator = GenerationUsageAccumulator()
        accumulator.update(
            StreamEvent.UsageUpdate(
                tokenCount = 1_250,
                promptTokens = 1_000,
                cachedPromptTokens = 800,
                cacheTelemetryAvailable = true,
                completionTokens = 250,
            ),
        )

        accumulator.settleRound(
            RoundUsageMetadata(
                durationMs = 900,
                toolExecutionMs = 120,
                toolNames = listOf("web_search"),
                originalToolResultChars = 12_000,
                injectedToolResultChars = 8_000,
                brokerActions = listOf("search", "invoke"),
                routeMode = "DIRECT",
                inlineSchemaTokens = 640,
                brokerSchemaTokens = 180,
                wireSchemaTokens = 1_090,
                wireToolCount = 4,
            ),
        )

        val round = accumulator.rounds().single()
        assertEquals(1, round.roundIndex)
        assertEquals(1_000, round.promptTokens)
        assertEquals(800, round.cachedPromptTokens)
        assertTrue(round.providerUsageReported)
        assertTrue(round.promptUsageReported)
        assertEquals(900, round.durationMs)
        assertEquals(120, round.toolExecutionMs)
        assertEquals(listOf("web_search"), round.toolNames)
        assertEquals(12_000, round.originalToolResultChars)
        assertEquals(8_000, round.injectedToolResultChars)
        assertEquals(listOf("search", "invoke"), round.brokerActions)
        assertEquals(820, round.inlineSchemaTokens + round.brokerSchemaTokens)
        assertEquals(1_090, round.wireSchemaTokens)
    }

    @Test
    fun `completion-only provider usage preserves unknown prompt split`() {
        val accumulator = GenerationUsageAccumulator()
        accumulator.update(
            StreamEvent.UsageUpdate(
                tokenCount = 37,
                completionTokens = 37,
            ),
        )

        accumulator.settleRound()

        val round = accumulator.rounds().single()
        assertTrue(round.providerUsageReported)
        assertFalse(round.promptUsageReported)
        assertEquals(0, round.promptTokens)
        assertEquals(37, round.completionTokens)
    }
}
