package com.newoether.agora.tool

import com.newoether.agora.api.DeferPolicy
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the defer budget, whose predecessor silently never fired: it counted tools instead of
 * measuring schema cost and read a *message count* setting as if it were a token window.
 */
class DeferredToolBudgetTest {

    /** A tool whose description is padded to a predictable token cost. */
    private fun tool(
        name: String,
        descriptionWords: Int = 40,
        defer: DeferPolicy = DeferPolicy.AUTO,
    ) = ToolDefinition(
        function = ToolFunction(
            name = name,
            description = List(descriptionWords) { "description" }.joinToString(" "),
            parameters = ToolParameters(
                properties = mapOf(
                    "alpha" to ToolProperty("string", "A reasonably wordy parameter description."),
                    "beta" to ToolProperty("string", "Another reasonably wordy parameter description."),
                ),
                required = listOf("alpha"),
            ),
        ),
        defer = defer,
    )

    @Test
    fun `small pool stays inline because meta-tools would cost more`() {
        // 3 tools is below MIN_AUTO_DEFER_COUNT (5): the discovery round-trip is a net loss.
        val tools = listOf(tool("a"), tool("b"), tool("c"))

        val deferred = McpDeferredToolProvider.selectDeferred(tools, contextTokens = 8_000)

        assertEquals(emptySet<String>(), deferred)
    }

    @Test
    fun `large pool on a small context window is deferred`() {
        val tools = (1..30).map { tool("tool_$it") }

        val deferred = McpDeferredToolProvider.selectDeferred(tools, contextTokens = 8_000)

        assertEquals(tools.size, deferred.size)
    }

    @Test
    fun `same pool stays inline on a large context window`() {
        // Identical pool, 1M-token window: 10% budget comfortably covers it, so inlining wins.
        val tools = (1..30).map { tool("tool_$it") }

        val deferred = McpDeferredToolProvider.selectDeferred(tools, contextTokens = 1_000_000)

        assertEquals(
            "budget scales with the real window, so a big model should not defer",
            emptySet<String>(),
            deferred,
        )
    }

    @Test
    fun `NEVER tools are never deferred even when the pool overflows`() {
        val tools = (1..30).map { tool("auto_$it") } +
            tool("approval_gated", defer = DeferPolicy.NEVER)

        val deferred = McpDeferredToolProvider.selectDeferred(tools, contextTokens = 8_000)

        assertTrue("auto tools should defer", deferred.contains("auto_1"))
        assertTrue("NEVER must stay inline", !deferred.contains("approval_gated"))
    }

    @Test
    fun `ALWAYS tools are deferred regardless of budget`() {
        val tools = listOf(tool("always_me", defer = DeferPolicy.ALWAYS), tool("keep_me"))

        val deferred = McpDeferredToolProvider.selectDeferred(tools, contextTokens = 1_000_000)

        assertEquals(setOf("always_me"), deferred)
    }

    @Test
    fun `provider cap forces deferral even when the token budget allows it`() {
        // Over MAX_INLINE_TOOLS (64) with a huge window: Anthropic-style caps still apply.
        val tools = (1..70).map { tool("tool_$it", descriptionWords = 1) }

        val deferred = McpDeferredToolProvider.selectDeferred(tools, contextTokens = 1_000_000)

        assertEquals(tools.size, deferred.size)
    }

    @Test
    fun `unknown context window falls back instead of collapsing the budget to zero`() {
        // contextTokens = 0 means models.dev had no entry. Reading a message-count setting here
        // was the original bug; the fallback must be a sane token figure.
        val tools = (1..5).map { tool("tool_$it", descriptionWords = 1) }

        val deferred = McpDeferredToolProvider.selectDeferred(tools, contextTokens = 0)

        assertEquals(
            "a tiny pool must not defer just because the window is unknown",
            emptySet<String>(),
            deferred,
        )
    }

    @Test
    fun `estimate counts name description and schema`() {
        val cost = McpDeferredToolProvider.estimateSchemaTokens(listOf(tool("x")))

        assertTrue("expected a non-trivial estimate, got $cost", cost > 20)
    }

    @Test
    fun `empty pool defers nothing`() {
        assertEquals(
            emptySet<String>(),
            McpDeferredToolProvider.selectDeferred(emptyList(), contextTokens = 8_000),
        )
    }
}
