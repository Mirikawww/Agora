package com.newoether.agora.api.openai

import com.newoether.agora.api.OpenAiChatRequest
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiPromptCachingTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `official OpenAI request carries opaque stable capability fingerprint`() {
        val secret = "private-memory-do-not-send"
        val alphaSchema = JsonObject(
            linkedMapOf(
                "required" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("query"))),
                "type" to JsonPrimitive("object"),
            )
        )
        val betaSchema = JsonObject(
            linkedMapOf(
                "type" to JsonPrimitive("object"),
                "required" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("query"))),
            )
        )
        val first = providerConfig(
            secret = secret,
            tools = listOf(tool("beta", betaSchema), tool("alpha", alphaSchema)),
        )
        val second = providerConfig(
            secret = "different-user-prompt",
            tools = listOf(tool("alpha", betaSchema), tool("beta", alphaSchema)),
        )

        val firstKey = openAiPromptCacheKey(first)
        val secondKey = openAiPromptCacheKey(second)
        val request = OpenAiChatRequest(model = first.modelId, messages = emptyList())
            .withOfficialPromptCaching(first)
        val encoded = json.encodeToString(request)

        assertEquals(firstKey, secondKey)
        assertTrue(firstKey.matches(Regex("[0-9a-f]{64}")))
        assertFalse(firstKey.contains(secret))
        assertFalse(encoded.contains(secret))
        assertTrue(encoded.contains("\"prompt_cache_key\":\"$firstKey\""))
    }

    @Test
    fun `OpenAI compatible relay never receives official cache key`() {
        val relayConfig = providerConfig(
            secret = "relay-system",
            tools = emptyList(),
            baseUrl = "https://relay.example/v1",
        )
        val request = OpenAiChatRequest(model = relayConfig.modelId, messages = emptyList())
            .withOfficialPromptCaching(relayConfig)
        val encoded = json.encodeToString(request)

        assertFalse(isOfficialOpenAiEndpoint(relayConfig.baseUrl))
        assertNull(request.promptCacheKey)
        assertFalse(encoded.contains("prompt_cache_key"))
    }

    @Test
    fun `default OpenAI-compatible request schema omits cache key`() {
        val encoded = json.encodeToString(
            OpenAiChatRequest(model = "compatible-model", messages = emptyList())
        )

        assertFalse(encoded.contains("prompt_cache_key"))
    }

    private fun providerConfig(
        secret: String,
        tools: List<ToolDefinition>,
        baseUrl: String? = "https://api.openai.com/v1",
    ) = ProviderConfig(
        apiKey = "sk-secret-key",
        modelId = "gpt-5.4",
        systemPrompt = secret,
        tools = tools,
        baseUrl = baseUrl,
        streamTag = "private-conversation-id",
    )

    private fun tool(name: String, schema: JsonObject) = ToolDefinition(
        function = ToolFunction(
            name = name,
            description = "Use $name",
            parameters = ToolParameters(properties = emptyMap(), rawSchema = schema),
        )
    )
}
