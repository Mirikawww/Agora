package com.newoether.agora.ui.chat.message

import com.newoether.agora.model.MessageSegment
import org.junit.Assert.assertEquals
import org.junit.Test

class BrokerToolIdentityTest {
    @Test
    fun `broker invocation exposes concrete connector identity to UI`() {
        val segment = MessageSegment(
            type = "tool",
            toolName = "agora_capabilities",
            toolArgs = """
                {
                  "action": "invoke",
                  "name": "todoist_add_tasks",
                  "arguments": {"content": "Ship report"}
                }
            """.trimIndent(),
        )

        assertEquals("todoist_add_tasks", segment.effectiveToolName())
    }

    @Test
    fun `broker search keeps broker identity`() {
        val segment = MessageSegment(
            type = "tool",
            toolName = "agora_capabilities",
            toolArgs = """{"action":"search","query":"tasks"}""",
        )

        assertEquals("agora_capabilities", segment.effectiveToolName())
    }
}
