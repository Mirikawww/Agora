package com.newoether.agora.viewmodel

import com.newoether.agora.api.GenerationError
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.tool.extractWebSearchQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationCompletionTest {
    @Test
    fun toolResultWithoutAssistantAnswer_isNotComplete() {
        val segments = listOf(
            MessageSegment(
                type = "tool",
                toolName = "search_conversations",
                toolResult = "{\"error\":\"no_results\"}",
            ),
        )

        assertFalse(
            hasFinalAssistantResponse(
                text = "",
                segments = segments,
                generatedImages = emptyList(),
                usedTools = true,
                answeredAfterLastTool = false,
            ),
        )
    }

    @Test
    fun preToolPreambleWithoutPostToolAnswer_isNotComplete() {
        assertFalse(
            hasFinalAssistantResponse(
                text = "I will search.",
                segments = emptyList(),
                generatedImages = emptyList(),
                usedTools = true,
                answeredAfterLastTool = false,
            ),
        )
    }

    @Test
    fun assistantTextAfterTool_isComplete() {
        assertTrue(
            hasFinalAssistantResponse(
                text = "No matching conversation was found.",
                segments = emptyList(),
                generatedImages = emptyList(),
                usedTools = true,
                answeredAfterLastTool = true,
            ),
        )
    }

    @Test
    fun generatedImage_isComplete() {
        assertTrue(
            hasFinalAssistantResponse(
                text = "",
                segments = emptyList(),
                generatedImages = listOf("image.png"),
                usedTools = true,
                answeredAfterLastTool = false,
            ),
        )
    }

    @Test
    fun emptyWebSearchArguments_remainEmptyInsteadOfUsingUserText() {
        assertNull(extractWebSearchQuery("{}"))
    }

    @Test
    fun emptyPostToolResponse_isNotCompleteWithoutRetryFallback() {
        assertFalse(
            hasFinalAssistantResponse(
                text = "",
                segments = listOf(MessageSegment(type = "tool", toolResult = "{}")),
                generatedImages = emptyList(),
                usedTools = true,
                answeredAfterLastTool = false,
            ),
        )
    }

    @Test
    fun formatGenerationDiagnostic_returnsOnlyErrorDetail() {
        val result = formatGenerationDiagnostic(
            GenerationError.Api("bad_key", null, "Invalid API key"),
        )

        assertEquals("bad_key: Invalid API key", result)
        assertFalse(result.contains("Provider"))
        assertFalse(result.contains("Model"))
        assertFalse(result.contains("API error"))
    }
}
