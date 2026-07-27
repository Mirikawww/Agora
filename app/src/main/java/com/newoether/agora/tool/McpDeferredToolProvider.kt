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
 * **No tool is ever removed.** Deferral changes the *entry point*, not the capability:
 * every MCP tool stays reachable through mcp_tool_invoke, which forwards to the same
 * execution path a direct call would take. This is the difference between deferral and
 * truncating the list to fit a provider cap — truncation would genuinely cost the model
 * tools, deferral costs it one discovery round-trip.
 *
 * Deferral triggers when either:
 *   • total tools (built-ins + MCP) would exceed [MAX_INLINE_TOOLS], or
 *   • the MCP pool alone exceeds [MAX_INLINE_MCP_TOOLS].
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

        /**
         * Maximum MCP tools to send inline before switching to meta-tool discovery.
         *
         * Above this the pool is deferred even when the total stays under [MAX_INLINE_TOOLS].
         * 8 gives room for 16 built-in tools (8 + 16 = 24, well under 64) while still
         * triggering on any moderately-sized Notion/Todoist connector deployment.
         * Keeping this low caps the per-request token cost from MCP schemas regardless of
         * provider limit — a 40-tool pool at ~200 tokens each is 8 000 tokens for tools the
         * model will almost certainly never call in a single turn.
         */
        private const val MAX_INLINE_MCP_TOOLS = 8

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

        /**
         * Whether to hide the MCP pool behind the meta-tools.
         *
         * The previous token-budget branch was broken: it read [maxContextWindow] as a token
         * count and multiplied it by 4000, but that setting is a **message count** (default 20).
         * The resulting threshold (~8000 tokens) sat right around what a trimmed 70-tool pool
         * actually costs, so deferral silently never fired and every request still shipped the
         * full schema list. Tool *count* is the honest signal here and needs no estimation.
         */
        fun shouldDefer(
            mcpTools: List<ToolDefinition>,
            builtinToolCount: Int,
            @Suppress("UNUSED_PARAMETER") maxContextWindow: Int,
        ): Boolean {
            if (mcpTools.isEmpty()) return false
            // Hard cap: total tools exceed the provider limit (Anthropic and most relays cap at 64).
            if (builtinToolCount + mcpTools.size > MAX_INLINE_TOOLS) return true
            // Schema volume: MCP schemas run hundreds of tokens each (Notion, Todoist), so a
            // pool past this size is not worth shipping inline on a turn that may never call it.
            return mcpTools.size > MAX_INLINE_MCP_TOOLS
        }
    }
}
