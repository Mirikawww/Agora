package com.newoether.agora.api

import com.newoether.agora.api.anthropic.AnthropicUsage
import com.newoether.agora.api.anthropic.toUsageUpdate
import com.newoether.agora.api.gemini.ApiUsageMetadata
import com.newoether.agora.api.gemini.toUsageUpdate
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderUsageTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `OpenAI usage keeps cached prompt tokens separate from total input`() {
        val response = json.decodeFromString<OpenAiStreamResponse>(
            """
            {
              "usage": {
                "prompt_tokens": 11000,
                "completion_tokens": 500,
                "total_tokens": 11500,
                "prompt_tokens_details": {
                  "cached_tokens": 9000
                }
              }
            }
            """.trimIndent(),
        )

        val usage = requireNotNull(response.usage).toUsageUpdate()

        assertEquals(11_000, usage.promptTokens)
        assertEquals(9_000, usage.cachedPromptTokens)
        assertTrue(usage.cacheTelemetryAvailable)
        assertEquals(500, usage.completionTokens)
        assertEquals(11_500, usage.tokenCount)
    }

    @Test
    fun `OpenAI usage does not invent a zero cache split when details are absent`() {
        val response = json.decodeFromString<OpenAiStreamResponse>(
            """
            {
              "usage": {
                "prompt_tokens": 11000,
                "completion_tokens": 500,
                "total_tokens": 11500
              }
            }
            """.trimIndent(),
        )

        val usage = requireNotNull(response.usage).toUsageUpdate()

        assertEquals(11_000, usage.promptTokens)
        assertEquals(0, usage.cachedPromptTokens)
        assertFalse(usage.cacheTelemetryAvailable)
    }

    @Test
    fun `Anthropic usage totals fresh cache creation and cache read input`() {
        val messageStartUsage = json.decodeFromString<AnthropicUsage>(
            """
            {
              "input_tokens": 400,
              "cache_creation_input_tokens": 600,
              "cache_read_input_tokens": 9000
            }
            """.trimIndent(),
        )

        val usage = messageStartUsage.toUsageUpdate(outputTokens = 500)

        assertEquals(10_000, usage.promptTokens)
        assertEquals(9_000, usage.cachedPromptTokens)
        assertTrue(usage.cacheTelemetryAvailable)
        assertEquals(500, usage.completionTokens)
        assertEquals(10_500, usage.tokenCount)
    }

    @Test
    fun `Gemini usage reports prompt cached and candidate tokens`() {
        val metadata = json.decodeFromString<ApiUsageMetadata>(
            """
            {
              "promptTokenCount": 10000,
              "toolUsePromptTokenCount": 700,
              "candidatesTokenCount": 500,
              "cachedContentTokenCount": 9000,
              "thoughtsTokenCount": 100,
              "totalTokenCount": 11300
            }
            """.trimIndent(),
        )

        val usage = metadata.toUsageUpdate()

        assertEquals(10_700, usage.promptTokens)
        assertEquals(9_000, usage.cachedPromptTokens)
        assertTrue(usage.cacheTelemetryAvailable)
        assertEquals(600, usage.completionTokens)
        assertEquals(100, usage.thoughtsTokenCount)
        assertEquals(11_300, usage.tokenCount)
    }
}
