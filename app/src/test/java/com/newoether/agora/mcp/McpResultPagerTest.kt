package com.newoether.agora.mcp

import com.newoether.agora.util.ToolLoopBudget
import com.newoether.agora.tool.McpToolProvider
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpResultPagerTest {
    @Test
    fun `oversized MCP result stays valid and every page reconstructs the exact value`() {
        val pager = McpResultPager(initialMaxChars = 700)
        val raw = """{"items":[""" + (0 until 500).joinToString(",") {
            """"entry-$it-${"x".repeat(8)}""""
        } + """]}"""

        var response = pager.budget("request-a", "mcp_workspace_search", raw)
        val recovered = StringBuilder()
        var pages = 0
        while (true) {
            assertTrue(response.length <= 700)
            val root = Json.parseToJsonElement(response).jsonObject
            recovered.append(root.getValue("chunk").jsonPrimitive.content)
            val meta = root.getValue("_agora_mcp_result").jsonObject
            assertEquals("current_generation", meta.getValue("cursor_lifecycle").jsonPrimitive.content)
            val next = meta["next_cursor"]?.jsonPrimitive?.contentOrNull ?: break
            response = pager.page("request-a", next, maxChars = 700)
            assertTrue(++pages < 100)
        }

        assertEquals(raw, recovered.toString())
    }

    @Test
    fun `cursor is request scoped and clear invalidates it`() {
        val pager = McpResultPager(initialMaxChars = 600)
        val first = Json.parseToJsonElement(
            pager.budget("request-a", "mcp_large", "x".repeat(5_000)),
        ).jsonObject
        val cursor = first
            .getValue("_agora_mcp_result")
            .jsonObject
            .getValue("next_cursor")
            .jsonPrimitive
            .content

        val crossRequest = Json.parseToJsonElement(
            pager.page("request-b", cursor, maxChars = 600),
        ).jsonObject
        assertTrue(crossRequest.getValue("isError").jsonPrimitive.content.toBoolean())

        pager.clearRequest("request-a")
        val expired = Json.parseToJsonElement(
            pager.page("request-a", cursor, maxChars = 600),
        ).jsonObject
        assertTrue(expired.getValue("isError").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `small MCP result is unchanged and creates no cursor`() {
        val pager = McpResultPager(initialMaxChars = 600)
        val raw = """{"ok":true}"""

        val result = pager.budget("request-a", "mcp_small", raw)

        assertEquals(raw, result)
        assertFalse(result.contains("next_cursor"))
        assertNotNull(Json.parseToJsonElement(result))
    }

    @Test
    fun `forged offsets and per-request capacity fail explicitly without affecting another request`() {
        val pager = McpResultPager(initialMaxChars = 600)
        val firstA = Json.parseToJsonElement(
            pager.budget("request-a", "mcp_large", "a".repeat(2_000)),
        ).jsonObject
        val cursorA = firstA
            .getValue("_agora_mcp_result")
            .jsonObject
            .getValue("next_cursor")
            .jsonPrimitive
            .content
        val forged = cursorA.substringBeforeLast(':') +
            ":" +
            (cursorA.substringAfterLast(':').toInt() + 1)
        assertTrue(
            Json.parseToJsonElement(pager.page("request-a", forged, 600))
                .jsonObject
                .getValue("isError")
                .jsonPrimitive
                .content
                .toBoolean(),
        )

        val firstB = Json.parseToJsonElement(
            pager.budget("request-b", "mcp_large", "b".repeat(2_000)),
        ).jsonObject
        val cursorB = firstB
            .getValue("_agora_mcp_result")
            .jsonObject
            .getValue("next_cursor")
            .jsonPrimitive
            .content
        pager.clearRequest("request-a")
        assertTrue(
            Json.parseToJsonElement(pager.page("request-b", cursorB, 600))
                .jsonObject
                .containsKey("chunk"),
        )

        repeat(7) { index ->
            pager.budget("request-b", "mcp_$index", "x".repeat(700))
        }
        val overCapacity = Json.parseToJsonElement(
            pager.budget("request-b", "mcp_overflow", "y".repeat(700)),
        ).jsonObject
        assertEquals(
            "mcp_result_cache_limit",
            overCapacity.getValue("error").jsonPrimitive.content,
        )
    }

    @Test
    fun `default tool loop keeps an MCP page intact`() {
        val pager = McpResultPager()
        val page = pager.budget(
            "request-a",
            "mcp_large",
            """{"payload":"${"x".repeat(30_000)}"}""",
        )
        val budget = ToolLoopBudget()
        budget.startProviderRound()

        val injected = budget.budgetRound(listOf(page)).results.single()

        assertTrue(page.length <= 12_000)
        assertEquals(page, injected)
        assertTrue(
            Json.parseToJsonElement(injected)
                .jsonObject
                .getValue("_agora_mcp_result")
                .jsonObject
                .containsKey("next_cursor"),
        )
    }

    @Test
    fun `shared batch allowance prevents MCP cursor pages from being clipped later`() {
        val pager = McpResultPager()
        val budget = ToolLoopBudget()
        budget.startProviderRound()
        val perResult = budget.maxResultCharsPerPending(pendingResults = 6)
        val pages = (0 until 6).map { index ->
            pager.budget(
                requestId = "request-a",
                sourceTool = "mcp_large_$index",
                value = """{"payload":"${index}-${"x".repeat(30_000)}"}""",
                maxChars = perResult,
            )
        }

        val injected = budget.budgetRound(pages).results

        assertTrue(pages.all { it.length <= perResult })
        assertEquals(pages, injected)
        injected.forEach { Json.parseToJsonElement(it).jsonObject }
    }

    @Test
    fun `complete tail is preferred even when the almost-complete envelope is larger`() {
        val pager = McpResultPager(initialMaxChars = 600)
        val raw = "x".repeat(900)
        val first = Json.parseToJsonElement(
            pager.budget("request-a", "mcp_large", raw),
        ).jsonObject
        val cursor = first
            .getValue("_agora_mcp_result")
            .jsonObject
            .getValue("next_cursor")
            .jsonPrimitive
            .content
        val roomyTail = pager.page("request-a", cursor, maxChars = 12_000)
        val exactTailBudget = roomyTail.length.coerceAtLeast(McpResultPager.MIN_PAGE_CHARS)

        val exactTail = Json.parseToJsonElement(
            pager.page("request-a", cursor, maxChars = exactTailBudget),
        ).jsonObject

        assertTrue(exactTail.getValue("_agora_mcp_result").jsonObject
            .getValue("complete").jsonPrimitive.content.toBoolean())
        assertFalse(exactTail.getValue("_agora_mcp_result").jsonObject.containsKey("next_cursor"))
    }

    @Test
    fun `sub-envelope allowance fails without advancing a cursor`() {
        val pager = McpResultPager()

        val response = Json.parseToJsonElement(
            pager.page("request-a", "unknown:123", maxChars = 400),
        ).jsonObject

        assertEquals(
            "result_page_budget_too_small",
            response.getValue("error").jsonPrimitive.content,
        )
        assertFalse(response.containsKey("next_cursor"))
    }

    @Test
    fun `sub-envelope allowance blocks an MCP call before remote execution`() = runBlocking {
        val provider = McpToolProvider()
        try {
            val response = Json.parseToJsonElement(
                provider.execute(
                    name = "mcp_mutating_call",
                    arguments = "{}",
                    ctx = GenerationContext(
                        mcpEnabled = true,
                        toolResultMaxChars = 400,
                    ),
                ),
            ).jsonObject

            assertTrue(
                response.getValue("message").jsonPrimitive.content
                    .contains("no remote tool was executed"),
            )
        } finally {
            provider.close()
        }
    }
}
