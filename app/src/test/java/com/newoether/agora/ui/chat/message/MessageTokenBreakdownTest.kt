package com.newoether.agora.ui.chat.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageTokenBreakdownTest {
    @Test
    fun `prompt total is split into uncached and cached input`() {
        val breakdown = messageTokenBreakdown(
            promptTokens = 10_000,
            cachedPromptTokens = 9_000,
            cacheTelemetryAvailable = true,
            completionTokens = 500,
        )

        assertEquals(10_000, breakdown.totalInputTokens)
        assertEquals(1_000, breakdown.freshInputTokens)
        assertEquals(9_000, breakdown.cachedInputTokens)
        assertTrue(breakdown.cacheTelemetryAvailable)
        assertEquals(500, breakdown.outputTokens)
    }

    @Test
    fun `missing provider telemetry is not presented as a zero cache hit`() {
        val breakdown = messageTokenBreakdown(
            promptTokens = 10_000,
            cachedPromptTokens = 0,
            cacheTelemetryAvailable = false,
            completionTokens = 500,
        )

        assertEquals(10_000, breakdown.totalInputTokens)
        assertFalse(breakdown.cacheTelemetryAvailable)
    }
}
