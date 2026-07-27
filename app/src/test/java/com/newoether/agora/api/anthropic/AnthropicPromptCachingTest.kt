package com.newoether.agora.api.anthropic

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicPromptCachingTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `official request caches complete stable tool prefix`() {
        val tools = listOf(tool("calendar"), tool("drive"))
        val control = AnthropicCacheControl()
        val cachedTools = tools.withStablePrefixCacheBreakpoint(control)
        val request = AnthropicRequest(
            model = "claude-sonnet-4-6",
            messages = emptyList(),
            tools = cachedTools,
            cacheControl = control,
        )
        val encoded = json.parseToJsonElement(json.encodeToString(request)).jsonObject
        val encodedTools = requireNotNull(encoded["tools"]).jsonArray

        assertEquals(listOf("calendar", "drive"), cachedTools.map { it.name })
        assertNull(cachedTools.first().cacheControl)
        assertEquals("ephemeral", cachedTools.last().cacheControl?.type)
        assertFalse(encodedTools.first().jsonObject.containsKey("cache_control"))
        assertEquals(
            "ephemeral",
            encodedTools.last().jsonObject["cache_control"]
                ?.jsonObject
                ?.get("type")
                ?.toString()
                ?.trim('"'),
        )
        assertEquals(
            "ephemeral",
            encoded["cache_control"]?.jsonObject?.get("type")?.toString()?.trim('"'),
        )
    }

    @Test
    fun `Anthropic compatible relay omits cache controls`() {
        val tools = listOf(tool("calendar"), tool("drive"))
        val uncachedTools = tools.withStablePrefixCacheBreakpoint(cacheControl = null)
        val request = AnthropicRequest(
            model = "compatible-claude",
            messages = emptyList(),
            tools = uncachedTools,
            cacheControl = null,
        )
        val encoded = json.encodeToString(request)

        assertFalse(isOfficialAnthropicEndpoint("https://relay.example/v1"))
        assertSame(tools, uncachedTools)
        assertTrue(uncachedTools.all { it.cacheControl == null })
        assertFalse(encoded.contains("cache_control"))
    }

    @Test
    fun `official endpoint recognition accepts default and canonical host only`() {
        assertTrue(isOfficialAnthropicEndpoint(null))
        assertTrue(isOfficialAnthropicEndpoint("https://api.anthropic.com/v1"))
        assertFalse(isOfficialAnthropicEndpoint("https://api.anthropic.com.evil.example/v1"))
    }

    private fun tool(name: String) = AnthropicTool(
        name = name,
        description = "Use $name",
        inputSchema = JsonObject(emptyMap()),
    )
}
