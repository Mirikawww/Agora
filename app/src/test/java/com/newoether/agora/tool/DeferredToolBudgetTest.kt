package com.newoether.agora.tool

import com.newoether.agora.api.DeferPolicy
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    fun `substantive requests keep foundational tools directly callable without discovery`() {
        val ctx = GenerationContext(webSearchEnabled = true, askToolEnabled = true)
        val foundational = WebSearchToolProvider().definitions(ctx) +
            AskToolProvider().definitions(ctx)
        val longTail = (1..70).map { tool("connector_tool_$it") }

        val plan = McpDeferredToolProvider.plan(
            tools = foundational + longTail,
            contextTokens = 200_000,
            currentText = "请核实 OpenAI 创始人是谁",
        )

        assertEquals(
            setOf("ask_user", "web_fetch", "web_search"),
            plan.inlineTools.map { it.function.name }.toSet(),
        )
        assertTrue(plan.usesBroker)
        assertEquals(4, plan.wireToolCount)
        assertTrue(
            "foundational schema cost=${plan.inlineSchemaTokens}",
            plan.inlineSchemaTokens < 1_000,
        )
    }

    @Test
    fun `pure greeting still defers foundational tools`() {
        val ctx = GenerationContext(webSearchEnabled = true, askToolEnabled = true)
        val foundational = WebSearchToolProvider().definitions(ctx) +
            AskToolProvider().definitions(ctx)
        val longTail = (1..70).map { tool("connector_tool_$it") }

        val plan = McpDeferredToolProvider.plan(
            tools = foundational + longTail,
            contextTokens = 200_000,
            currentText = "你好",
        )

        assertTrue(plan.inlineTools.isEmpty())
        assertEquals(1, plan.wireToolCount)
        assertEquals(CapabilityRouteMode.NO_TOOL, plan.route.mode)
    }

    @Test
    fun `pure greeting defers foundational tools even without a large connector registry`() {
        val ctx = GenerationContext(webSearchEnabled = true, askToolEnabled = true)
        val foundational = WebSearchToolProvider().definitions(ctx) +
            AskToolProvider().definitions(ctx)

        val plan = McpDeferredToolProvider.plan(
            tools = foundational,
            contextTokens = 200_000,
            currentText = "你好",
        )

        assertTrue(plan.inlineTools.isEmpty())
        assertEquals(3, plan.deferredTools.size)
        assertEquals(1, plan.wireToolCount)
    }

    @Test
    fun `explicit force exposes the requested foundational tool even for a greeting`() {
        val ctx = GenerationContext(webSearchEnabled = true, askToolEnabled = true)
        val foundational = WebSearchToolProvider().definitions(ctx) +
            AskToolProvider().definitions(ctx)
        val longTail = (1..70).map { tool("connector_tool_$it") }

        val plan = McpDeferredToolProvider.plan(
            tools = foundational + longTail,
            contextTokens = 200_000,
            currentText = "你好",
            forcedDirectToolNames = setOf("web_search", "web_fetch"),
        )

        assertEquals(
            setOf("web_fetch", "web_search"),
            plan.inlineTools.map { it.function.name }.toSet(),
        )
        assertTrue(plan.usesBroker)
    }

    @Test
    fun `broker search excludes direct tools and returns an invoke-ready input hint`() = runBlocking {
        var invoked = false
        val provider = McpDeferredToolProvider { _, _, _ ->
            invoked = true
            "{}"
        }
        val ctx = GenerationContext(
            conversationId = "foundational-direct",
            webSearchEnabled = true,
            askToolEnabled = true,
        )
        val foundational = WebSearchToolProvider().definitions(ctx) +
            AskToolProvider().definitions(ctx)
        val longTail = (1..70).map { tool("connector_tool_$it") }
        provider.prepare(
            requestId = "foundational-direct",
            allTools = foundational + longTail,
            contextTokens = 200_000,
            currentText = "请核实 OpenAI 创始人是谁",
            recentTexts = emptyList(),
        )

        val search = provider.execute(
            McpDeferredToolProvider.TOOL_BROKER,
            """{"action":"search","query":"connector","limit":6}""",
            ctx,
        )
        val inspectDirect = provider.execute(
            McpDeferredToolProvider.TOOL_BROKER,
            """{"action":"inspect","name":"web_search"}""",
            ctx,
        )
        val invokeDirect = provider.execute(
            McpDeferredToolProvider.TOOL_BROKER,
            """{"action":"invoke","name":"web_search","arguments":{"query":"OpenAI"}}""",
            ctx,
        )

        assertFalse(search.contains("\"name\":\"web_search\""))
        assertFalse(search.contains("\"name\":\"web_fetch\""))
        assertFalse(search.contains("\"name\":\"ask_user\""))
        assertTrue(search.contains("\"input_hint\""))
        assertTrue(search.contains("\"query\":\"string; required; hint="))
        assertTrue(search.contains("Invoke directly"))
        assertTrue(inspectDirect.contains("tool_already_direct"))
        assertTrue(invokeDirect.contains("tool_already_direct"))
        assertFalse(invoked)
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
    fun `routed exact match cannot bypass final inline schema budget`() {
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
                        repeat(4_000) { index -> add(JsonPrimitive("notion-mode-$index")) }
                    })
                })
            })
        }
        val hugeNotionTool = ToolDefinition(
            function = ToolFunction(
                name = "notion_create_page",
                description = "Create an exact Notion page.",
                parameters = ToolParameters(properties = emptyMap(), rawSchema = compact),
            ),
            fullParameters = ToolParameters(properties = emptyMap(), rawSchema = full),
        )

        val plan = McpDeferredToolProvider.plan(
            tools = listOf(hugeNotionTool),
            contextTokens = 1_000_000,
            currentText = "Create a Notion page",
        )

        assertTrue(plan.usesBroker)
        assertTrue(plan.inlineTools.isEmpty())
        assertEquals(CapabilityRouteMode.BROKER, plan.route.mode)
        assertTrue(
            "final inline schema cost=${plan.inlineSchemaTokens}",
            plan.inlineSchemaTokens <= McpDeferredToolProvider.MAX_INLINE_SCHEMA_TOKENS,
        )
        assertTrue(plan.deferredTools.any { it.function.name == "notion_create_page" })
    }

    @Test
    fun `oversized EAGER schema is preferred but remains broker reachable`() {
        val full = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        repeat(4_000) { index -> add(JsonPrimitive("query-mode-$index")) }
                    })
                })
            })
        }
        val hugeEager = ToolDefinition(
            function = ToolFunction(
                name = "foundational_search",
                description = "Search a foundational source.",
                parameters = ToolParameters(properties = emptyMap()),
            ),
            defer = DeferPolicy.EAGER,
            fullParameters = ToolParameters(properties = emptyMap(), rawSchema = full),
        )

        val plan = McpDeferredToolProvider.plan(
            tools = listOf(hugeEager),
            contextTokens = 200_000,
            currentText = "Research this substantive request",
        )

        assertEquals(CapabilityRouteMode.BROKER, plan.route.mode)
        assertTrue(plan.inlineTools.isEmpty())
        assertEquals(listOf("foundational_search"), plan.deferredTools.map { it.function.name })
        assertTrue(plan.inlineSchemaTokens <= McpDeferredToolProvider.MAX_INLINE_SCHEMA_TOKENS)
    }

    @Test
    fun `search capsule stays bounded while inspect preserves a huge exact schema`() = runBlocking {
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
                        repeat(1_000) { index -> add(JsonPrimitive("exact-mode-$index")) }
                    })
                })
            })
            put("required", buildJsonArray { add(JsonPrimitive("mode")) })
        }
        val huge = ToolDefinition(
            function = ToolFunction(
                "connector_huge",
                "Choose an exact connector mode.",
                ToolParameters(properties = emptyMap(), rawSchema = compact),
            ),
            fullParameters = ToolParameters(properties = emptyMap(), rawSchema = full),
            defer = DeferPolicy.ALWAYS,
        )
        val provider = McpDeferredToolProvider { _, _, _ -> "{}" }
        val ctx = GenerationContext(conversationId = "huge-capsule")
        provider.prepare(
            requestId = "huge-capsule",
            allTools = listOf(huge),
            contextTokens = 200_000,
            currentText = "use the connector",
            recentTexts = emptyList(),
        )

        val search = provider.execute(
            McpDeferredToolProvider.TOOL_BROKER,
            """{"action":"search","query":"connector_huge","limit":1}""",
            ctx,
        )
        var inspect = provider.execute(
            McpDeferredToolProvider.TOOL_BROKER,
            """{"action":"inspect","name":"connector_huge"}""",
            ctx,
        )

        assertTrue(search.length < 1_500)
        assertTrue(search.contains("\"inspect_for_required_constraints\":true"))
        assertFalse(search.contains("exact-mode-999"))
        val inspectedSchema = buildString {
            while (true) {
                val response = Json.parseToJsonElement(inspect).jsonObject
                response["input_schema"]?.let {
                    append(it)
                    break
                }
                append(response.getValue("input_schema_json_chunk").jsonPrimitive.content)
                val nextCursor = response["next_cursor"]?.jsonPrimitive?.int ?: break
                inspect = provider.execute(
                    McpDeferredToolProvider.TOOL_BROKER,
                    """{"action":"inspect","name":"connector_huge","cursor":$nextCursor}""",
                    ctx,
                )
            }
        }
        assertTrue(inspectedSchema.contains("exact-mode-999"))
    }

    @Test
    fun `small inspect still returns the complete input schema once`() = runBlocking {
        val full = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("minLength", 3)
                })
            })
            put("required", buildJsonArray { add(JsonPrimitive("query")) })
        }
        val provider = McpDeferredToolProvider { _, _, _ -> "{}" }
        val ctx = GenerationContext(conversationId = "small-inspect")
        provider.prepare(
            requestId = "small-inspect",
            allTools = listOf(
                ToolDefinition(
                    function = ToolFunction(
                        "small_inspect_tool",
                        "A small exact schema.",
                        ToolParameters(properties = emptyMap(), rawSchema = full),
                    ),
                    defer = DeferPolicy.ALWAYS,
                ),
            ),
            contextTokens = 200_000,
            currentText = "inspect the small tool",
            recentTexts = emptyList(),
        )

        val inspect = Json.parseToJsonElement(
            provider.execute(
                McpDeferredToolProvider.TOOL_BROKER,
                """{"action":"inspect","name":"small_inspect_tool"}""",
                ctx,
            ),
        ).jsonObject

        assertEquals(full, inspect["input_schema"])
        assertEquals(JsonPrimitive(true), inspect["complete"])
        assertEquals("raw", inspect["section"]!!.jsonPrimitive.content)
        assertFalse(inspect.containsKey("next_cursor"))
        assertFalse(inspect.containsKey("input_schema_json_chunk"))
    }

    @Test
    fun `large safe inspect defaults to the complete required section`() = runBlocking {
        val full = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("format", "uri")
                    put("pattern", "^https://")
                })
                put("optional_mode", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        repeat(4_000) { index -> add(JsonPrimitive("optional-mode-$index")) }
                    })
                })
            })
            put("required", buildJsonArray { add(JsonPrimitive("query")) })
        }
        val provider = McpDeferredToolProvider { _, _, _ -> "{}" }
        val ctx = GenerationContext(conversationId = "required-section")
        provider.prepare(
            requestId = "required-section",
            allTools = listOf(
                ToolDefinition(
                    function = ToolFunction(
                        "large_safe_tool",
                        "A large tool with a small required signature.",
                        ToolParameters(properties = emptyMap(), rawSchema = full),
                    ),
                    defer = DeferPolicy.ALWAYS,
                ),
            ),
            contextTokens = 200_000,
            currentText = "use the large safe tool",
            recentTexts = emptyList(),
        )

        val inspect = Json.parseToJsonElement(
            provider.execute(
                McpDeferredToolProvider.TOOL_BROKER,
                """{"action":"inspect","name":"large_safe_tool"}""",
                ctx,
            ),
        ).jsonObject
        val inspectedSchema = inspect["input_schema"]!!.jsonObject
        val properties = inspectedSchema["properties"]!!.jsonObject

        assertEquals("required", inspect["section"]!!.jsonPrimitive.content)
        assertEquals("object", inspectedSchema["type"]!!.jsonPrimitive.content)
        assertEquals(listOf("query"), inspectedSchema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(setOf("query"), properties.keys)
        assertEquals("uri", properties["query"]!!.jsonObject["format"]!!.jsonPrimitive.content)
        assertEquals("^https://", properties["query"]!!.jsonObject["pattern"]!!.jsonPrimitive.content)
        assertFalse(inspect.toString().contains("optional-mode-3999"))
    }

    @Test
    fun `optional inspect returns only complete optional properties`() = runBlocking {
        val full = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        repeat(4_000) { index -> add(JsonPrimitive("required-query-$index")) }
                    })
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put("minimum", 1)
                    put("maximum", 50)
                })
            })
            put("required", buildJsonArray { add(JsonPrimitive("query")) })
        }
        val provider = McpDeferredToolProvider { _, _, _ -> "{}" }
        val ctx = GenerationContext(conversationId = "optional-section")
        provider.prepare(
            requestId = "optional-section",
            allTools = listOf(
                ToolDefinition(
                    function = ToolFunction(
                        "large_optional_tool",
                        "A large tool with one optional field.",
                        ToolParameters(properties = emptyMap(), rawSchema = full),
                    ),
                    defer = DeferPolicy.ALWAYS,
                ),
            ),
            contextTokens = 200_000,
            currentText = "use the optional field",
            recentTexts = emptyList(),
        )

        val inspect = Json.parseToJsonElement(
            provider.execute(
                McpDeferredToolProvider.TOOL_BROKER,
                """{"action":"inspect","name":"large_optional_tool","section":"optional"}""",
                ctx,
            ),
        ).jsonObject
        val inspectedSchema = inspect["input_schema"]!!.jsonObject
        val properties = inspectedSchema["properties"]!!.jsonObject

        assertEquals("optional", inspect["section"]!!.jsonPrimitive.content)
        assertEquals(setOf("limit"), properties.keys)
        assertEquals(1, properties["limit"]!!.jsonObject["minimum"]!!.jsonPrimitive.int)
        assertEquals(50, properties["limit"]!!.jsonObject["maximum"]!!.jsonPrimitive.int)
        assertFalse(inspect.toString().contains("required-query-3999"))
    }

    @Test
    fun `optional inspect includes definitions used by optional properties`() = runBlocking {
        val full = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("query", buildJsonObject {
                    put("enum", buildJsonArray {
                        repeat(4_000) { index -> add(JsonPrimitive("required-padding-$index")) }
                    })
                })
                put("filter", buildJsonObject { put("\$ref", "#/\$defs/Filter") })
            })
            put("required", buildJsonArray { add(JsonPrimitive("query")) })
            put("\$defs", buildJsonObject {
                put("Filter", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("value", buildJsonObject { put("type", "string") })
                    })
                })
                put("Unused", buildJsonObject { put("const", "optional-unused") })
            })
        }
        val provider = McpDeferredToolProvider { _, _, _ -> "{}" }
        val ctx = GenerationContext(conversationId = "optional-defs")
        provider.prepare(
            requestId = "optional-defs",
            allTools = listOf(
                ToolDefinition(
                    function = ToolFunction(
                        "optional_defs_tool",
                        "A tool whose optional field uses a local definition.",
                        ToolParameters(properties = emptyMap(), rawSchema = full),
                    ),
                    defer = DeferPolicy.ALWAYS,
                ),
            ),
            contextTokens = 200_000,
            currentText = "inspect optional definitions",
            recentTexts = emptyList(),
        )

        val inspect = Json.parseToJsonElement(
            provider.execute(
                McpDeferredToolProvider.TOOL_BROKER,
                """{"action":"inspect","name":"optional_defs_tool","section":"optional"}""",
                ctx,
            ),
        ).jsonObject
        val inspectedSchema = inspect["input_schema"]!!.jsonObject

        assertEquals(setOf("filter"), inspectedSchema["properties"]!!.jsonObject.keys)
        assertEquals(setOf("Filter"), inspectedSchema["\$defs"]!!.jsonObject.keys)
        assertFalse(inspect.toString().contains("optional-unused"))
    }

    @Test
    fun `required inspect includes transitive local defs and omits unused defs`() = runBlocking {
        val full = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("request", buildJsonObject {
                    put("\$ref", "#/\$defs/Request")
                })
                put("padding", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        repeat(4_000) { index -> add(JsonPrimitive("padding-$index")) }
                    })
                })
            })
            put("required", buildJsonArray { add(JsonPrimitive("request")) })
            put("\$defs", buildJsonObject {
                put("Request", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("mode", buildJsonObject {
                            put("\$ref", "#/\$defs/Mode")
                        })
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("mode")) })
                })
                put("Mode", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add(JsonPrimitive("safe"))
                        add(JsonPrimitive("fast"))
                    })
                })
                put("Unused", buildJsonObject {
                    put("type", "string")
                    put("const", "must-not-leak")
                })
            })
        }
        val provider = McpDeferredToolProvider { _, _, _ -> "{}" }
        val ctx = GenerationContext(conversationId = "defs-closure")
        provider.prepare(
            requestId = "defs-closure",
            allTools = listOf(
                ToolDefinition(
                    function = ToolFunction(
                        "defs_tool",
                        "A tool whose required input uses local definitions.",
                        ToolParameters(properties = emptyMap(), rawSchema = full),
                    ),
                    defer = DeferPolicy.ALWAYS,
                ),
            ),
            contextTokens = 200_000,
            currentText = "use the defs tool",
            recentTexts = emptyList(),
        )

        val inspect = Json.parseToJsonElement(
            provider.execute(
                McpDeferredToolProvider.TOOL_BROKER,
                """{"action":"inspect","name":"defs_tool"}""",
                ctx,
            ),
        ).jsonObject
        val inspectedSchema = inspect["input_schema"]!!.jsonObject
        val defs = inspectedSchema["\$defs"]!!.jsonObject

        assertEquals(setOf("Request", "Mode"), defs.keys)
        assertEquals(
            "#/\$defs/Mode",
            defs["Request"]!!.jsonObject["properties"]!!.jsonObject["mode"]!!
                .jsonObject["\$ref"]!!.jsonPrimitive.content,
        )
        assertEquals(
            listOf("safe", "fast"),
            defs["Mode"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertFalse(inspect.toString().contains("must-not-leak"))
    }

    @Test
    fun `required inspect closes legacy definitions refs`() = runBlocking {
        val full = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("request", buildJsonObject { put("\$ref", "#/definitions/Request") })
                put("padding", buildJsonObject {
                    put("enum", buildJsonArray {
                        repeat(4_000) { index -> add(JsonPrimitive("legacy-padding-$index")) }
                    })
                })
            })
            put("required", buildJsonArray { add(JsonPrimitive("request")) })
            put("definitions", buildJsonObject {
                put("Request", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("title", buildJsonObject { put("type", "string") })
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("title")) })
                })
                put("Unused", buildJsonObject { put("const", "legacy-unused") })
            })
        }
        val provider = McpDeferredToolProvider { _, _, _ -> "{}" }
        val ctx = GenerationContext(conversationId = "definitions-closure")
        provider.prepare(
            requestId = "definitions-closure",
            allTools = listOf(
                ToolDefinition(
                    function = ToolFunction(
                        "legacy_definitions_tool",
                        "A tool using legacy definitions.",
                        ToolParameters(properties = emptyMap(), rawSchema = full),
                    ),
                    defer = DeferPolicy.ALWAYS,
                ),
            ),
            contextTokens = 200_000,
            currentText = "use legacy definitions",
            recentTexts = emptyList(),
        )

        val inspect = Json.parseToJsonElement(
            provider.execute(
                McpDeferredToolProvider.TOOL_BROKER,
                """{"action":"inspect","name":"legacy_definitions_tool"}""",
                ctx,
            ),
        ).jsonObject
        val definitions = inspect["input_schema"]!!.jsonObject["definitions"]!!.jsonObject

        assertEquals(setOf("Request"), definitions.keys)
        assertEquals(
            listOf("title"),
            definitions["Request"]!!.jsonObject["required"]!!.jsonArray
                .map { it.jsonPrimitive.content },
        )
        assertFalse(inspect.toString().contains("legacy-unused"))
    }

    @Test
    fun `root combinators fall back to bounded raw inspect`() = runBlocking {
        val full = buildJsonObject {
            put("type", "object")
            put("oneOf", buildJsonArray {
                add(buildJsonObject { put("required", buildJsonArray { add(JsonPrimitive("query")) }) })
                add(buildJsonObject { put("required", buildJsonArray { add(JsonPrimitive("id")) }) })
            })
            put("properties", buildJsonObject {
                put("query", buildJsonObject { put("type", "string") })
                put("id", buildJsonObject { put("type", "string") })
                put("padding", buildJsonObject {
                    put("enum", buildJsonArray {
                        repeat(5_000) { index -> add(JsonPrimitive("combinator-padding-$index")) }
                    })
                })
            })
            put("required", buildJsonArray { add(JsonPrimitive("query")) })
        }
        val provider = McpDeferredToolProvider { _, _, _ -> "{}" }
        val ctx = GenerationContext(conversationId = "root-combinator")
        provider.prepare(
            requestId = "root-combinator",
            allTools = listOf(
                ToolDefinition(
                    function = ToolFunction(
                        "root_combinator_tool",
                        "A schema whose root semantics cannot be sectioned safely.",
                        ToolParameters(properties = emptyMap(), rawSchema = full),
                    ),
                    defer = DeferPolicy.ALWAYS,
                ),
            ),
            contextTokens = 200_000,
            currentText = "inspect the combinator tool",
            recentTexts = emptyList(),
        )

        val inspect = Json.parseToJsonElement(
            provider.execute(
                McpDeferredToolProvider.TOOL_BROKER,
                """{"action":"inspect","name":"root_combinator_tool"}""",
                ctx,
            ),
        ).jsonObject

        assertEquals("raw", inspect["section"]!!.jsonPrimitive.content)
        assertTrue(inspect["input_schema_json_chunk"]!!.jsonPrimitive.content.contains("\"oneOf\""))
        assertTrue(inspect.containsKey("next_cursor"))
        assertFalse(inspect.containsKey("input_schema"))
    }

    @Test
    fun `root local ref falls back to raw without an empty required schema`() = runBlocking {
        val full = buildJsonObject {
            put("\$ref", "#/\$defs/Input")
            put("\$defs", buildJsonObject {
                put("Input", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("query", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray {
                                repeat(2_000) { index -> add(JsonPrimitive("root-ref-$index")) }
                            })
                        })
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("query")) })
                })
            })
        }
        val provider = McpDeferredToolProvider { _, _, _ -> "{}" }
        val ctx = GenerationContext(conversationId = "root-ref")
        provider.prepare(
            requestId = "root-ref",
            allTools = listOf(
                ToolDefinition(
                    function = ToolFunction(
                        "root_ref_tool",
                        "A root-ref schema.",
                        ToolParameters(properties = emptyMap(), rawSchema = full),
                    ),
                    defer = DeferPolicy.ALWAYS,
                ),
            ),
            contextTokens = 200_000,
            currentText = "inspect root ref",
            recentTexts = emptyList(),
        )

        val rawResult = provider.execute(
            McpDeferredToolProvider.TOOL_BROKER,
            """{"action":"inspect","name":"root_ref_tool"}""",
            ctx,
        )
        val inspect = Json.parseToJsonElement(rawResult).jsonObject

        assertEquals("raw", inspect["section"]!!.jsonPrimitive.content)
        assertTrue(inspect["input_schema_json_chunk"]!!.jsonPrimitive.content.contains("\"\$ref\""))
        assertTrue(inspect.containsKey("next_cursor"))
        assertTrue("inspect result chars=${rawResult.length}", rawResult.length < 16_000)
    }

    @Test
    fun `external refs anywhere in a large schema fall back to raw`() = runBlocking {
        val full = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("query", buildJsonObject { put("type", "string") })
                put("remote_filter", buildJsonObject {
                    put("\$ref", "https://schemas.example.test/filter.json")
                })
                put("padding", buildJsonObject {
                    put("enum", buildJsonArray {
                        repeat(5_000) { index -> add(JsonPrimitive("external-ref-padding-$index")) }
                    })
                })
            })
            put("required", buildJsonArray { add(JsonPrimitive("query")) })
        }
        val provider = McpDeferredToolProvider { _, _, _ -> "{}" }
        val ctx = GenerationContext(conversationId = "external-ref")
        provider.prepare(
            requestId = "external-ref",
            allTools = listOf(
                ToolDefinition(
                    function = ToolFunction(
                        "external_ref_tool",
                        "A schema containing an external reference.",
                        ToolParameters(properties = emptyMap(), rawSchema = full),
                    ),
                    defer = DeferPolicy.ALWAYS,
                ),
            ),
            contextTokens = 200_000,
            currentText = "inspect the external ref tool",
            recentTexts = emptyList(),
        )

        val inspect = Json.parseToJsonElement(
            provider.execute(
                McpDeferredToolProvider.TOOL_BROKER,
                """{"action":"inspect","name":"external_ref_tool"}""",
                ctx,
            ),
        ).jsonObject

        assertEquals("raw", inspect["section"]!!.jsonPrimitive.content)
        assertTrue(
            inspect["input_schema_json_chunk"]!!.jsonPrimitive.content
                .contains("https://schemas.example.test/filter.json"),
        )
        assertTrue(inspect.containsKey("next_cursor"))
    }

    @Test
    fun `unresolved local refs in a large schema fall back to raw`() = runBlocking {
        val full = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("query", buildJsonObject { put("\$ref", "#/\$defs/Missing") })
                put("padding", buildJsonObject {
                    put("enum", buildJsonArray {
                        repeat(5_000) { index -> add(JsonPrimitive("missing-ref-padding-$index")) }
                    })
                })
            })
            put("required", buildJsonArray { add(JsonPrimitive("query")) })
            put("\$defs", buildJsonObject {
                put("Present", buildJsonObject { put("type", "string") })
            })
        }
        val provider = McpDeferredToolProvider { _, _, _ -> "{}" }
        val ctx = GenerationContext(conversationId = "missing-ref")
        provider.prepare(
            requestId = "missing-ref",
            allTools = listOf(
                ToolDefinition(
                    function = ToolFunction(
                        "missing_ref_tool",
                        "A schema containing an unresolved local reference.",
                        ToolParameters(properties = emptyMap(), rawSchema = full),
                    ),
                    defer = DeferPolicy.ALWAYS,
                ),
            ),
            contextTokens = 200_000,
            currentText = "inspect the missing ref tool",
            recentTexts = emptyList(),
        )

        val inspect = Json.parseToJsonElement(
            provider.execute(
                McpDeferredToolProvider.TOOL_BROKER,
                """{"action":"inspect","name":"missing_ref_tool"}""",
                ctx,
            ),
        ).jsonObject

        assertEquals("raw", inspect["section"]!!.jsonPrimitive.content)
        assertTrue(
            inspect["input_schema_json_chunk"]!!.jsonPrimitive.content
                .contains("#/\$defs/Missing"),
        )
        assertTrue(inspect.containsKey("next_cursor"))
    }

    @Test
    fun `raw inspect keeps max chars pagination valid and lossless`() = runBlocking {
        val full = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("mode", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        repeat(1_000) { index -> add(JsonPrimitive("raw-mode-$index")) }
                    })
                })
            })
            put("required", buildJsonArray { add(JsonPrimitive("mode")) })
        }
        val provider = McpDeferredToolProvider { _, _, _ -> "{}" }
        val ctx = GenerationContext(conversationId = "raw-lossless")
        provider.prepare(
            requestId = "raw-lossless",
            allTools = listOf(
                ToolDefinition(
                    function = ToolFunction(
                        "raw_lossless_tool",
                        "A schema inspected through raw pages.",
                        ToolParameters(properties = emptyMap(), rawSchema = full),
                    ),
                    defer = DeferPolicy.ALWAYS,
                ),
            ),
            contextTokens = 200_000,
            currentText = "inspect every raw page",
            recentTexts = emptyList(),
        )

        val reconstructed = StringBuilder()
        var cursor = 0
        while (true) {
            val page = Json.parseToJsonElement(
                provider.execute(
                    McpDeferredToolProvider.TOOL_BROKER,
                    """{"action":"inspect","name":"raw_lossless_tool","section":"raw","cursor":$cursor,"max_chars":1000}""",
                    ctx,
                ),
            ).jsonObject
            val chunk = page["input_schema_json_chunk"]!!.jsonPrimitive.content
            assertTrue(chunk.length <= 1_000)
            assertEquals("raw", page["section"]!!.jsonPrimitive.content)
            reconstructed.append(chunk)
            val next = page["next_cursor"]?.jsonPrimitive?.int ?: break
            assertTrue(next > cursor)
            cursor = next
        }

        assertEquals(full.toString(), reconstructed.toString())
    }

    @Test
    fun `search capsule marks omitted format and pattern constraints for inspect`() = runBlocking {
        val full = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("email", buildJsonObject {
                    put("type", "string")
                    put("description", "Account email address.")
                    put("format", "email")
                })
                put("code", buildJsonObject {
                    put("type", "string")
                    put("description", "Six digit verification code.")
                    put("pattern", "^[0-9]{6}$")
                })
            })
            put("required", buildJsonArray {
                add(JsonPrimitive("email"))
                add(JsonPrimitive("code"))
            })
        }
        val constrained = ToolDefinition(
            function = ToolFunction(
                "connector_verify",
                "Verify a connector account.",
                ToolParameters(properties = emptyMap(), rawSchema = full),
            ),
            defer = DeferPolicy.ALWAYS,
        )
        val provider = McpDeferredToolProvider { _, _, _ -> "{}" }
        val ctx = GenerationContext(conversationId = "constraint-capsule")
        provider.prepare(
            requestId = "constraint-capsule",
            allTools = listOf(constrained),
            contextTokens = 200_000,
            currentText = "verify the account",
            recentTexts = emptyList(),
        )

        val search = provider.execute(
            McpDeferredToolProvider.TOOL_BROKER,
            """{"action":"search","query":"connector_verify","limit":1}""",
            ctx,
        )
        val inspect = provider.execute(
            McpDeferredToolProvider.TOOL_BROKER,
            """{"action":"inspect","name":"connector_verify"}""",
            ctx,
        )

        assertTrue(search.contains("\"inspect_for_required_constraints\":true"))
        assertFalse(search.contains("^[0-9]{6}$"))
        assertTrue(inspect.contains("^[0-9]{6}$"))
    }

    @Test
    fun `exact search hit returns one capsule before paginated fallback tools`() = runBlocking {
        val provider = McpDeferredToolProvider { _, _, _ -> "{}" }
        val tools = listOf(tool("alpha_exact", "Exact alpha capability.")) +
            (1..11).map { index ->
                tool("opaque_${index.toString().padStart(2, '0')}", "Opaque operation $index.")
            }
        val ctx = GenerationContext(conversationId = "positive-paging")
        provider.prepare("positive-paging", tools, 200_000, "你好", emptyList())

        val first = Json.parseToJsonElement(
            provider.execute(
                McpDeferredToolProvider.TOOL_BROKER,
                """{"action":"search","query":"alpha_exact"}""",
                ctx,
            ),
        ).jsonObject
        val second = Json.parseToJsonElement(
            provider.execute(
                McpDeferredToolProvider.TOOL_BROKER,
                """{"action":"search","query":"alpha_exact","cursor":1,"limit":20}""",
                ctx,
            ),
        ).jsonObject

        assertEquals(1, first["tools"]!!.jsonArray.size)
        assertEquals("alpha_exact", first["tools"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(1, first["next_cursor"]!!.jsonPrimitive.int)
        assertEquals(11, second["tools"]!!.jsonArray.size)
        assertTrue(second.toString().contains("opaque_01"))
    }

    @Test
    fun `required fields win capsule budget and optional complexity does not force inspect`() = runBlocking {
        val full = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                repeat(10) { index ->
                    put("optional_$index", buildJsonObject {
                        put("type", "object")
                        put("description", "Advanced optional filter $index.")
                        put("properties", buildJsonObject {
                            put("value", buildJsonObject { put("type", "string") })
                        })
                    })
                }
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "The required search query.")
                })
            })
            put("required", buildJsonArray { add(JsonPrimitive("query")) })
        }
        val provider = McpDeferredToolProvider { _, _, _ -> "{}" }
        val ctx = GenerationContext(conversationId = "required-first")
        val requiredLast = ToolDefinition(
            function = ToolFunction(
                "required_last",
                "Run a search with optional advanced filters.",
                ToolParameters(properties = emptyMap(), rawSchema = full),
            ),
            defer = DeferPolicy.ALWAYS,
        )
        provider.prepare(
            "required-first",
            listOf(requiredLast),
            200_000,
            "use required_last",
            emptyList(),
        )

        val search = provider.execute(
            McpDeferredToolProvider.TOOL_BROKER,
            """{"action":"search","query":"required_last"}""",
            ctx,
        )

        assertTrue(search.contains("\"query\":\"string; required; hint=The required search query.\""))
        assertTrue(search.contains("\"inspect_for_required_constraints\":false"))
        assertTrue(search.contains("\"inspect_for_optional_constraints\":true"))
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
    fun `broker inspect schema advertises every supported section`() {
        val provider = McpDeferredToolProvider { _, _, _ -> "{}" }
        val ctx = GenerationContext(conversationId = "inspect-section-contract")
        provider.prepare(
            requestId = "inspect-section-contract",
            allTools = listOf(tool("deferred_tool", defer = DeferPolicy.ALWAYS)),
            contextTokens = 200_000,
            currentText = "inspect the deferred tool",
            recentTexts = emptyList(),
        )

        val brokerParameters = provider.definitions(ctx).single().function.parameters
        val section = brokerParameters.rawSchema!!["properties"]!!
            .jsonObject["section"]!!.jsonObject

        assertTrue(brokerParameters.properties.containsKey("section"))
        assertEquals(
            listOf("required", "optional", "raw"),
            section["enum"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `custom MCP names keep broker manifest bounded while preserving server count`() {
        val provider = McpDeferredToolProvider { _, _, _ -> "{}" }
        val tools = (1..100).map { index ->
            tool(
                "mcp_tool_$index",
                "[MCP: server-$index-${"x".repeat(80)}] Capability $index.",
            )
        }
        val ctx = GenerationContext(conversationId = "bounded-manifest")
        provider.prepare("bounded-manifest", tools, 200_000, "你好", emptyList())

        val description = provider.definitions(ctx).single().function.description

        assertTrue("description chars=${description.length}", description.length < 1_500)
        assertTrue(description.contains("+84 more MCP servers"))
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
    fun `oversized NEVER schema fails instead of silently breaking final budget`() {
        val full = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("mode", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        repeat(4_000) { index -> add(JsonPrimitive("mandatory-mode-$index")) }
                    })
                })
            })
        }
        val mandatory = ToolDefinition(
            function = ToolFunction(
                name = "approval_gated",
                description = "A native approval-gated capability.",
                parameters = ToolParameters(properties = emptyMap()),
            ),
            defer = DeferPolicy.NEVER,
            fullParameters = ToolParameters(properties = emptyMap(), rawSchema = full),
        )

        val error = try {
            McpDeferredToolProvider.plan(
                tools = listOf(mandatory),
                contextTokens = 200_000,
                currentText = "run the approval gated capability",
            )
            null
        } catch (expected: IllegalArgumentException) {
            expected
        }

        assertTrue(error?.message?.contains("final inline budget") == true)

        val forcedError = try {
            McpDeferredToolProvider.plan(
                tools = listOf(mandatory.copy(defer = DeferPolicy.AUTO)),
                contextTokens = 200_000,
                currentText = "run the explicitly forced capability",
                forcedDirectToolNames = setOf("approval_gated"),
            )
            null
        } catch (expected: IllegalArgumentException) {
            expected
        }
        assertTrue(forcedError?.message?.contains("final inline budget") == true)
    }

    @Test
    fun `long numeric schema values cannot bypass final budgets`() {
        val full = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("account_id", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add(JsonPrimitive("9".repeat(10_000)))
                    })
                })
            })
        }
        val numeric = ToolDefinition(
            function = ToolFunction(
                name = "numeric_lookup",
                description = "Look up an exact numeric account.",
                parameters = ToolParameters(properties = emptyMap()),
            ),
            fullParameters = ToolParameters(properties = emptyMap(), rawSchema = full),
            defer = DeferPolicy.AUTO,
        )

        val plan = McpDeferredToolProvider.plan(
            tools = listOf(numeric),
            contextTokens = 200_000,
            currentText = "numeric lookup",
        )

        assertTrue(plan.inlineTools.isEmpty())
        assertEquals(listOf("numeric_lookup"), plan.deferredTools.map { it.function.name })
        assertTrue(plan.wireSchemaTokens <= McpDeferredToolProvider.MAX_WIRE_SCHEMA_TOKENS)
    }

    @Test
    fun `large whitespace schema value cannot bypass final character budgets`() {
        val full = buildJsonObject {
            put("type", "object")
            put("description", " ".repeat(500_000))
        }
        val whitespace = ToolDefinition(
            function = ToolFunction(
                name = "whitespace_lookup",
                description = "Look up a value.",
                parameters = ToolParameters(properties = emptyMap()),
            ),
            fullParameters = ToolParameters(properties = emptyMap(), rawSchema = full),
            defer = DeferPolicy.AUTO,
        )

        val provider = McpDeferredToolProvider { _, _, _ -> "{}" }
        val plan = provider.prepare(
            requestId = "request",
            allTools = listOf(whitespace),
            contextTokens = 200_000,
            currentText = "whitespace lookup",
            recentTexts = emptyList(),
        )

        assertTrue(plan.inlineTools.isEmpty())
        assertEquals(
            listOf("whitespace_lookup"),
            plan.deferredTools.map { it.function.name },
        )
        val wireTools = plan.inlineTools + provider.definitions(
            GenerationContext(
                conversationId = "conversation",
                capabilityRequestId = "request",
            ),
        )
        assertTrue(
            McpDeferredToolProvider.estimateWireSchemaChars(wireTools) <=
                McpDeferredToolProvider.MAX_WIRE_SCHEMA_CHARS,
        )
    }

    @Test
    fun `forced name never overrides ALWAYS deferral`() {
        val always = tool("always_remote", defer = DeferPolicy.ALWAYS)

        val plan = McpDeferredToolProvider.plan(
            tools = listOf(always),
            contextTokens = 200_000,
            currentText = "use always remote",
            forcedDirectToolNames = setOf("always_remote"),
        )

        assertTrue(plan.inlineTools.isEmpty())
        assertEquals(listOf("always_remote"), plan.deferredTools.map { it.function.name })
        assertTrue(plan.usesBroker)
    }

    @Test
    fun `MCP result pager remains directly callable in a large registry`() {
        val plan = McpDeferredToolProvider.plan(
            tools = (1..80).map { tool("remote_$it") } +
                McpToolProvider.RESULT_PAGE_DEFINITION,
            contextTokens = 200_000,
            currentText = "hello",
        )

        assertTrue(
            plan.inlineTools.any { it.function.name == McpToolProvider.RESULT_PAGE },
        )
        assertFalse(
            plan.deferredTools.any { it.function.name == McpToolProvider.RESULT_PAGE },
        )
        assertTrue(plan.wireSchemaTokens <= McpDeferredToolProvider.MAX_WIRE_SCHEMA_TOKENS)
    }

    @Test
    fun `exact provider cap of protected tools fits without a broker`() {
        val tools = (1..64).map { index ->
            ToolDefinition(
                function = ToolFunction(
                    name = "m$index",
                    description = "",
                    parameters = ToolParameters(properties = emptyMap()),
                ),
                defer = DeferPolicy.NEVER,
            )
        }

        val plan = McpDeferredToolProvider.plan(
            tools,
            contextTokens = 200_000,
            currentText = "do work",
        )

        assertEquals(64, plan.inlineTools.size)
        assertTrue(plan.deferredTools.isEmpty())
        assertFalse(plan.usesBroker)
        assertEquals(64, plan.wireToolCount)
        assertTrue(plan.wireSchemaTokens > plan.inlineSchemaTokens)
        assertTrue(plan.wireSchemaTokens <= McpDeferredToolProvider.MAX_WIRE_SCHEMA_TOKENS)
    }

    @Test
    fun `estimate counts name description and schema`() {
        val cost = McpDeferredToolProvider.estimateSchemaTokens(
            listOf(tool("x", descriptionWords = 40)),
        )

        assertTrue("expected a non-trivial estimate, got $cost", cost > 20)
    }
}
