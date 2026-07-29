package com.newoether.agora.api.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LocalProviderTest {
    @Test
    fun `llama generated tokens are completion usage with prompt split unavailable`() {
        val usage = localUsageUpdate(37)

        assertEquals(37, usage.tokenCount)
        assertEquals(37, usage.completionTokens)
        assertEquals(0, usage.promptTokens)
        assertFalse(usage.cacheTelemetryAvailable)
    }
}
