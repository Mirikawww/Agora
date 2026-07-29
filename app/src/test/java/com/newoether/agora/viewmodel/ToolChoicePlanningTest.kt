package com.newoether.agora.viewmodel

import com.newoether.agora.api.FunctionToolTransport
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.ToolChoiceDirective
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolChoicePlanningTest {
    @Test
    fun `forced choice is emitted only for a present tool on a choice-capable transport`() {
        val ctx = GenerationContext(forceWebSearch = true)
        val webTool = tool("web_search")

        val directive = forcedToolChoice(
            ctx = ctx,
            transport = FunctionToolTransport.NATIVE_CHOICE,
            wireTools = listOf(webTool),
        )

        assertEquals(ToolChoiceDirective.ForcedFunction("web_search"), directive)
        assertNull(forcedToolChoice(ctx, FunctionToolTransport.NATIVE_AUTO, listOf(webTool)))
        assertNull(forcedToolChoice(ctx, FunctionToolTransport.NATIVE_CHOICE, emptyList()))
    }

    @Test
    fun `tool continuation clears hard choice and first-round MUST instruction`() {
        val firstRound = ProviderConfig(
            apiKey = "key",
            modelId = "model",
            systemPrompt = "Base prompt\n\n$FORCED_WEB_SEARCH_INSTRUCTION",
            toolChoice = ToolChoiceDirective.ForcedFunction("web_search"),
        )

        val continuation = continuationProviderConfig(
            firstRound,
            completedToolNames = setOf("web_search"),
        )

        assertNull(continuation.toolChoice)
        assertEquals("Base prompt", continuation.systemPrompt)
        assertTrue(firstRound.toolChoice is ToolChoiceDirective.ForcedFunction)
    }

    @Test
    fun `prompt fallback remains until its requested tool actually runs`() {
        val firstRound = ProviderConfig(
            apiKey = "key",
            modelId = "model",
            systemPrompt = "Base prompt\n\n$FORCED_WEB_SEARCH_INSTRUCTION",
            toolChoice = ToolChoiceDirective.ForcedFunction("web_search"),
        )

        val unrelatedContinuation = continuationProviderConfig(
            firstRound,
            completedToolNames = setOf("ask_user"),
        )

        assertNull(unrelatedContinuation.toolChoice)
        assertEquals(firstRound.systemPrompt, unrelatedContinuation.systemPrompt)
    }

    private fun tool(name: String) = ToolDefinition(
        function = ToolFunction(
            name = name,
            description = "test",
            parameters = ToolParameters(properties = emptyMap()),
        ),
    )
}
