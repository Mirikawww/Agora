package com.newoether.agora.api.ollama

import com.newoether.agora.api.OpenAiFunctionCall
import com.newoether.agora.api.OpenAiToolCall
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OllamaProviderTest {
    @Test
    fun `tool result history uses Ollama tool role and tool name`() {
        val message = ChatMessage(
            id = "${Constants.RESULT_MSG_PREFIX}1",
            text = "",
            participant = Participant.USER,
            segments = listOf(
                MessageSegment(
                    type = "tool",
                    toolName = "web_search",
                    toolResult = """{"answer":"done"}""",
                ),
            ),
        )

        val result = message.toOllamaToolResultMessages().single()

        assertEquals("tool", result.role)
        assertEquals("web_search", result.toolName)
        assertEquals("""{"answer":"done"}""", result.content)
    }

    @Test
    fun `parallel Ollama calls without ids receive distinct deterministic ids`() {
        val calls = listOf(
            openAiToolCall("search", "one"),
            openAiToolCall("search", "two"),
        ).toOllamaStreamToolCalls()

        assertEquals(2, calls.size)
        assertNotEquals(calls[0].id, calls[1].id)
        assertTrue(calls.all { it.id.isNotBlank() })
    }

    @Test
    fun `Ollama usage reports prompt and completion split`() {
        val usage = OllamaStreamResponse(
            done = true,
            promptEvalCount = 120,
            evalCount = 30,
        ).toUsageUpdate()

        assertEquals(150, usage.tokenCount)
        assertEquals(120, usage.promptTokens)
        assertEquals(30, usage.completionTokens)
    }

    private fun openAiToolCall(name: String, value: String) = OpenAiToolCall(
        function = OpenAiFunctionCall(
            name = name,
            arguments = buildJsonObject { put("query", value) },
        ),
    )
}
