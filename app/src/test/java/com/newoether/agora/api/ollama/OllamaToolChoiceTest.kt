package com.newoether.agora.api.ollama

import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.ToolChoiceDirective
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OllamaToolChoiceTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `Ollama request deliberately ignores hard choice directive`() {
        val config = ProviderConfig(
            apiKey = "",
            modelId = "local-ollama",
            tools = listOf(tool("web_search")),
            toolChoice = ToolChoiceDirective.ForcedFunction("web_search"),
        )
        val request = OllamaChatRequest(
            model = config.modelId,
            messages = emptyList(),
            tools = config.tools,
        )

        val encoded = json.encodeToString(request)

        assertTrue(encoded.contains("\"tools\""))
        assertFalse(encoded.contains("\"tool_choice\""))
        assertFalse(encoded.contains("\"toolConfig\""))
        assertFalse(encoded.contains("\"functionCallingConfig\""))
    }

    private fun tool(name: String) = ToolDefinition(
        function = ToolFunction(
            name = name,
            description = "Use $name",
            parameters = ToolParameters(properties = emptyMap()),
        ),
    )
}
