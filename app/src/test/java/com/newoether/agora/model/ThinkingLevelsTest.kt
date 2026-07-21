package com.newoether.agora.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ThinkingLevelsTest {

    @Test
    fun `effort slider exposes five levels without minimal`() {
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), ThinkingLevels.effortValues)
        assertFalse(ThinkingLevels.effortValues.contains("minimal"))
    }

    @Test
    fun `removed minimal value falls back to default effort`() {
        assertEquals(ThinkingLevels.DefaultEffort, ThinkingLevels.normalize("minimal"))
    }

    @Test
    fun `provider slider ranges match the five-level scale`() {
        assertEquals(0..3, ThinkingLevels.effortRangeForProvider("OpenAI"))
        assertEquals(0..2, ThinkingLevels.effortRangeForProvider("Google"))
        assertEquals(0..4, ThinkingLevels.effortRangeForProvider("Anthropic"))
    }
}
