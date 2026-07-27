package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Deferred MCP tool exposure — mirrors Cherry Studio's tool deferral design.
 *
 * Problem: with 3 connectors (Notion 20 + Todoist 45 + built-ins ~16 = 81 tools)
 * the total schema easily exceeds provider limits (Anthropic caps at 64 tools;
 * large schemas also inflate prompt tokens unnecessarily on every request).
 *
 * Solution: when the MCP tool pool is large, replace the full schema list with
 * three lightweight meta-tools the model can use to discover and invoke any tool:
 *
 *   mcp_tool_search  — list available tools (name + one-line description)
 *   mcp_tool_inspect — fetch the full input schema for one tool by name
 *   mcp_tool_invoke  — execute a tool by name with a JSON arguments string
 *
 * The model pays a small extra round-trip on first use (search → inspect → invoke),
 * but the upfront token cost drops from O(all_schemas) to O(3_meta_tools) = ~200 tokens.
 * For infrequent tool use this is a net win; for heavy tool use the model can cache
 * the inspect result across calls in the same turn.
 *
 * Deferral triggers when:
 *   • total tools (built-ins + MCP) would exceed MAX_INLINE_TOOLS (64), OR
 *   • estimated MCP schema tokens exceed DEFER_THRESHOLD_PCT of the context window
 *     AND there are at least MIN_DEFER_COUNT MCP tools.
 *
 * When deferral is NOT triggered the provider returns an empty definitions list
 * and [McpToolProvider] keeps serving the full tool schemas as usual.
 */
class McpDeferredToolProvider(
    /** Executes the actual MCP call once the model picks a tool via mcp_tool_invoke. */
    private val mcpExecute: suspend (name: String, arguments: String, ctx: GenerationContext) -> String,
) : ToolProvider {

    // ── State set once per buildApiPath call ─────────────────────────────────

    @Volatile private var deferredTools: List<ToolDefinition> = emptyList()
    @Volatile private var deferred: Boolean = false

    /**
     * Called by [GenerationManager] with the full MCP tool list before each generation.
     * Returns true if deferral was activated (caller should drop mcpTools from allTools
     * and use [definitions] instead), false if everything fits inline.
     */
    fun prepare(
        mcpTools: List<ToolDefinition>,
        builtinToolCount: Int,
        maxContextWindow: Int,
    ): Boolean {
        deferredTools = mcpTools
        deferred = shouldDefer(mcpTools, builtinToolCount, maxContextWindow)
        if (deferred) {
            DebugLog.d(
                "AgoraTiming",
                "MCP deferred: ${mcpTools.size} tools -> 3 meta-tools " +
                    "(builtins=$builtinToolCount total=${builtinToolCount + mcpTools.size})"
            )
        }
        return deferred
    }

    fun isDeferred(): Boolean = deferred

    // ── ToolProvider interface ────────────────────────────────────────────────

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!deferred || deferredTools.isEmpty()) return emptyList()
        return META_TOOLS
    }

    override fun handles(name: String): Boolean = name in META_TOOL_NAMES

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        val args = try {
            Json.parseToJsonElement(arguments.ifBlank { "{}" }) as? JsonObject ?: JsonObject(emptyMap())
        } catch (_: Exception) {
            JsonObject(emptyMap())
        }
        return when (name) {
            TOOL_SEARCH  -> handleSearch(args)
            TOOL_INSPECT -> handleInspect(args)
            TOOL_INVOKE  -> handleInvoke(args, ctx)
            else         -> buildJsonObject { put("error", "unknown_meta_tool") }.toString()
        }
    }

    // ── Meta-tool handlers ───────────────────────────────────────────────────

    private fun handleSearch(args: JsonObject): String {
        val query = (args["query"] as? JsonPrimitive)?.content?.trim()?.lowercase()
        val tools = deferredTools.filter { tool ->
            if (query.isNullOrBlank()) true
            else tool.function.name.lowercase().contains(query) ||
                tool.function.description.lowercase().contains(query)
        }
        return buildJsonObject {
            put("total", tools.size)
            put("hint", "Call mcp_tool_inspect with a name to get the full schema, then mcp_tool_invoke to run it.")
            putJsonArray("tools") {
                tools.forEach { tool ->
                    add(buildJsonObject {
                        put("name", tool.function.name)
                        // First sentence of description only — keeps search results concise.
                        put("description", tool.function.description.substringBefore(". ").take(120))
                    })
                }
            }
        }.toString()
    }

    private fun handleInspect(args: JsonObject): String {
        val toolName = (args["name"] as? JsonPrimitive)?.content?.trim() ?: run {
            return buildJsonObject { put("error", "missing_name") }.toString()
        }
        val tool = deferredTools.firstOrNull { it.function.name == toolName } ?: run {
            return buildJsonObject {
                put("error", "tool_not_found")
                put("name", toolName)
                put("hint", "Call mcp_tool_search to see available tool names.")
            }.toString()
        }
        return buildJsonObject {
            put("name", tool.function.name)
            put("description", tool.function.description)
            put("input_schema", tool.function.parameters.rawSchema
                ?: buildJsonObject {
                    put("type", "object")
                    putJsonArray("required") {
                        tool.function.parameters.required.forEach { add(JsonPrimitive(it)) }
                    }
                    put("properties", buildJsonObject {
                        tool.function.parameters.properties.forEach { (k, v) ->
                            put(k, buildJsonObject {
                                put("type", v.type)
                                put("description", v.description)
                            })
                        }
                    })
                }
            )
        }.toString()
    }

    private suspend fun handleInvoke(args: JsonObject, ctx: GenerationContext): String {
        val toolName = (args["name"] as? JsonPrimitive)?.content?.trim() ?: run {
            return buildJsonObject { put("error", "missing_name") }.toString()
        }
        // Validate the tool exists in deferred set before forwarding
        if (deferredTools.none { it.function.name == toolName }) {
            return buildJsonObject {
                put("error", "tool_not_found")
                put("name", toolName)
                put("hint", "Call mcp_tool_search to discover available tools.")
            }.toString()
        }
        val toolArgs = (args["arguments"] as? JsonPrimitive)?.content?.trim() ?: "{}"
        return mcpExecute(toolName, toolArgs, ctx)
    }

    // ── Threshold logic ───────────────────────────────────────────────────────

    companion object {
        /** Hard cap on total inline tools (Anthropic and most providers limit to 64). */
        const val MAX_INLINE_TOOLS = 64

        /** Defer when MCP schemas alone take more than this fraction of the context window. */
        private const val DEFER_THRESHOLD_PCT = 10

        /** Don't bother deferring a tiny tool set — the meta round-trip costs more. */
        private const val MIN_DEFER_COUNT = 5

        const val TOOL_SEARCH  = "mcp_tool_search"
        const val TOOL_INSPECT = "mcp_tool_inspect"
        const val TOOL_INVOKE  = "mcp_tool_invoke"

        val META_TOOL_NAMES = setOf(TOOL_SEARCH, TOOL_INSPECT, TOOL_INVOKE)

        private val META_TOOLS: List<ToolDefinition> = listOf(
            ToolDefinition(function = ToolFunction(
                name = TOOL_SEARCH,
                description = "Discover available MCP tools by name/description. " +
                    "Call this first to find the right tool name, then use mcp_tool_inspect to get its schema.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "query" to ToolProperty("string",
                            "Optional keyword to filter tools by name or description.")
                    ),
                    required = emptyList()
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = TOOL_INSPECT,
                description = "Get the full input schema for a specific MCP tool. " +
                    "Call this after mcp_tool_search to understand a tool's parameters before invoking it.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "name" to ToolProperty("string", "Exact tool name as returned by mcp_tool_search.")
                    ),
                    required = listOf("name")
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = TOOL_INVOKE,
                description = "Execute an MCP tool by name. " +
                    "Use mcp_tool_inspect first to understand the required arguments.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "name"      to ToolProperty("string", "Exact tool name to invoke."),
                        "arguments" to ToolProperty("string", "JSON string of tool arguments matching the tool's input schema.")
                    ),
                    required = listOf("name", "arguments")
                )
            )),
        )

        fun shouldDefer(
            mcpTools: List<ToolDefinition>,
            builtinToolCount: Int,
            maxContextWindow: Int,
        ): Boolean {
            if (mcpTools.isEmpty()) return false
            // Hard cap: total tools exceed provider limit.
            if (builtinToolCount + mcpTools.size > MAX_INLINE_TOOLS) return true
            // Token budget: MCP schemas alone exceed DEFER_THRESHOLD_PCT of context.
            if (mcpTools.size < MIN_DEFER_COUNT) return false
            val schemaChars = mcpTools.sumOf { tool ->
                tool.function.description.length +
                    (tool.function.parameters.rawSchema?.toString()?.length
                        ?: tool.function.parameters.properties.values.sumOf {
                            it.type.length + it.description.length
                        })
            }
            val schemaTokens = schemaChars / 4
            // maxContextWindow is in messages; use a generous 4000-token-per-message proxy
            // for the context token budget. Threshold: 10% of estimated context tokens.
            val estimatedContextTokens = maxContextWindow * 4_000
            val threshold = estimatedContextTokens * DEFER_THRESHOLD_PCT / 100
            return schemaTokens > threshold
        }
    }
}
