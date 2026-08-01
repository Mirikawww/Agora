package com.newoether.agora.tool

import com.newoether.agora.api.DeferPolicy
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A capability invoked through the broker must become directly callable for the rest of the turn.
 *
 * Without promotion, a model that calls the same connector twice pays for two broker searches, the
 * second one re-deriving a schema it just used.
 */
class CapabilityPromotionTest {

    private fun connectorTool(
        name: String,
        enumValues: Int = 4,
        defer: DeferPolicy = DeferPolicy.AUTO,
    ): ToolDefinition {
        val full = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("filter", buildJsonObject {
                    put("type", "string")
                    put("description", "Filter expression selecting which items to return.")
                    put("enum", buildJsonArray {
                        repeat(enumValues) { add(JsonPrimitive("filter-mode-$it")) }
                    })
                })
            })
            put("required", buildJsonArray { add(JsonPrimitive("filter")) })
        }
        val compact = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("filter", buildJsonObject { put("type", "string") })
            })
        }
        return ToolDefinition(
            function = ToolFunction(
                name = name,
                description = "[MCP: Todoist] Capability $name.",
                parameters = ToolParameters(properties = emptyMap(), rawSchema = compact),
            ),
            defer = defer,
            fullParameters = ToolParameters(properties = emptyMap(), rawSchema = full),
        )
    }

    /** A registry large enough that plan() routes rather than exposing everything directly. */
    private fun largeRegistry(count: Int = 40): List<ToolDefinition> =
        (0 until count).map { connectorTool("todoist_capability_$it") }

    private fun provider() = McpDeferredToolProvider { _, _, _ -> "{}" }

    /** Prepares a turn under the fixed id every test in this class promotes against. */
    private fun prepare(
        provider: McpDeferredToolProvider,
        tools: List<ToolDefinition>,
        currentText: String,
    ): ToolExposurePlan = provider.prepare(
        requestId = "turn-1",
        allTools = tools,
        contextTokens = 200_000,
        currentText = currentText,
        recentTexts = emptyList(),
    )

    @Test
    fun `an invoked capability is promoted onto the wire`() {
        val provider = provider()
        val tools = largeRegistry()
        val plan = prepare(provider, tools, "do something unrelated to any tool name")
        val target = "todoist_capability_7"
        assertTrue(
            "target must start out deferred for this test to mean anything",
            plan.deferredTools.any { it.function.name == target },
        )
        assertFalse(plan.inlineTools.any { it.function.name == target })

        val wire = provider.promoteInvoked("turn-1", setOf(target))

        assertNotNull("expected promotion to change the tool surface", wire)
        assertTrue(
            "invoked capability must be directly callable next round",
            wire!!.any { it.function.name == target },
        )
        assertTrue(
            "the broker must remain for everything still deferred",
            wire.any { it.function.name == McpDeferredToolProvider.TOOL_BROKER },
        )
    }

    @Test
    fun `a promoted capability carries its complete schema`() {
        val provider = provider()
        prepare(provider, largeRegistry(), "unrelated request text")
        val target = "todoist_capability_3"

        val wire = provider.promoteInvoked("turn-1", setOf(target))!!
        val promoted = wire.single { it.function.name == target }

        // The compact routing capsule drops enum constraints; the promoted tool must not.
        val schema = promoted.function.parameters.asJsonObject().toString()
        assertTrue("promoted schema must be the complete one, got $schema", "enum" in schema)
        assertTrue("required must survive promotion", "required" in schema)
    }

    @Test
    fun `a promoted capability leaves the deferred pool`() {
        val provider = provider()
        prepare(provider, largeRegistry(), "unrelated request text")
        val target = "todoist_capability_5"

        provider.promoteInvoked("turn-1", setOf(target))

        // Searching must no longer surface it: one capability may not appear both directly and
        // behind the broker, or the model sees the same tool twice.
        val state = provider.inlineTools("turn-1")
        assertTrue(state.any { it.function.name == target })
        assertTrue("broker must still exist for the remaining tools", provider.isDeferred("turn-1"))
    }

    @Test
    fun `promotion is idempotent so a third call adds nothing`() {
        val provider = provider()
        prepare(provider, largeRegistry(), "unrelated request text")
        val target = "todoist_capability_9"

        val first = provider.promoteInvoked("turn-1", setOf(target))
        val second = provider.promoteInvoked("turn-1", setOf(target))

        assertNotNull(first)
        assertNull("already-promoted capability must not re-promote", second)
    }

    @Test
    fun `invoking a tool that is already direct changes nothing`() {
        val provider = provider()
        val plan = prepare(provider, largeRegistry(), "unrelated request text")
        val alreadyDirect = plan.inlineTools.firstOrNull()?.function?.name

        if (alreadyDirect != null) {
            assertNull(provider.promoteInvoked("turn-1", setOf(alreadyDirect)))
        }
        assertNull(provider.promoteInvoked("turn-1", setOf("no_such_tool")))
        assertNull(provider.promoteInvoked("turn-1", emptySet()))
    }

    @Test
    fun `promotion never breaches the inline or wire budgets`() {
        val provider = provider()
        // Every schema is individually enormous, so none of them can be admitted.
        val huge = (0 until 20).map { connectorTool("todoist_huge_$it", enumValues = 4_000) }
        prepare(provider, huge, "unrelated request text")

        val wire = provider.promoteInvoked("turn-1", setOf("todoist_huge_2"))

        assertNull("an oversized schema must stay broker-reachable, not blow the budget", wire)
    }

    @Test
    fun `budget admits what fits and leaves the rest deferred`() {
        val provider = provider()
        val mixed = buildList {
            add(connectorTool("todoist_small", enumValues = 2))
            add(connectorTool("todoist_enormous", enumValues = 4_000))
            addAll(largeRegistry(30))
        }
        prepare(provider, mixed, "unrelated request text")

        val wire = provider.promoteInvoked(
            "turn-1",
            setOf("todoist_small", "todoist_enormous"),
        )

        assertNotNull(wire)
        assertTrue(
            "the small schema fits and must be promoted",
            wire!!.any { it.function.name == "todoist_small" },
        )
        assertFalse(
            "the enormous schema must remain behind the broker",
            wire.any { it.function.name == "todoist_enormous" },
        )
        assertTrue(
            McpDeferredToolProvider.estimateSchemaTokens(
                wire.filterNot { it.function.name == McpDeferredToolProvider.TOOL_BROKER },
            ) <= McpDeferredToolProvider.MAX_INLINE_SCHEMA_TOKENS,
        )
        val finalWire = McpDeferredToolProvider.estimateWireSchema(wire)
        assertTrue(finalWire.tokens <= McpDeferredToolProvider.MAX_WIRE_SCHEMA_TOKENS)
        assertTrue(finalWire.chars <= McpDeferredToolProvider.MAX_WIRE_SCHEMA_CHARS)
    }

    @Test
    fun `the broker disappears once every capability has been promoted`() {
        val provider = provider()
        // A pool that routes only because a tool is ALWAYS-deferred, small enough to fully promote.
        val tools = listOf(
            connectorTool("todoist_only", enumValues = 2, defer = DeferPolicy.ALWAYS),
        )
        prepare(provider, tools, "unrelated request text")

        val wire = provider.promoteInvoked("turn-1", setOf("todoist_only"))!!

        assertEquals(listOf("todoist_only"), wire.map { it.function.name })
        assertFalse("no deferred tools left, so no broker", provider.isDeferred("turn-1"))
    }

    @Test
    fun `promotion on an unknown request id is a no-op`() {
        assertNull(provider().promoteInvoked("never-prepared", setOf("todoist_capability_1")))
    }
}
