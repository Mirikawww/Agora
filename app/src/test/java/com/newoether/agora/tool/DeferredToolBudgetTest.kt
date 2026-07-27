package com.newoether.agora.tool

import com.newoether.agora.api.DeferPolicy
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeferredToolBudgetTest {

    private fun tool(
        name: String,
        description: String = "A connector capability.",
        descriptionWords: Int? = null,
        defer: DeferPolicy = DeferPolicy.AUTO,
    ) = ToolDefinition(
        function = ToolFunction(
            name = name,
            description = descriptionWords?.let {
                List(it) { "description" }.joinToString(" ")
            } ?: description,
            parameters = ToolParameters(
                properties = mapOf(
                    "query" to ToolProperty("string", "The user's concrete request."),
                ),
                required = listOf("query"),
            ),
        ),
        defer = defer,
    )

    @Test
    fun `small registry stays direct`() {
        val tools = listOf(tool("a"), tool("b"), tool("c"))

        val plan = McpDeferredToolProvider.plan(
            tools,
            contextTokens = 8_000,
            currentText = "do something",
        )

        assertFalse(plan.usesBroker)
        assertEquals(tools, plan.inlineTools)
        assertEquals(3, plan.wireToolCount)
    }

    @Test
    fun `large registry greeting uploads only compact broker`() {
        val tools = (1..70).map { tool("connector_tool_$it") }

        val plan = McpDeferredToolProvider.plan(
            tools,
            contextTokens = 200_000,
            currentText = "你好",
        )

        assertTrue(plan.usesBroker)
        assertEquals(emptyList<ToolDefinition>(), plan.inlineTools)
        assertEquals(70, plan.deferredTools.size)
        assertEquals(1, plan.wireToolCount)
        assertEquals(CapabilityRouteMode.NO_TOOL, plan.route.mode)
    }

    @Test
    fun `greeting wire schema is a small constant fraction of full registry`() {
        val provider = McpDeferredToolProvider { _, _, _ -> "{}" }
        val tools = (1..75).map {
            tool("connector_tool_$it", descriptionWords = 80)
        }
        val ctx = GenerationContext(conversationId = "benchmark")
        val baseline = McpDeferredToolProvider.estimateSchemaTokens(tools)

        val plan = provider.prepare("benchmark", tools, 200_000, "你好", emptyList())
        val wire = plan.inlineTools + provider.definitions(ctx)
        val routed = McpDeferredToolProvider.estimateSchemaTokens(wire)

        assertEquals(1, wire.size)
        assertTrue("baseline=$baseline routed=$routed", routed * 10 < baseline)
    }

    @Test
    fun `large registry sends relevant Top-K and keeps every other tool broker reachable`() {
        val tools = (1..15).map {
            tool("todoist_action_$it", "Create, find, or update Todoist tasks.")
        } + (1..15).map {
            tool("notion_action_$it", "Create, find, or update Notion pages.")
        }

        val plan = McpDeferredToolProvider.plan(
            tools,
            contextTokens = 200_000,
            currentText = "创建一个 Todoist 待办任务",
        )

        assertTrue(plan.usesBroker)
        assertTrue(plan.inlineTools.isNotEmpty())
        assertTrue(plan.inlineTools.size <= McpDeferredToolProvider.TOP_K_DIRECT_TOOLS)
        assertTrue(plan.inlineTools.all { it.function.name.startsWith("todoist_") })
        assertEquals(tools.size, plan.inlineTools.size + plan.deferredTools.size)
    }

    @Test
    fun `reversing registry preserves inline wire tool order`() {
        val tools = listOf(
            tool("mandatory_z", defer = DeferPolicy.NEVER),
            tool("mandatory_a", defer = DeferPolicy.NEVER),
        ) + (1..15).map {
            tool("todoist_action_$it", "Create, find, or update Todoist tasks.")
        } + (1..15).map {
            tool("notion_action_$it", "Create, find, or update Notion pages.")
        }

        val forward = McpDeferredToolProvider.plan(
            tools,
            contextTokens = 200_000,
            currentText = "创建一个 Todoist 待办任务",
        )
        val reversed = McpDeferredToolProvider.plan(
            tools.reversed(),
            contextTokens = 200_000,
            currentText = "创建一个 Todoist 待办任务",
        )

        assertEquals(
            forward.inlineTools.map { it.function.name },
            reversed.inlineTools.map { it.function.name },
        )
    }

    @Test
    fun `wire count includes broker and never exceeds provider cap`() {
        val tools = (1..100).map {
            tool("tool_$it", description = "Tool capability number $it.")
        }

        val plan = McpDeferredToolProvider.plan(
            tools,
            contextTokens = 1_000_000,
            currentText = "use tool 1",
        )

        assertTrue(plan.wireToolCount <= McpDeferredToolProvider.MAX_WIRE_TOOLS)
    }

    @Test
    fun `NEVER remains direct while ALWAYS remains deferred`() {
        val tools = (1..12).map { tool("auto_$it") } +
            tool("approval_gated", defer = DeferPolicy.NEVER) +
            tool("always_brokered", defer = DeferPolicy.ALWAYS)

        val plan = McpDeferredToolProvider.plan(
            tools,
            contextTokens = 200_000,
            currentText = "你好",
        )

        assertTrue(plan.inlineTools.any { it.function.name == "approval_gated" })
        assertTrue(plan.deferredTools.any { it.function.name == "always_brokered" })
    }

    @Test
    fun `absolute upload budget does not disappear on a large context model`() {
        val tools = (1..8).map {
            tool("verbose_$it", descriptionWords = 1_000)
        }

        val smallContext = McpDeferredToolProvider.plan(
            tools,
            contextTokens = 8_000,
            currentText = "你好",
        )
        val largeContext = McpDeferredToolProvider.plan(
            tools,
            contextTokens = 1_000_000,
            currentText = "你好",
        )

        assertTrue(smallContext.usesBroker)
        assertTrue(largeContext.usesBroker)
        assertEquals(smallContext.wireToolCount, largeContext.wireToolCount)
    }

    @Test
    fun `conversation registries do not leak across concurrent generations`() = runBlocking {
        val provider = McpDeferredToolProvider { name, arguments, ctx ->
            "${ctx.conversationId}:$name:$arguments"
        }
        val alphaTools = (1..12).map {
            tool("alpha_$it", "Alpha accounting capability $it.")
        }
        val betaTools = (1..12).map {
            tool("beta_$it", "Beta calendar capability $it.")
        }
        provider.prepare("conversation-a", alphaTools, 200_000, "你好", emptyList())
        provider.prepare("conversation-b", betaTools, 200_000, "你好", emptyList())

        val alphaResult = provider.execute(
            McpDeferredToolProvider.TOOL_BROKER,
            """{"action":"search","query":"alpha accounting"}""",
            GenerationContext(conversationId = "conversation-a"),
        )
        val betaResult = provider.execute(
            McpDeferredToolProvider.TOOL_BROKER,
            """{"action":"search","query":"beta calendar"}""",
            GenerationContext(conversationId = "conversation-b"),
        )

        assertTrue(alphaResult.contains("alpha_1"))
        assertFalse(alphaResult.contains("beta_1"))
        assertTrue(betaResult.contains("beta_1"))
        assertFalse(betaResult.contains("alpha_1"))
    }

    @Test
    fun `broker invokes the original tool path`() = runBlocking {
        val provider = McpDeferredToolProvider { name, arguments, ctx ->
            "${ctx.conversationId}:$name:$arguments"
        }
        val tools = (1..12).map { tool("connector_$it") }
        val ctx = GenerationContext(conversationId = "conversation")
        provider.prepare("conversation", tools, 200_000, "你好", emptyList())

        val result = provider.execute(
            McpDeferredToolProvider.TOOL_BROKER,
            """{"action":"invoke","name":"connector_7","arguments":{"query":"value"}}""",
            ctx,
        )

        assertEquals("""conversation:connector_7:{"query":"value"}""", result)
    }

    @Test
    fun `selected direct tool restores exact full schema`() {
        val compact = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("mode", buildJsonObject { put("type", "string") })
            })
        }
        val full = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("mode", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add(JsonPrimitive("safe"))
                        add(JsonPrimitive("fast"))
                    })
                })
            })
        }
        val routedTool = ToolDefinition(
            function = ToolFunction(
                name = "connector_mode",
                description = "Choose an exact connector mode.",
                parameters = ToolParameters(properties = emptyMap(), rawSchema = compact),
            ),
            fullParameters = ToolParameters(properties = emptyMap(), rawSchema = full),
        )

        val plan = McpDeferredToolProvider.plan(
            tools = listOf(routedTool),
            contextTokens = 200_000,
            currentText = "choose connector mode",
        )

        assertEquals(full, plan.inlineTools.single().function.parameters.rawSchema)
    }

    @Test
    fun `full schema rather than compact capsule controls greeting budget`() {
        val compact = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {})
        }
        val full = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("mode", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        repeat(4_000) { index -> add(JsonPrimitive("exact-mode-$index")) }
                    })
                })
            })
        }
        val huge = ToolDefinition(
            function = ToolFunction(
                "connector_huge",
                "A capability with a large exact schema.",
                ToolParameters(properties = emptyMap(), rawSchema = compact),
            ),
            fullParameters = ToolParameters(properties = emptyMap(), rawSchema = full),
        )

        val plan = McpDeferredToolProvider.plan(
            listOf(huge),
            contextTokens = 1_000_000,
            currentText = "你好",
        )

        assertTrue(plan.usesBroker)
        assertTrue(plan.inlineTools.isEmpty())
    }

    @Test
    fun `search pagination makes zero-score and seventh tools discoverable`() = runBlocking {
        val provider = McpDeferredToolProvider { _, _, _ -> "{}" }
        val tools = (1..12).map { index ->
            tool("unfamiliar_${index.toString().padStart(2, '0')}", "Opaque operation $index.")
        }
        val ctx = GenerationContext(conversationId = "paging")
        provider.prepare("paging", tools, 200_000, "你好", emptyList())

        val first = provider.execute(
            McpDeferredToolProvider.TOOL_BROKER,
            """{"action":"search","query":"完全陌生的意图","limit":6}""",
            ctx,
        )
        val second = provider.execute(
            McpDeferredToolProvider.TOOL_BROKER,
            """{"action":"search","query":"完全陌生的意图","limit":6,"cursor":6}""",
            ctx,
        )

        assertTrue(first.contains("\"next_cursor\":6"))
        assertTrue(first.contains("unfamiliar_01"))
        assertFalse(first.contains("unfamiliar_07"))
        assertTrue(second.contains("unfamiliar_07"))
        assertTrue(second.contains("unfamiliar_12"))
    }

    @Test
    fun `all enabled capability families are named in compact broker manifest`() {
        val provider = McpDeferredToolProvider { _, _, _ -> "{}" }
        val tools = listOf(
            tool("todoist_add_tasks", "Todoist tasks."),
            tool("notion_create_page", "Notion pages."),
            tool("github_request", "GitHub."),
            tool("web_search", "Web search."),
            tool("memory", "Memory."),
            tool("search_conversations", "Past conversation search."),
            tool("file_read", "Read files."),
            tool("execute_shell_command", "Run shell."),
            tool("skills", "Skills."),
            tool("generate_image", "Generate images."),
            tool("ask_user", "Ask the user."),
            tool("update_user_profile", "Personalization."),
            tool("get_provider_balance", "Provider balance."),
        )
        val ctx = GenerationContext(conversationId = "manifest")
        provider.prepare("manifest", tools, 200_000, "你好", emptyList())

        val description = provider.definitions(ctx).single().function.description

        assertTrue(description.contains("Ask"))
        assertTrue(description.contains("Skills"))
        assertTrue(description.contains("Todoist"))
        assertTrue(description.contains("Notion"))
        assertTrue(description.contains("personalization"))
        assertTrue(description.contains("provider balances"))
    }

    @Test
    fun `old generation cleanup cannot delete replacement state in same conversation`() = runBlocking {
        val provider = McpDeferredToolProvider { _, _, _ -> "{}" }
        val oldTools = (1..12).map { tool("old_$it", "Old capability.") }
        val newTools = (1..12).map { tool("new_$it", "New capability.") }
        provider.prepare("request-old", oldTools, 200_000, "你好", emptyList())
        provider.prepare("request-new", newTools, 200_000, "你好", emptyList())
        provider.clear("request-old")

        val result = provider.execute(
            McpDeferredToolProvider.TOOL_BROKER,
            """{"action":"search","query":"new","limit":20}""",
            GenerationContext(
                conversationId = "same-conversation",
                capabilityRequestId = "request-new",
            ),
        )

        assertTrue(result.contains("new_1"))
        assertFalse(result.contains("old_1"))
    }

    @Test
    fun `NEVER overflow fails explicitly instead of bypassing its native gate`() {
        val tools = (1..64).map {
            tool("mandatory_$it", defer = DeferPolicy.NEVER)
        } + tool("extra_auto")

        val error = try {
            McpDeferredToolProvider.plan(
                tools,
                contextTokens = 200_000,
                currentText = "你好",
            )
            null
        } catch (expected: IllegalArgumentException) {
            expected
        }

        assertTrue(error?.message?.contains("NEVER-deferred") == true)
    }

    @Test
    fun `estimate counts name description and schema`() {
        val cost = McpDeferredToolProvider.estimateSchemaTokens(
            listOf(tool("x", descriptionWords = 40)),
        )

        assertTrue("expected a non-trivial estimate, got $cost", cost > 20)
    }
}
