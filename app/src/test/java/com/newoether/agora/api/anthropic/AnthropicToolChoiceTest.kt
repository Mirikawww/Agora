package com.newoether.agora.api.anthropic

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

class AnthropicToolChoiceTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `forced function serializes as Anthropic tool choice`() {
        val config = providerConfig(
            tools = listOf(tool("web_search")),
            toolChoice = ToolChoiceDirective.ForcedFunction("web_search"),
        )
        val request = AnthropicRequest(
            model = config.modelId,
            messages = emptyList(),
            toolChoice = config.toAnthropicToolChoice(thinking = null),
        )

        val encoded = json.parseToJsonElement(json.encodeToString(request)).jsonObject
        val toolChoice = requireNotNull(encoded["tool_choice"]).jsonObject

        assertEquals("tool", toolChoice["type"]?.toString()?.trim('"'))
        assertEquals("web_search", toolChoice["name"]?.toString()?.trim('"'))
    }

    @Test
    fun `Anthropic omits forced choice while thinking is enabled`() {
        val config = providerConfig(
            tools = listOf(tool("web_search")),
            toolChoice = ToolChoiceDirective.ForcedFunction("web_search"),
        )
        val thinking = AnthropicThinking()
        val request = AnthropicRequest(
            model = config.modelId,
            messages = emptyList(),
            thinking = thinking,
            toolChoice = config.toAnthropicToolChoice(thinking),
        )

        val encoded = json.encodeToString(request)

        assertFalse(encoded.contains("\"tool_choice\""))
    }

    @Test
    fun `Anthropic adaptive thinking keeps forced choice`() {
        val config = providerConfig(
            tools = listOf(tool("web_search")),
            toolChoice = ToolChoiceDirective.ForcedFunction("web_search"),
        ).copy(modelId = "claude-opus-4-8")

        val choice = config.toAnthropicToolChoice(
            thinking = AnthropicThinking(type = "adaptive"),
        )

        assertEquals("web_search", choice?.name)
    }

    @Test
    fun `Anthropic Mythos Preview omits forced choice even with adaptive thinking`() {
        val config = providerConfig(
            tools = listOf(tool("web_search")),
            toolChoice = ToolChoiceDirective.ForcedFunction("web_search"),
        ).copy(modelId = "claude-mythos-preview")

        val choice = config.toAnthropicToolChoice(
            thinking = AnthropicThinking(type = "adaptive"),
        )

        assertEquals(null, choice)
    }

    @Test
    fun `Anthropic Mythos 5 keeps forced choice with adaptive thinking`() {
        val config = providerConfig(
            tools = listOf(tool("web_search")),
            toolChoice = ToolChoiceDirective.ForcedFunction("web_search"),
        ).copy(modelId = "claude-mythos-5")

        val choice = config.toAnthropicToolChoice(
            thinking = AnthropicThinking(type = "adaptive"),
        )

        assertEquals("web_search", choice?.name)
    }

    @Test
    fun `adaptive-only model ignores manual budget instead of sending invalid enabled mode`() {
        val thinking = providerConfig(emptyList(), null).copy(
            modelId = "claude-opus-5",
            thinkingEnabled = true,
            thinkingBudgetEnabled = true,
            thinkingBudgetTokens = 8_000,
        ).toAnthropicThinking()

        assertEquals("adaptive", thinking?.type)
        assertEquals(null, thinking?.budgetTokens)
    }

    @Test
    fun `manual-capable model retains explicit thinking budget`() {
        val thinking = providerConfig(emptyList(), null).copy(
            modelId = "claude-opus-4-6",
            thinkingEnabled = true,
            thinkingBudgetEnabled = true,
            thinkingBudgetTokens = 8_000,
        ).toAnthropicThinking()

        assertEquals("enabled", thinking?.type)
        assertEquals(8_000, thinking?.budgetTokens)
    }

    @Test
    fun `Opus 5 explicitly disables its default thinking when setting is off`() {
        val thinking = providerConfig(emptyList(), null).copy(
            modelId = "claude-opus-5",
            thinkingEnabled = false,
        ).toAnthropicThinking()

        assertEquals("disabled", thinking?.type)
        assertEquals(null, thinking?.budgetTokens)
    }

    @Test
    fun `Mythos 5 omits unsupported disabled mode when setting is off`() {
        val thinking = providerConfig(emptyList(), null).copy(
            modelId = "claude-mythos-5",
            thinkingEnabled = false,
        ).toAnthropicThinking()

        assertEquals(null, thinking)
    }

    @Test
    fun `Anthropic omits forced choice when function is not exposed`() {
        val config = providerConfig(
            tools = listOf(tool("generate_image")),
            toolChoice = ToolChoiceDirective.ForcedFunction("web_search"),
        )
        val request = AnthropicRequest(
            model = config.modelId,
            messages = emptyList(),
            toolChoice = config.toAnthropicToolChoice(thinking = null),
        )

        assertFalse(json.encodeToString(request).contains("\"tool_choice\""))
    }

    @Test
    fun `Anthropic null directive preserves default request bytes`() {
        val config = providerConfig(
            tools = listOf(tool("web_search")),
            toolChoice = null,
        )
        val baseline = AnthropicRequest(
            model = config.modelId,
            messages = emptyList(),
        )
        val mapped = baseline.copy(toolChoice = config.toAnthropicToolChoice(thinking = null))

        assertEquals(json.encodeToString(baseline), json.encodeToString(mapped))
        assertFalse(json.encodeToString(mapped).contains("\"tool_choice\""))
    }

    private fun providerConfig(
        tools: List<ToolDefinition>,
        toolChoice: ToolChoiceDirective?,
    ) = ProviderConfig(
        apiKey = "key",
        modelId = "claude-test",
        tools = tools,
        toolChoice = toolChoice,
    )

    private fun tool(name: String) = ToolDefinition(
        function = ToolFunction(
            name = name,
            description = "Use $name",
            parameters = ToolParameters(properties = emptyMap()),
        ),
    )
}
