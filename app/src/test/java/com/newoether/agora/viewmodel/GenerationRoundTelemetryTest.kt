package com.newoether.agora.viewmodel

import com.newoether.agora.tool.McpDeferredToolProvider
import com.newoether.agora.tool.McpToolProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GenerationRoundTelemetryTest {
    @Test
    fun `broker telemetry retains only the operation name`() {
        val arguments = """{"action":"inspect","name":"secret_tool","arguments":{"token":"secret"}}"""

        assertEquals(
            McpDeferredToolProvider.ACTION_INSPECT,
            brokerAction(McpDeferredToolProvider.TOOL_BROKER, arguments),
        )
        assertEquals(
            McpDeferredToolProvider.ACTION_INVOKE,
            brokerAction(McpDeferredToolProvider.LEGACY_INVOKE, arguments),
        )
    }

    @Test
    fun `ordinary tools and malformed broker calls add no broker action`() {
        assertNull(brokerAction("notion_search", """{"action":"invoke"}"""))
        assertNull(brokerAction(McpDeferredToolProvider.TOOL_BROKER, "not-json"))
        assertNull(
            brokerAction(
                McpDeferredToolProvider.TOOL_BROKER,
                """{"action":"sensitive free-form text that must not be retained"}""",
            ),
        )
    }

    @Test
    fun `MCP pager reports original size without retaining result text`() {
        val page = """
            {
              "_agora_mcp_result": {
                "original_chars": 42000,
                "source_tool": "mcp_workspace_search",
                "offset": 0,
                "next_cursor": "opaque"
              },
              "chunk": "small"
            }
        """.trimIndent()

        val continuation = page.replace("\"offset\": 0", "\"offset\": 12000")

        assertEquals(
            42_000,
            originalToolResultChars("mcp_workspace_search", page),
        )
        assertEquals(
            42_000,
            originalToolResultChars(McpDeferredToolProvider.TOOL_BROKER, page),
        )
        assertEquals(
            0,
            originalToolResultChars(McpToolProvider.RESULT_PAGE, continuation),
        )
        assertEquals(5, originalToolResultChars("ordinary_tool", "plain"))
        // An ordinary tool cannot forge pager metadata to suppress or inflate telemetry.
        assertEquals(page.length, originalToolResultChars("ordinary_tool", page))
    }
}
