package com.newoether.agora.tool

import com.newoether.agora.api.DeferPolicy
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.util.DebugLog
import com.newoether.agora.util.TokenEstimator
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
 *   • total tools would exceed [MAX_INLINE_TOOLS] (provider cap), or
 *   • the estimated schema cost of deferrable tools exceeds 10% of the model's real token
 *     window, and the pool is big enough for the meta-tools to pay for themselves.
 *
 * When deferral is NOT triggered the provider returns an empty definitions list
 * and [McpToolProvider] keeps serving the full tool schemas as usual.
 */
class McpDeferredToolProvider(
    /**
     * Executes a tool once the model picks it via mcp_tool_invoke.
     *
     * Routes across *every* tool provider, not just MCP: built-in tools are deferrable too,
     * and they must stay executable while their schema is withheld.
     */
    private val deferredExecute: suspend (name: String, arguments: String, ctx: GenerationContext) -> String,
) : ToolProvider {

    // ── State set once per buildApiPath call ─────────────────────────────────

    @Volatile private var deferredTools: List<ToolDefinition> = emptyList()
    @Volatile private var inlineTools: List<ToolDefinition> = emptyList()
    @Volatile private var deferred: Boolean = false

    /**
     * Called by [GenerationManager] with the full MCP tool list before each generation.
     * Returns true if deferral was activated (caller should drop mcpTools from allTools
     * and use [definitions] instead), false if everything fits inline.
     */
    fun prepare(
        allTools: List<ToolDefinition>,
        contextTokens: Int,
    ): Boolean {
        val deferredNames = selectDeferred(allTools, contextTokens)
        // Keep the full pool addressable by mcp_tool_invoke: a tool that stayed inline is
        // still a legal invoke target, so callers can reach anything either way.
        deferredTools = allTools.filter { it.function.name in deferredNames }
        inlineTools = allTools.filter { it.function.name !in deferredNames }
        deferred = deferredTools.isNotEmpty()
        if (deferred) {
            DebugLog.d(
                "AgoraTiming",
                "tools deferred: ${deferredTools.size}/${allTools.size} " +
                    "(~${estimateSchemaTokens(deferredTools)} tok saved, " +
                    "inline=${inlineTools.size} ctx=$contextTokens)"
            )
        }
        return deferred
    }

    /** The tools that stayed inline for this request. */
    fun inlineTools(): List<ToolDefinition> = inlineTools

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
        return deferredExecute(toolName, toolArgs, ctx)
    }

    // ── Threshold logic ───────────────────────────────────────────────────────

    companion object {
        /** Hard cap on total inline tools (Anthropic and most providers limit to 64). */
        const val MAX_INLINE_TOOLS = 64

        /** Share of the model's real token window that inline tool schemas may occupy. */
        private const val DEFER_THRESHOLD_PCT = 10

        /** Used when models.dev has no `limit.context` for the model. */
        private const val FALLBACK_CONTEXT_TOKENS = 32_000

        /**
         * Below this many deferrable tools, inlining beats the discovery round-trip.
         * Guards against paying meta-tool overhead to hide a handful of small schemas.
         */
        private const val MIN_AUTO_DEFER_COUNT = 5

        /** Static cost of the three meta-tools; deferral must save at least this much. */
        private const val META_TOOLS_OVERHEAD_TOKENS = 500

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
            ), defer = DeferPolicy.NEVER),
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
            ), defer = DeferPolicy.NEVER),
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
            ), defer = DeferPolicy.NEVER),
        )

        /**
         * Estimated prompt-token cost of shipping [tools] inline.
         *
         * Counts what the model actually sees — name + description + serialised schema — via
         * the same [TokenEstimator] the UI uses, so the gate and the reported figures agree.
         */
        fun estimateSchemaTokens(tools: List<ToolDefinition>): Int {
            var total = 0
            for (tool in tools) {
                total += TokenEstimator.estimate(tool.function.name)
                total += TokenEstimator.estimate(tool.function.description)
                total += TokenEstimator.estimate(tool.function.parameters.asJsonObject().toString())
            }
            return total
        }

        /**
         * Which tools to withhold from the request, keyed by token budget rather than count.
         *
         * Counting tools was the original bug: seven built-in providers with verbose schemas
         * cost far more than a dozen terse MCP entries, yet only the MCP pool was ever gated
         * and `maxContextWindow` was accepted and ignored. The budget is now the real one —
         * [DEFER_THRESHOLD_PCT] of the context window, measured over *all* eligible tools.
         *
         * Two gates guard against a net-negative deferral, mirroring Cherry Studio:
         *   • [MIN_AUTO_DEFER_COUNT] — below this the discovery round-trip costs more than
         *     inlining would have.
         *   • [META_TOOLS_OVERHEAD_TOKENS] — the savings must at least pay for the three
         *     meta-tools' own schemas.
         *
         * [DeferPolicy.NEVER] tools are always inline; [DeferPolicy.ALWAYS] always deferred.
         */
        fun selectDeferred(
            tools: List<ToolDefinition>,
            contextTokens: Int,
        ): Set<String> {
            val always = tools.filter { it.defer == DeferPolicy.ALWAYS }
            val candidates = tools.filter { it.defer == DeferPolicy.AUTO }

            // Real token window from the models.dev catalog. NOT the `maxContextWindow`
            // setting — that one counts messages, and reading it as tokens is exactly the
            // bug that made the previous budget branch dead code.
            val ctx = if (contextTokens > 0) contextTokens else FALLBACK_CONTEXT_TOKENS
            val threshold = ctx * DEFER_THRESHOLD_PCT / 100
            val cost = estimateSchemaTokens(candidates)

            // Hard provider cap (Anthropic and most relays stop at 64) still forces deferral
            // even when the token budget alone would have allowed the pool through.
            val overProviderCap = tools.count { it.defer != DeferPolicy.ALWAYS } > MAX_INLINE_TOOLS

            val autoDeferred = when {
                overProviderCap -> candidates
                cost <= threshold -> emptyList()
                candidates.size < MIN_AUTO_DEFER_COUNT -> emptyList()
                cost <= META_TOOLS_OVERHEAD_TOKENS -> emptyList()
                else -> candidates
            }

            return (always + autoDeferred).map { it.function.name }.toSet()
        }
    }
}
