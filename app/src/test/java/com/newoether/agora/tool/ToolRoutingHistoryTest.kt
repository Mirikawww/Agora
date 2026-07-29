package com.newoether.agora.tool

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolRoutingHistoryTest {
    @Test
    fun `recent successful names include broker target but exclude denied and failed calls`() {
        val history = listOf(
            toolMessage(
                name = "notion_create_page",
                args = """{"title":"Done"}""",
                result = """{"id":"page-1"}""",
            ),
            toolMessage(
                name = "github_request",
                args = """{"method":"DELETE"}""",
                result = "MCP tool call denied by user",
            ),
            toolMessage(
                name = McpDeferredToolProvider.TOOL_BROKER,
                args = """{"action":"invoke","name":"todoist_add_tasks","arguments":{"content":"Next"}}""",
                result = """{"id":"task-1"}""",
            ),
            toolMessage(
                name = "execute_shell_command",
                args = """{"command":"false"}""",
                result = "Error executing tool 'execute_shell_command': failed",
            ),
            toolMessage(
                name = McpToolProvider.RESULT_PAGE,
                args = """{"cursor":"opaque"}""",
                result = """{"chunk":"continued"}""",
            ),
        )

        val names = ToolRoutingHistory.recentSuccessfulToolNames(history)

        assertEquals(
            linkedSetOf("todoist_add_tasks", "notion_create_page"),
            names,
        )
    }

    private fun toolMessage(name: String, args: String, result: String) = ChatMessage(
        text = "",
        participant = Participant.MODEL,
        segments = listOf(
            MessageSegment(
                type = "tool",
                toolName = name,
                toolArgs = args,
                toolResult = result,
            ),
        ),
    )
}
