package com.newoether.agora.viewmodel

import com.newoether.agora.tool.McpDeferredToolProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Promotion keys off the capability a call actually exercised, not the wire tool name.
 *
 * A broker invoke arrives as `agora_capabilities` with the real capability inside its arguments.
 * Keying off the wire name would only ever promote the broker, which is already direct.
 */
class InvokedCapabilityNameTest {

    @Test
    fun `a broker invoke resolves to the concrete capability`() {
        assertEquals(
            "todoist_find_tasks",
            invokedCapabilityName(
                McpDeferredToolProvider.TOOL_BROKER,
                """{"action":"invoke","name":"todoist_find_tasks","arguments":{"filter":"today"}}""",
            ),
        )
    }

    @Test
    fun `browsing the catalogue promotes nothing`() {
        // Search and inspect are not evidence the model needs the capability; promoting on them
        // would put schemas on the wire that were never called.
        assertNull(
            invokedCapabilityName(
                McpDeferredToolProvider.TOOL_BROKER,
                """{"action":"search","query":"tasks due today"}""",
            ),
        )
        assertNull(
            invokedCapabilityName(
                McpDeferredToolProvider.TOOL_BROKER,
                """{"action":"inspect","name":"todoist_find_tasks"}""",
            ),
        )
    }

    @Test
    fun `legacy broker tools follow the same rule`() {
        assertEquals(
            "todoist_find_tasks",
            invokedCapabilityName(
                McpDeferredToolProvider.LEGACY_INVOKE,
                """{"name":"todoist_find_tasks","arguments":{}}""",
            ),
        )
        assertNull(
            invokedCapabilityName(McpDeferredToolProvider.LEGACY_SEARCH, """{"query":"tasks"}"""),
        )
        assertNull(
            invokedCapabilityName(
                McpDeferredToolProvider.LEGACY_INSPECT,
                """{"name":"todoist_find_tasks"}""",
            ),
        )
    }

    @Test
    fun `a direct tool call resolves to itself`() {
        assertEquals("web_search", invokedCapabilityName("web_search", """{"query":"kotlin"}"""))
        assertEquals(
            "todoist_find_tasks",
            invokedCapabilityName("todoist_find_tasks", """{"filter":"today"}"""),
        )
    }

    @Test
    fun `malformed or nameless broker arguments resolve to nothing`() {
        assertNull(invokedCapabilityName(McpDeferredToolProvider.TOOL_BROKER, "not json at all"))
        assertNull(invokedCapabilityName(McpDeferredToolProvider.TOOL_BROKER, ""))
        assertNull(
            invokedCapabilityName(McpDeferredToolProvider.TOOL_BROKER, """{"action":"invoke"}"""),
        )
        assertNull(
            invokedCapabilityName(
                McpDeferredToolProvider.TOOL_BROKER,
                """{"action":"invoke","name":"   "}""",
            ),
        )
    }

    @Test
    fun `an invoke action is matched case-insensitively`() {
        assertEquals(
            "todoist_find_tasks",
            invokedCapabilityName(
                McpDeferredToolProvider.TOOL_BROKER,
                """{"action":"INVOKE","name":"todoist_find_tasks"}""",
            ),
        )
    }
}
