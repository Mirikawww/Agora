package com.newoether.agora.api.gemini

import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.ToolChoiceDirective
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GeminiToolChoiceTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `forced function serializes as Gemini allowed function`() {
        val config = ProviderConfig(
            apiKey = "key",
            modelId = "gemini-test",
            tools = listOf(tool("web_search")),
            toolChoice = ToolChoiceDirective.ForcedFunction("web_search"),
        )
        val request = ApiGenerateContentRequest(
            contents = emptyList(),
            toolConfig = ApiToolConfig(
                functionCallingConfig = config.toGeminiFunctionCallingConfig(),
            ),
        )

        val encoded = json.parseToJsonElement(json.encodeToString(request)).jsonObject
        val functionCalling = requireNotNull(encoded["toolConfig"])
            .jsonObject["functionCallingConfig"]
            ?.jsonObject
            ?: error("functionCallingConfig missing")

        assertEquals("ANY", functionCalling["mode"]?.toString()?.trim('"'))
        assertEquals(
            listOf("web_search"),
            functionCalling["allowedFunctionNames"]?.jsonArray?.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `Gemini omits forced choice when function is not exposed`() {
        val config = ProviderConfig(
            apiKey = "key",
            modelId = "gemini-test",
            tools = listOf(tool("generate_image")),
            toolChoice = ToolChoiceDirective.ForcedFunction("web_search"),
        )
        val request = ApiGenerateContentRequest(
            contents = emptyList(),
            toolConfig = config.toGeminiFunctionCallingConfig()?.let {
                ApiToolConfig(functionCallingConfig = it)
            },
        )

        assertFalse(json.encodeToString(request).contains("\"functionCallingConfig\""))
    }

    @Test
    fun `Gemini null directive preserves default request bytes`() {
        val config = ProviderConfig(
            apiKey = "key",
            modelId = "gemini-test",
            tools = listOf(tool("web_search")),
        )
        val baseline = ApiGenerateContentRequest(contents = emptyList())
        val mapped = baseline.copy(
            toolConfig = config.toGeminiFunctionCallingConfig()?.let {
                ApiToolConfig(functionCallingConfig = it)
            },
        )

        assertEquals(json.encodeToString(baseline), json.encodeToString(mapped))
        assertFalse(json.encodeToString(mapped).contains("\"functionCallingConfig\""))
    }

    @Test
    fun `Gemini 3 keeps client functions and drops built-ins until opaque context is replayable`() {
        val compatibility = effectiveGeminiToolCompatibility(
            modelId = "gemini-3.5-flash",
            hasFunctionTools = true,
            codeExecutionEnabled = true,
            googleSearchEnabled = true,
        )

        assertEquals(false, compatibility.codeExecutionEnabled)
        assertEquals(false, compatibility.googleSearchEnabled)
        assertEquals(false, compatibility.includeServerSideToolInvocations)
    }

    @Test
    fun `pre-Gemini 3 keeps function tools and drops unsupported built-in combination`() {
        val compatibility = effectiveGeminiToolCompatibility(
            modelId = "gemini-2.5-pro",
            hasFunctionTools = true,
            codeExecutionEnabled = true,
            googleSearchEnabled = true,
        )

        assertEquals(false, compatibility.codeExecutionEnabled)
        assertEquals(false, compatibility.googleSearchEnabled)
        assertEquals(false, compatibility.includeServerSideToolInvocations)
    }

    @Test
    fun `pre-Gemini 3 keeps built-ins when no function tools are present`() {
        val compatibility = effectiveGeminiToolCompatibility(
            modelId = "gemini-2.5-flash",
            hasFunctionTools = false,
            codeExecutionEnabled = false,
            googleSearchEnabled = true,
        )

        assertEquals(false, compatibility.codeExecutionEnabled)
        assertEquals(true, compatibility.googleSearchEnabled)
        assertEquals(false, compatibility.includeServerSideToolInvocations)
    }

    private fun tool(name: String) = ToolDefinition(
        function = ToolFunction(
            name = name,
            description = "Use $name",
            parameters = ToolParameters(properties = emptyMap()),
        ),
    )
}
