package com.newoether.agora.api.openai

import com.newoether.agora.api.OpenAiChatRequest
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.ToolChoiceDirective
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OpenAiToolChoiceTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `forced function serializes as OpenAI tool choice`() {
        val config = ProviderConfig(
            apiKey = "key",
            modelId = "gpt-test",
            tools = listOf(tool("web_search")),
            toolChoice = ToolChoiceDirective.ForcedFunction("web_search"),
        )
        val request = OpenAiChatRequest(
            model = config.modelId,
            messages = emptyList(),
            toolChoice = config.toOpenAiToolChoice(),
        )

        val encoded = json.parseToJsonElement(json.encodeToString(request)).jsonObject
        val toolChoice = requireNotNull(encoded["tool_choice"]).jsonObject

        assertEquals("function", toolChoice["type"]?.toString()?.trim('"'))
        assertEquals(
            "web_search",
            toolChoice["function"]?.jsonObject?.get("name")?.toString()?.trim('"'),
        )
    }

    @Test
    fun `OpenAI omits forced choice when function is not exposed`() {
        val config = ProviderConfig(
            apiKey = "key",
            modelId = "gpt-test",
            tools = listOf(tool("generate_image")),
            toolChoice = ToolChoiceDirective.ForcedFunction("web_search"),
        )
        val request = OpenAiChatRequest(
            model = config.modelId,
            messages = emptyList(),
            tools = config.tools,
            toolChoice = config.toOpenAiToolChoice(),
        )

        assertFalse(json.encodeToString(request).contains("\"tool_choice\""))
    }

    @Test
    fun `OpenAI null directive preserves default request bytes`() {
        val config = ProviderConfig(
            apiKey = "key",
            modelId = "gpt-test",
            tools = listOf(tool("web_search")),
        )
        val baseline = OpenAiChatRequest(
            model = config.modelId,
            messages = emptyList(),
            tools = config.tools,
        )
        val mapped = baseline.copy(toolChoice = config.toOpenAiToolChoice())

        assertEquals(json.encodeToString(baseline), json.encodeToString(mapped))
        assertFalse(json.encodeToString(mapped).contains("\"tool_choice\""))
    }

    private fun tool(name: String) = ToolDefinition(
        function = ToolFunction(
            name = name,
            description = "Use $name",
            parameters = ToolParameters(properties = emptyMap()),
        ),
    )
}
