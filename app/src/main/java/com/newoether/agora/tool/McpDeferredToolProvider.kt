package com.newoether.agora.tool

import com.newoether.agora.api.DeferPolicy
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.util.DebugLog
import com.newoether.agora.util.TokenEstimator
import com.newoether.agora.viewmodel.GenerationContext
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Per-conversation capability exposure.
 *
 * Large registries are represented by a handful of directly relevant tools plus one compact
 * broker. The broker is not a lossy replacement: it can search, return the complete schema, and
 * invoke every enabled tool. Small registries remain direct because an extra tool round would cost
 * more than their schemas.
 *
 * State is keyed by conversation. Agora permits concurrent generations, so a process-global
 * `deferredTools` list would let one conversation search or invoke another conversation's tools.
 */
class McpDeferredToolProvider(
    private val deferredExecute: suspend (
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ) -> String,
) : ToolProvider {

    private data class RequestState(
        val allTools: List<ToolDefinition>,
        val inlineTools: List<ToolDefinition>,
        val deferredTools: List<ToolDefinition>,
        val brokerDefinition: ToolDefinition?,
        val route: CapabilityRoute,
    )

    private val states = ConcurrentHashMap<String, RequestState>()

    /**
     * Builds and stores the exposure plan for one generation.
     *
     * [contextTokens] is retained for diagnostics. Upload cost is absolute, not a percentage of a
     * context window, so it no longer weakens routing merely because a model has a 1M-token window.
     */
    fun prepare(
        requestId: String,
        allTools: List<ToolDefinition>,
        contextTokens: Int,
        currentText: String,
        recentTexts: List<String>,
    ): ToolExposurePlan {
        val plan = plan(
            tools = allTools,
            contextTokens = contextTokens,
            currentText = currentText,
            recentTexts = recentTexts,
        )
        val broker = if (plan.deferredTools.isNotEmpty()) {
            brokerDefinition(allTools)
        } else {
            null
        }
        states[requestId] = RequestState(
            allTools = allTools,
            inlineTools = plan.inlineTools,
            deferredTools = plan.deferredTools,
            brokerDefinition = broker,
            route = plan.route,
        )
        // android.util.Log is a stub in local JVM tests; diagnostics must never affect routing.
        runCatching {
            DebugLog.d(
                "AgoraTiming",
                "capability route=${plan.route.mode} direct=${plan.inlineTools.size} " +
                    "broker=${broker != null} deferred=${plan.deferredTools.size}/${allTools.size} " +
                    "schema=${plan.inlineSchemaTokens}tok ctx=$contextTokens",
            )
        }
        return plan
    }

    fun inlineTools(requestId: String): List<ToolDefinition> =
        states[requestId]?.inlineTools.orEmpty()

    fun isDeferred(requestId: String): Boolean =
        states[requestId]?.deferredTools?.isNotEmpty() == true

    fun clear(requestId: String) {
        states.remove(requestId)
    }

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> =
        state(ctx)?.brokerDefinition?.let(::listOf).orEmpty()

    override fun handles(name: String): Boolean = name in META_TOOL_NAMES

    override suspend fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String {
        val state = state(ctx) ?: return errorJson(
            code = "capability_state_missing",
            hint = "The generation's capability registry is no longer active.",
        )
        val args = parseObject(arguments)
        return when (name) {
            TOOL_BROKER -> when (args.string("action")?.lowercase()) {
                ACTION_SEARCH -> handleSearch(state, args)
                ACTION_INSPECT -> handleInspect(state, args)
                ACTION_INVOKE -> handleInvoke(state, args, ctx)
                else -> errorJson(
                    code = "invalid_action",
                    hint = "Use action=search, action=inspect, or action=invoke.",
                )
            }
            // Compatibility for tool-call history created by older Agora builds.
            LEGACY_SEARCH -> handleSearch(state, args)
            LEGACY_INSPECT -> handleInspect(state, args)
            LEGACY_INVOKE -> handleInvoke(state, args, ctx)
            else -> errorJson("unknown_capability_tool")
        }
    }

    private fun state(ctx: GenerationContext): RequestState? =
        (ctx.capabilityRequestId ?: ctx.conversationId)?.let(states::get)

    private fun handleSearch(state: RequestState, args: JsonObject): String {
        val query = args.string("query").orEmpty()
        val ranked = CapabilityRouter.rank(query, emptyList(), state.allTools)
        val cursor = args.int("cursor").coerceAtLeast(0)
        val limit = args.int("limit", DEFAULT_SEARCH_RESULTS).coerceIn(1, MAX_SEARCH_RESULTS)
        // Keep zero-score entries after relevant matches. This makes every enabled tool reachable
        // through cursor pagination even when a new connector uses vocabulary the local router
        // has never seen.
        val matches = ranked.drop(cursor).take(limit).map { it.tool }
        val nextCursor = (cursor + matches.size).takeIf { it < ranked.size }
        val lexicalMatches = ranked.count { it.score > 0 }

        return buildJsonObject {
            put("query", query)
            put("total", ranked.size)
            put("lexical_matches", lexicalMatches)
            put("cursor", cursor)
            nextCursor?.let { put("next_cursor", it) }
            put(
                "hint",
                if (matches.isEmpty()) {
                    "No more results. Search with a connector or operation name."
                } else {
                    "Use action=inspect with an exact name for its full schema, then action=invoke. " +
                        "Continue with next_cursor when present."
                },
            )
            putJsonArray("tools") {
                matches.forEach { tool ->
                    add(buildJsonObject {
                        put("name", tool.function.name)
                        put("description", tool.function.description.take(240))
                    })
                }
            }
        }.toString()
    }

    private fun handleInspect(state: RequestState, args: JsonObject): String {
        val name = args.string("name") ?: return errorJson("missing_name")
        val tool = state.allTools.firstOrNull { it.function.name == name }
            ?: return errorJson("tool_not_found", "Search enabled capabilities first.")
        val schema = tool.completeParameters().asJsonObject()
        val schemaText = schema.toString()
        val cursor = args.int("cursor").coerceIn(0, schemaText.length)
        val maxChars = args.int("max_chars", DEFAULT_SCHEMA_CHUNK_CHARS)
            .coerceIn(MIN_SCHEMA_CHUNK_CHARS, MAX_SCHEMA_CHUNK_CHARS)
        val end = (cursor + maxChars).coerceAtMost(schemaText.length)
        val isCompleteObject = cursor == 0 && end == schemaText.length
        return buildJsonObject {
            put("name", tool.function.name)
            put("description", tool.function.description)
            put("complete", end == schemaText.length)
            if (isCompleteObject) {
                put("input_schema", schema)
            } else {
                // A JSON string chunk keeps the outer tool result valid; blindly taking the first
                // 100k characters would produce malformed JSON and silently lose schema suffixes.
                put("input_schema_json_chunk", schemaText.substring(cursor, end))
                put("cursor", cursor)
                if (end < schemaText.length) put("next_cursor", end)
            }
        }.toString()
    }

    private suspend fun handleInvoke(
        state: RequestState,
        args: JsonObject,
        ctx: GenerationContext,
    ): String {
        val name = args.string("name") ?: return errorJson("missing_name")
        if (state.allTools.none { it.function.name == name }) {
            return errorJson("tool_not_found", "Search enabled capabilities first.")
        }
        val toolArgs = when (val value = args["arguments"]) {
            null -> "{}"
            is JsonPrimitive -> value.contentOrNull?.takeIf(String::isNotBlank) ?: "{}"
            else -> value.toString()
        }
        return deferredExecute(name, toolArgs, ctx)
    }

    companion object {
        /** Provider wire cap, including the broker itself. */
        const val MAX_WIRE_TOOLS = 64

        /** Pools at or above this size use Top-K + broker even when every schema is terse. */
        const val LARGE_REGISTRY_TOOLS = 12

        /** Absolute schema budget for a direct pool; context-window size does not change upload. */
        const val MAX_INLINE_SCHEMA_TOKENS = 1_500

        const val TOP_K_DIRECT_TOOLS = 6
        private const val DEFAULT_SEARCH_RESULTS = 10
        private const val MAX_SEARCH_RESULTS = 20
        private const val DEFAULT_SCHEMA_CHUNK_CHARS = 30_000
        private const val MIN_SCHEMA_CHUNK_CHARS = 1_000
        private const val MAX_SCHEMA_CHUNK_CHARS = 40_000

        const val TOOL_BROKER = "agora_capabilities"
        const val LEGACY_SEARCH = "mcp_tool_search"
        const val LEGACY_INSPECT = "mcp_tool_inspect"
        const val LEGACY_INVOKE = "mcp_tool_invoke"
        const val ACTION_SEARCH = "search"
        const val ACTION_INSPECT = "inspect"
        const val ACTION_INVOKE = "invoke"

        val META_TOOL_NAMES = setOf(
            TOOL_BROKER,
            LEGACY_SEARCH,
            LEGACY_INSPECT,
            LEGACY_INVOKE,
        )

        fun estimateSchemaTokens(tools: List<ToolDefinition>): Int = tools.sumOf { tool ->
            TokenEstimator.estimate(tool.function.name) +
                TokenEstimator.estimate(tool.function.description) +
                TokenEstimator.estimate(tool.function.parameters.asJsonObject().toString())
        }

        fun plan(
            tools: List<ToolDefinition>,
            contextTokens: Int,
            currentText: String,
            recentTexts: List<String> = emptyList(),
        ): ToolExposurePlan {
            if (tools.isEmpty()) {
                val route = CapabilityRouter.route(currentText, recentTexts, tools)
                return ToolExposurePlan(route, emptyList(), emptyList(), 0)
            }

            val directEligible = tools
                .filter { it.defer != DeferPolicy.ALWAYS }
                .sortedBy { it.function.name }
            val directComplete = directEligible.map { it.withCompleteSchema() }
            val totalSchemaTokens = estimateSchemaTokens(directComplete)
            val mustRoute = tools.any { it.defer == DeferPolicy.ALWAYS } ||
                directEligible.size >= LARGE_REGISTRY_TOOLS ||
                directEligible.size > MAX_WIRE_TOOLS ||
                totalSchemaTokens > MAX_INLINE_SCHEMA_TOKENS

            if (!mustRoute) {
                val route = CapabilityRoute(
                    mode = CapabilityRouteMode.DIRECT,
                    selectedToolNames = directEligible.map { it.function.name },
                    confidence = 1f,
                    reason = "Registry is already smaller than the broker threshold.",
                    schemaTokenEstimate = totalSchemaTokens,
                    requiresBroker = false,
                )
                return ToolExposurePlan(
                    route = route,
                    inlineTools = directComplete,
                    deferredTools = tools.filter { it.defer == DeferPolicy.ALWAYS },
                    inlineSchemaTokens = estimateSchemaTokens(
                        directComplete,
                    ),
                )
            }

            val route = CapabilityRouter.route(
                currentText = currentText,
                recentTexts = recentTexts,
                tools = tools.filter { it.defer == DeferPolicy.AUTO },
                topK = TOP_K_DIRECT_TOOLS,
            )
            val mandatory = tools
                .filter { it.defer == DeferPolicy.NEVER }
                .sortedBy { it.function.name }
            require(mandatory.size < MAX_WIRE_TOOLS) {
                "Cannot expose ${mandatory.size} NEVER-deferred tools plus the capability broker " +
                    "within the $MAX_WIRE_TOOLS-tool provider limit."
            }
            val selected = route.selectedToolsFrom(tools)
                .filter { it.defer == DeferPolicy.AUTO }
            // Reserve one of the provider's 64 slots for the broker. If an impossible registry
            // contains 64+ NEVER items, the overflow remains broker-reachable instead of vanishing.
            val inline = (mandatory + selected)
                .distinctBy { it.function.name }
                .take(MAX_WIRE_TOOLS - 1)
                .map { it.withCompleteSchema() }
            val inlineNames = inline.map { it.function.name }.toSet()
            val deferred = tools.filter { it.function.name !in inlineNames }
            return ToolExposurePlan(
                route = route.copy(requiresBroker = deferred.isNotEmpty()),
                inlineTools = inline,
                deferredTools = deferred,
                inlineSchemaTokens = estimateSchemaTokens(inline),
            )
        }

        /**
         * Compatibility helper for existing tests and callers. New code should use [plan], because
         * selecting relevant tools without the current request is necessarily less precise.
         */
        fun selectDeferred(
            tools: List<ToolDefinition>,
            contextTokens: Int,
        ): Set<String> = plan(
            tools = tools,
            contextTokens = contextTokens,
            currentText = "",
        ).deferredTools.map { it.function.name }.toSet()

        private fun brokerDefinition(tools: List<ToolDefinition>): ToolDefinition {
            val labels = capabilityLabels(tools)
            val catalog = labels.joinToString(", ").ifBlank { "enabled Agora tools" }
            return ToolDefinition(
                function = ToolFunction(
                    name = TOOL_BROKER,
                    description = "Access enabled capabilities not directly listed in this request " +
                        "($catalog). Search by the user's intent for short summaries, inspect one " +
                        "exact name for its full paginated schema, then invoke it. Search results " +
                        "are cursor-paginated across the complete registry; never claim a capability " +
                        "is unavailable before exhausting the relevant search.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "action" to ToolProperty("string", "search, inspect, or invoke"),
                            "query" to ToolProperty("string", "For search: the user's concrete intent."),
                            "name" to ToolProperty("string", "For inspect/invoke: exact result name."),
                            "arguments" to ToolProperty("object", "For invoke: arguments matching the returned schema."),
                            "cursor" to ToolProperty("integer", "For paginated search/inspect: next_cursor."),
                            "limit" to ToolProperty("integer", "For search: summaries per page, 1-20."),
                            "max_chars" to ToolProperty("integer", "For inspect: schema chunk size, 1000-40000."),
                        ),
                        required = listOf("action"),
                        rawSchema = buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("action", buildJsonObject {
                                    put("type", "string")
                                    put("enum", buildJsonArray {
                                        add(JsonPrimitive(ACTION_SEARCH))
                                        add(JsonPrimitive(ACTION_INSPECT))
                                        add(JsonPrimitive(ACTION_INVOKE))
                                    })
                                })
                                put("query", buildJsonObject {
                                    put("type", "string")
                                    put("description", "User intent to search for.")
                                })
                                put("name", buildJsonObject {
                                    put("type", "string")
                                    put("description", "Exact capability name for inspect or invoke.")
                                })
                                put("arguments", buildJsonObject {
                                    put("type", "object")
                                    put("description", "Arguments matching the returned input_schema.")
                                })
                                put("cursor", buildJsonObject {
                                    put("type", "integer")
                                    put("description", "Pagination cursor returned by search or inspect.")
                                })
                                put("limit", buildJsonObject {
                                    put("type", "integer")
                                    put("description", "Search page size from 1 to 20.")
                                })
                                put("max_chars", buildJsonObject {
                                    put("type", "integer")
                                    put("description", "Inspect chunk size from 1000 to 40000 characters.")
                                })
                            })
                            putJsonArray("required") { add(JsonPrimitive("action")) }
                        },
                    ),
                ),
                defer = DeferPolicy.NEVER,
            )
        }

        private fun capabilityLabels(tools: List<ToolDefinition>): List<String> {
            val text = tools.joinToString(" ") {
                "${it.function.name} ${it.function.description}"
            }.lowercase()
            val customMcpNames = tools.mapNotNull { tool ->
                Regex("""\[MCP:\s*([^]]+)]""", RegexOption.IGNORE_CASE)
                    .find(tool.function.description)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
            }
            return buildList {
                if ("todoist" in text) add("Todoist")
                if ("notion" in text) add("Notion")
                if ("github" in text) add("GitHub")
                addAll(customMcpNames.sortedWith(String.CASE_INSENSITIVE_ORDER))
                if ("web_search" in text || "web_fetch" in text) add("web")
                if ("memory" in text) add("memory")
                if ("conversation" in text) add("past conversations")
                if ("file_" in text) add("files")
                if ("shell" in text) add("shell")
                if ("skill" in text) add("Skills")
                if ("image" in text) add("images")
                if ("ask_user" in text) add("Ask")
                if ("user_profile" in text || "personalization" in text) add("personalization")
                if ("provider_balance" in text) add("provider balances")
                if ("mcp_list_resources" in text || "mcp_read_resource" in text) add("MCP resources")
                if ("mcp_list_prompts" in text || "mcp_get_prompt" in text) add("MCP prompts")
            }.distinct()
        }

        private fun parseObject(value: String): JsonObject = try {
            Json.parseToJsonElement(value.ifBlank { "{}" }) as? JsonObject ?: JsonObject(emptyMap())
        } catch (_: Exception) {
            JsonObject(emptyMap())
        }

        private fun JsonObject.string(name: String): String? =
            (get(name) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)

        private fun JsonObject.int(name: String, default: Int = 0): Int =
            (get(name) as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: default

        private fun errorJson(code: String, hint: String? = null): String =
            buildJsonObject {
                put("error", code)
                hint?.let { put("hint", it) }
            }.toString()
    }
}

data class ToolExposurePlan(
    val route: CapabilityRoute,
    val inlineTools: List<ToolDefinition>,
    val deferredTools: List<ToolDefinition>,
    val inlineSchemaTokens: Int,
) {
    val usesBroker: Boolean get() = deferredTools.isNotEmpty()
    val wireToolCount: Int get() = inlineTools.size + if (usesBroker) 1 else 0
}

/** Complete schema is populated for MCP tools; built-ins already carry their only schema. */
private fun ToolDefinition.completeParameters(): ToolParameters =
    fullParameters ?: function.parameters

private fun ToolDefinition.withCompleteSchema(): ToolDefinition {
    val complete = fullParameters ?: return this
    return copy(function = function.copy(parameters = complete))
}
