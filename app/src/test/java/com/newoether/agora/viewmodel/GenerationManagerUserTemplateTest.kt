package com.newoether.agora.viewmodel

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.tool.AskToolProvider
import com.newoether.agora.tool.McpDeferredToolProvider
import com.newoether.agora.tool.WebSearchToolProvider
import com.newoether.agora.util.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationManagerUserTemplateTest {
    @Test
    fun applyUserTemplateToMessages_wrapsOnlyNormalUserMessages() {
        val messages = listOf(
            ChatMessage(id = "u1", text = "hello", participant = Participant.USER),
            ChatMessage(id = Constants.RESULT_MSG_PREFIX + "r1", text = "tool output", participant = Participant.USER),
            ChatMessage(id = Constants.TOOL_MSG_PREFIX + "t1", text = "", participant = Participant.MODEL),
            ChatMessage(id = "m1", text = "assistant", participant = Participant.MODEL)
        )

        val result = applyUserTemplateToMessages(messages, "<wrap>", "</wrap>")

        assertEquals("<wrap>hello</wrap>", result[0].text)
        assertEquals("tool output", result[1].text)
        assertEquals("", result[2].text)
        assertEquals("assistant", result[3].text)
    }

    @Test
    fun forcedWebContext_keepsWebToolsDirectAcrossManagerRoutingBoundary() {
        val ctx = GenerationContext(
            webSearchEnabled = true,
            forceWebSearch = true,
            askToolEnabled = true,
        )
        val longTail = (1..70).map { index ->
            ToolDefinition(
                function = ToolFunction(
                    name = "connector_tool_$index",
                    description = "Long-tail connector capability.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "query" to ToolProperty("string", "The request."),
                        ),
                        required = listOf("query"),
                    ),
                ),
            )
        }

        val forcedNames = forcedDirectToolNames(ctx)
        val plan = McpDeferredToolProvider.plan(
            tools = WebSearchToolProvider().definitions(ctx) +
                AskToolProvider().definitions(ctx) +
                longTail,
            contextTokens = 200_000,
            currentText = "你好",
            forcedDirectToolNames = forcedNames,
        )

        assertEquals(setOf("web_search", "web_fetch"), forcedNames)
        assertEquals(
            setOf("web_search", "web_fetch"),
            plan.inlineTools.map { it.function.name }.toSet(),
        )
        assertTrue(plan.deferredTools.any { it.function.name == "ask_user" })
        assertTrue(plan.usesBroker)
    }
}
