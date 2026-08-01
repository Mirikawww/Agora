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
import kotlinx.serialization.json.JsonArray
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
 * broker. The broker is not a lossy replacement: deferred tools remain searchable, inspectable,
 * and invokable, while inline tools remain directly callable. Small registries remain direct
 * because an extra tool round would cost more than their schemas.
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
        recentSuccessfulToolNames: Set<String> = emptySet(),
        forcedDirectToolNames: Set<String> = emptySet(),
    ): ToolExposurePlan {
        val plan = plan(
            tools = allTools,
            contextTokens = contextTokens,
            currentText = currentText,
            recentTexts = recentTexts,
            recentSuccessfulToolNames = recentSuccessfulToolNames,
            forcedDirectToolNames = forcedDirectToolNames,
        )
        val broker = if (plan.deferredTools.isNotEmpty()) {
            brokerDefinition(plan.deferredTools)
        } else {
            null
        }
        states[requestId] = RequestState(
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
                    "inlineSchema=${plan.inlineSchemaTokens}tok " +
                    "wireSchema=${plan.wireSchemaTokens}tok ctx=$contextTokens",
            )
        }
        return plan
    }

    fun inlineTools(requestId: String): List<ToolDefinition> =
        states[requestId]?.inlineTools.orEmpty()

    fun isDeferred(requestId: String): Boolean =
        states[requestId]?.deferredTools?.isNotEmpty() == true

    /**
     * Moves capabilities the model has already invoked onto the wire for the rest of the turn.
     *
     * The exposure plan is computed once per turn, before the model has said anything. A tool it
     * then discovers through the broker stays deferred for every following round, so calling the
     * same connector twice costs two extra broker searches — the second one re-deriving a schema
     * the model just used. Promotion removes that repeat: once a capability is proven relevant by
     * an actual invocation, its complete schema is worth the wire budget it occupies.
     *
     * The promoted tool is dropped from the deferred pool and the broker is rebuilt without it, so
     * the model cannot see one capability in both places. Promotion is skipped when the complete
     * schema does not fit the same inline and wire budgets [plan] enforces — an oversized schema
     * remains broker-reachable rather than silently blowing the request past its cap.
     *
     * Returns the tool surface for the next round, or null when nothing changed.
     */
    fun promoteInvoked(requestId: String, invokedNames: Set<String>): List<ToolDefinition>? {
        if (invokedNames.isEmpty()) return null
        val state = states[requestId] ?: return null
        val promotable = state.deferredTools.filter { it.function.name in invokedNames }
        if (promotable.isEmpty()) return null

        var inline = state.inlineTools
        var deferred = state.deferredTools
        var promotedAny = false
        // Admit one at a time against the live budget: two promoted connector schemas may fit
        // individually but not together, and the first one proven relevant should win.
        for (candidate in promotable.sortedBy { it.function.name }) {
            val complete = candidate.withCompleteSchema()
            val nextInline = inline + complete
            val nextDeferred = deferred.filterNot { it.function.name == candidate.function.name }
            val inlineLimit = if (nextDeferred.isEmpty()) MAX_WIRE_TOOLS else MAX_WIRE_TOOLS - 1
            if (nextInline.size > inlineLimit) continue
            if (estimateSchemaTokens(nextInline) > MAX_INLINE_SCHEMA_TOKENS) continue
            if (estimateSchemaChars(nextInline) > MAX_INLINE_SCHEMA_CHARS) continue
            val nextBroker = nextDeferred.takeIf { it.isNotEmpty() }?.let(::brokerDefinition)
            val wire = estimateWireSchema(
                if (nextBroker == null) nextInline else nextInline + nextBroker,
            )
            if (wire.tokens > MAX_WIRE_SCHEMA_TOKENS) continue
            if (wire.chars > MAX_WIRE_SCHEMA_CHARS) continue
            inline = nextInline
            deferred = nextDeferred
            promotedAny = true
        }
        if (!promotedAny) return null

        val broker = deferred.takeIf { it.isNotEmpty() }?.let(::brokerDefinition)
        states[requestId] = state.copy(
            inlineTools = inline,
            deferredTools = deferred,
            brokerDefinition = broker,
        )
        return if (broker == null) inline else inline + broker
    }

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
        val ranked = CapabilityRouter.rank(query, emptyList(), state.deferredTools)
        val cursor = args.int("cursor").coerceIn(0, ranked.size)
        val limit = args.int("limit", DEFAULT_SEARCH_RESULTS).coerceIn(1, MAX_SEARCH_RESULTS)
        val lexicalMatches = ranked.count { it.score > 0 }
        // Do not pad a relevant page with zero-score capsules: one exact match should cost one
        // capsule, not the default six. The absolute cursor still opens the zero-score tail on the
        // next page, so tools using unfamiliar vocabulary remain fully reachable.
        val pageEnd = when {
            cursor < lexicalMatches -> minOf(cursor + limit, lexicalMatches)
            else -> minOf(cursor + limit, ranked.size)
        }
        val matches = ranked.subList(cursor, pageEnd).map { it.tool }
        val nextCursor = (cursor + matches.size).takeIf { it < ranked.size }

        return buildJsonObject {
            put("query", query.take(MAX_SEARCH_QUERY_CHARS))
            if (query.length > MAX_SEARCH_QUERY_CHARS) put("query_truncated", true)
            put("total", ranked.size)
            put("lexical_matches", lexicalMatches)
            put("cursor", cursor)
            nextCursor?.let { put("next_cursor", it) }
            put(
                "hint",
                if (matches.isEmpty()) {
                    "No more results. Search with a connector or operation name."
                } else {
                    "Invoke directly when inspect_for_required_constraints is false. Inspect only " +
                        "when it is true, or when omitted optional constraints are needed. Continue " +
                        "with next_cursor for less relevant fallback results."
                },
            )
            putJsonArray("tools") {
                matches.forEach { tool ->
                    add(buildJsonObject {
                        put("name", tool.function.name)
                        put("description", tool.function.description.take(MAX_SEARCH_DESCRIPTION_CHARS))
                        put("input_hint", compactInputHint(tool))
                    })
                }
            }
        }.toString()
    }

    /**
     * A bounded call signature for the common search -> invoke path. It intentionally omits long
     * descriptions and deep constraints; those remain losslessly available through inspect.
     */
    private fun compactInputHint(tool: ToolDefinition): JsonObject {
        val schema = tool.completeParameters().asJsonObject()
        val requiredOrder = (schema["required"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.distinct()
            .orEmpty()
        val required = requiredOrder.toSet()
        val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val orderedProperties = buildList {
            requiredOrder.forEach { name ->
                properties[name]?.let { add(name to it) }
            }
            properties.entries
                .filter { it.key !in required }
                .forEach { add(it.key to it.value) }
        }
        var inspectForRequiredConstraints =
            schema.keys.any { it in COMPLEX_SCHEMA_KEYS || it !in SIMPLE_ROOT_HINT_KEYS }
        if (required.any { it !in properties }) inspectForRequiredConstraints = true
        var inspectForOptionalConstraints = false
        var hintChars = 0
        var includedProperties = 0
        var omittedRequiredProperties = 0
        var omittedOptionalProperties = 0

        return buildJsonObject {
            put("properties", buildJsonObject {
                orderedProperties.forEach { (name, value) ->
                    val isRequired = name in required
                    val property = value as? JsonObject
                    val rawType = property?.get("type")
                    val type = ((rawType as? JsonPrimitive)?.contentOrNull ?: "any")
                        .take(MAX_HINT_TYPE_CHARS)
                    val itemSchema = property?.get("items") as? JsonObject
                    val itemType = (itemSchema?.get("type") as? JsonPrimitive)
                        ?.contentOrNull
                        ?.take(MAX_HINT_TYPE_CHARS)
                    val rawEnum = property?.get("enum") as? JsonArray
                    val enumFits = rawEnum != null &&
                        rawEnum.all { it is JsonPrimitive } &&
                        rawEnum.size <= MAX_HINT_ENUM_VALUES &&
                        rawEnum.toString().length <= MAX_HINT_ENUM_CHARS
                    val description = (
                        (property?.get("description") as? JsonPrimitive)?.contentOrNull
                            ?: (property?.get("title") as? JsonPrimitive)?.contentOrNull
                        )
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                    val itemDescription = (
                        (itemSchema?.get("description") as? JsonPrimitive)?.contentOrNull
                            ?: (itemSchema?.get("title") as? JsonPrimitive)?.contentOrNull
                        )
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                    val defaultValue = property?.get("default")
                    val constraintsOmitted =
                        property == null ||
                        rawType !is JsonPrimitive ||
                        property.keys.any { it in COMPLEX_SCHEMA_KEYS } ||
                        property.keys.any { it !in SIMPLE_HINT_KEYS } ||
                        itemSchema?.keys?.any { it !in SIMPLE_ITEM_HINT_KEYS } == true ||
                        type == "object" ||
                        (type == "array" && itemType == null) ||
                        (rawEnum != null && !enumFits) ||
                        description?.length?.let { it > MAX_HINT_DESCRIPTION_CHARS } == true ||
                        itemDescription?.length?.let { it > MAX_HINT_ITEM_DESCRIPTION_CHARS } == true ||
                        (defaultValue != null && (
                            defaultValue !is JsonPrimitive ||
                                defaultValue.toString().length > MAX_HINT_DEFAULT_CHARS
                            ))
                    if (constraintsOmitted) {
                        if (isRequired) {
                            inspectForRequiredConstraints = true
                        } else {
                            inspectForOptionalConstraints = true
                        }
                    }
                    val signature = buildString {
                        append(type)
                        itemType?.let { append('<').append(it).append('>') }
                        if (isRequired) append("; required")
                        if (enumFits) append("; enum=").append(rawEnum)
                        description?.let {
                            append("; hint=").append(it.take(MAX_HINT_DESCRIPTION_CHARS))
                        }
                        itemDescription?.let {
                            append("; item_hint=").append(it.take(MAX_HINT_ITEM_DESCRIPTION_CHARS))
                        }
                        (defaultValue as? JsonPrimitive)
                            ?.toString()
                            ?.takeIf { it.length <= MAX_HINT_DEFAULT_CHARS }
                            ?.let { append("; default=").append(it) }
                    }
                    val estimatedChars = name.length + signature.length + 6
                    if (
                        includedProperties >= MAX_HINT_PROPERTIES ||
                        name.length > MAX_HINT_PROPERTY_NAME_CHARS ||
                        hintChars + estimatedChars > MAX_HINT_CHARS
                    ) {
                        if (isRequired) {
                            omittedRequiredProperties += 1
                            inspectForRequiredConstraints = true
                        } else {
                            omittedOptionalProperties += 1
                            inspectForOptionalConstraints = true
                        }
                    } else {
                        put(name, signature)
                        hintChars += estimatedChars
                        includedProperties += 1
                    }
                }
            })
            val omittedProperties = omittedRequiredProperties + omittedOptionalProperties
            if (omittedProperties > 0) put("more_properties", omittedProperties)
            if (omittedRequiredProperties > 0) {
                put("more_required_properties", omittedRequiredProperties)
            }
            if (omittedOptionalProperties > 0) {
                put("more_optional_properties", omittedOptionalProperties)
            }
            put("inspect_for_required_constraints", inspectForRequiredConstraints)
            put("inspect_for_optional_constraints", inspectForOptionalConstraints)
        }
    }

    private fun handleInspect(state: RequestState, args: JsonObject): String {
        val name = args.string("name") ?: return errorJson("missing_name")
        val tool = state.deferredTools.firstOrNull { it.function.name == name }
            ?: return if (state.inlineTools.any { it.function.name == name }) {
                errorJson("tool_already_direct", "Call $name directly; its complete schema is already available.")
            } else {
                errorJson("tool_not_found", "Search deferred capabilities first.")
            }
        val schema = tool.completeParameters().asJsonObject()
        val requestedSection = args.string("section")?.lowercase()
        if (requestedSection != null && requestedSection !in INSPECT_SECTIONS) {
            return errorJson(
                "invalid_section",
                "Use section=required, section=optional, or section=raw.",
            )
        }
        val defaultSection = if (schema.toString().length > DEFAULT_SCHEMA_CHUNK_CHARS) {
            INSPECT_REQUIRED
        } else {
            INSPECT_RAW
        }
        val section = requestedSection ?: defaultSection
        val sectionedSchema = if (!canSectionSchema(schema)) {
            null
        } else {
            when (section) {
                INSPECT_REQUIRED -> requiredSection(schema)
                INSPECT_OPTIONAL -> optionalSection(schema)
                else -> null
            }
        }
        val inspectedSchema = sectionedSchema ?: schema
        val effectiveSection = if (section != INSPECT_RAW && sectionedSchema == null) {
            INSPECT_RAW
        } else {
            section
        }
        val schemaText = inspectedSchema.toString()
        val cursor = args.int("cursor").coerceIn(0, schemaText.length)
        val maxChars = args.int("max_chars", DEFAULT_SCHEMA_CHUNK_CHARS)
            .coerceIn(MIN_SCHEMA_CHUNK_CHARS, MAX_SCHEMA_CHUNK_CHARS)
        val end = (cursor + maxChars).coerceAtMost(schemaText.length)
        val isCompleteObject = cursor == 0 && end == schemaText.length
        return buildJsonObject {
            put("name", tool.function.name)
            put("description", tool.function.description.take(MAX_INSPECT_DESCRIPTION_CHARS))
            if (tool.function.description.length > MAX_INSPECT_DESCRIPTION_CHARS) {
                put("description_truncated", true)
            }
            put("section", effectiveSection)
            put("complete", end == schemaText.length)
            if (effectiveSection != INSPECT_RAW) put("schema_complete", false)
            if (isCompleteObject) {
                put("input_schema", inspectedSchema)
            } else {
                // A JSON string chunk keeps the outer tool result valid; blindly taking the first
                // 100k characters would produce malformed JSON and silently lose schema suffixes.
                put("input_schema_json_chunk", schemaText.substring(cursor, end))
                put("cursor", cursor)
                if (end < schemaText.length) put("next_cursor", end)
            }
        }.toString()
    }

    private fun requiredSection(schema: JsonObject): JsonObject? {
        val required = schema["required"] as? JsonArray ?: JsonArray(emptyList())
        val requiredNames = required.mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull
        }.distinct()
        val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
        if (requiredNames.any { it !in properties }) return null
        val requiredProperties = JsonObject(requiredNames.associateWith { properties.getValue(it) })
        val closure = localDefinitionClosure(schema, requiredProperties) ?: return null
        return buildJsonObject {
            schema["type"]?.let { put("type", it) }
            put("properties", requiredProperties)
            put("required", JsonArray(requiredNames.map(::JsonPrimitive)))
            if (closure.defs.isNotEmpty()) put("\$defs", closure.defs)
            if (closure.legacyDefinitions.isNotEmpty()) {
                put("definitions", closure.legacyDefinitions)
            }
        }
    }

    private fun canSectionSchema(schema: JsonObject): Boolean {
        if (schema.keys.any { it !in SECTIONABLE_ROOT_KEYS }) return false
        if (schema.keys.any { it in ROOT_COMBINATOR_KEYS }) return false
        val availableDefs = schema["\$defs"] as? JsonObject ?: JsonObject(emptyMap())
        val availableLegacy =
            schema["definitions"] as? JsonObject ?: JsonObject(emptyMap())
        var valid = true

        fun visit(element: JsonElement) {
            if (!valid) return
            when (element) {
                is JsonObject -> {
                    val refElement = element["\$ref"]
                    if (refElement != null) {
                        val ref = (refElement as? JsonPrimitive)?.contentOrNull ?: run {
                            valid = false
                            return
                        }
                        val (container, name) = localDefinitionTarget(ref) ?: run {
                            valid = false
                            return
                        }
                        val available =
                            if (container == "\$defs") availableDefs else availableLegacy
                        if (name !in available) {
                            valid = false
                            return
                        }
                    }
                    element.values.forEach(::visit)
                }
                is JsonArray -> element.forEach(::visit)
                else -> Unit
            }
        }

        visit(schema)
        return valid
    }

    private data class LocalDefinitionClosure(
        val defs: JsonObject,
        val legacyDefinitions: JsonObject,
    )

    private fun localDefinitionClosure(
        schema: JsonObject,
        roots: JsonElement,
    ): LocalDefinitionClosure? {
        val availableDefs = schema["\$defs"] as? JsonObject ?: JsonObject(emptyMap())
        val availableLegacy =
            schema["definitions"] as? JsonObject ?: JsonObject(emptyMap())
        val pending = java.util.ArrayDeque<Pair<String, String>>()
        var unsupportedRef = false

        fun collectRefs(element: JsonElement) {
            when (element) {
                is JsonObject -> {
                    val refElement = element["\$ref"]
                    if (refElement != null) {
                        val ref = (refElement as? JsonPrimitive)?.contentOrNull ?: run {
                            unsupportedRef = true
                            return
                        }
                        val target = localDefinitionTarget(ref)
                        if (target == null) {
                            unsupportedRef = true
                        } else {
                            pending.addLast(target)
                        }
                    }
                    element.values.forEach(::collectRefs)
                }
                is JsonArray -> element.forEach(::collectRefs)
                else -> Unit
            }
        }

        collectRefs(roots)
        if (unsupportedRef) return null
        val resolvedDefs = linkedMapOf<String, JsonElement>()
        val resolvedLegacy = linkedMapOf<String, JsonElement>()
        while (pending.isNotEmpty()) {
            val (container, name) = pending.removeFirst()
            val resolved = if (container == "\$defs") resolvedDefs else resolvedLegacy
            if (name in resolved) continue
            val available = if (container == "\$defs") availableDefs else availableLegacy
            val definition = available[name] ?: return null
            resolved[name] = definition
            collectRefs(definition)
            if (unsupportedRef) return null
        }
        return LocalDefinitionClosure(
            defs = JsonObject(resolvedDefs),
            legacyDefinitions = JsonObject(resolvedLegacy),
        )
    }

    private fun localDefinitionTarget(ref: String): Pair<String, String>? {
        val container = when {
            ref.startsWith("#/\$defs/") -> "\$defs"
            ref.startsWith("#/definitions/") -> "definitions"
            else -> return null
        }
        val rawName = ref.removePrefix("#/$container/").substringBefore('/')
        val name = rawName.replace("~1", "/").replace("~0", "~")
        return name.takeIf(String::isNotBlank)?.let { container to it }
    }

    private fun optionalSection(schema: JsonObject): JsonObject? {
        val required = (schema["required"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.toSet()
            .orEmpty()
        val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val optionalProperties = JsonObject(properties.filterKeys { it !in required })
        val closure = localDefinitionClosure(schema, optionalProperties) ?: return null
        return buildJsonObject {
            schema["type"]?.let { put("type", it) }
            put("properties", optionalProperties)
            put("required", JsonArray(emptyList()))
            if (closure.defs.isNotEmpty()) put("\$defs", closure.defs)
            if (closure.legacyDefinitions.isNotEmpty()) {
                put("definitions", closure.legacyDefinitions)
            }
        }
    }

    private suspend fun handleInvoke(
        state: RequestState,
        args: JsonObject,
        ctx: GenerationContext,
    ): String {
        val name = args.string("name") ?: return errorJson("missing_name")
        if (state.deferredTools.none { it.function.name == name }) {
            return if (state.inlineTools.any { it.function.name == name }) {
                errorJson("tool_already_direct", "Call $name directly; broker invocation is only for deferred tools.")
            } else {
                errorJson("tool_not_found", "Search deferred capabilities first.")
            }
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

        /** Full-schema cost that switches a registry from all-direct to routed exposure. */
        const val ROUTING_TRIGGER_SCHEMA_TOKENS = 1_500

        /**
         * Final budget for complete schemas placed directly on the wire.
         *
         * The broker schema is tracked separately in [ToolExposurePlan.brokerSchemaTokens].
         */
        const val MAX_INLINE_SCHEMA_TOKENS = 1_500

        /** Absolute fail-safe for tokenizer edge cases and pathological schema string values. */
        const val MAX_INLINE_SCHEMA_CHARS = 6_000

        /**
         * Final provider-neutral cap for the serialized tool surface, including the broker and
         * JSON envelope. The 1,500-token complete-inline budget is enforced independently.
         */
        const val MAX_WIRE_SCHEMA_TOKENS = 2_500

        /** Serialized provider-envelope fail-safe, independent of the approximate tokenizer. */
        const val MAX_WIRE_SCHEMA_CHARS = 10_000

        const val TOP_K_DIRECT_TOOLS = 6
        private const val DEFAULT_SEARCH_RESULTS = 6
        private const val MAX_SEARCH_RESULTS = 20
        private const val MAX_SEARCH_QUERY_CHARS = 240
        private const val MAX_SEARCH_DESCRIPTION_CHARS = 160
        private const val MAX_HINT_PROPERTIES = 10
        private const val MAX_HINT_CHARS = 600
        private const val MAX_HINT_PROPERTY_NAME_CHARS = 80
        private const val MAX_HINT_TYPE_CHARS = 40
        private const val MAX_HINT_DESCRIPTION_CHARS = 80
        private const val MAX_HINT_ITEM_DESCRIPTION_CHARS = 60
        private const val MAX_HINT_DEFAULT_CHARS = 40
        private const val MAX_HINT_ENUM_VALUES = 6
        private const val MAX_HINT_ENUM_CHARS = 160
        private const val MAX_CATALOG_MCP_NAMES = 16
        private const val MAX_CATALOG_MCP_NAME_CHARS = 40
        // The complete broker result must stay under GenerationManager's 16k per-result cap.
        // A schema chunk is embedded as a JSON string and can nearly double through escaping.
        private const val DEFAULT_SCHEMA_CHUNK_CHARS = 6_000
        private const val MIN_SCHEMA_CHUNK_CHARS = 1_000
        private const val MAX_SCHEMA_CHUNK_CHARS = 6_000
        private const val MAX_INSPECT_DESCRIPTION_CHARS = 256
        private const val INSPECT_REQUIRED = "required"
        private const val INSPECT_OPTIONAL = "optional"
        private const val INSPECT_RAW = "raw"
        private val INSPECT_SECTIONS = setOf(INSPECT_REQUIRED, INSPECT_OPTIONAL, INSPECT_RAW)
        private val COMPLEX_SCHEMA_KEYS = setOf(
            "oneOf",
            "anyOf",
            "allOf",
            "not",
            "\$defs",
            "definitions",
            "dependentSchemas",
            "if",
            "then",
            "else",
        )
        private val ROOT_COMBINATOR_KEYS = setOf(
            "oneOf",
            "anyOf",
            "allOf",
            "if",
            "then",
            "else",
            "not",
        )
        private val SECTIONABLE_ROOT_KEYS = setOf(
            "type",
            "properties",
            "required",
            "title",
            "description",
            "\$schema",
            "\$defs",
            "definitions",
        )
        private val SIMPLE_ROOT_HINT_KEYS = setOf(
            "type",
            "properties",
            "required",
            "title",
            "description",
            "\$schema",
        )
        private val SIMPLE_HINT_KEYS = setOf(
            "type",
            "description",
            "title",
            "items",
            "enum",
            "default",
        )
        private val SIMPLE_ITEM_HINT_KEYS = setOf(
            "type",
            "description",
            "title",
        )

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

        fun estimateSchemaChars(tools: List<ToolDefinition>): Int = tools.sumOf { tool ->
            tool.function.name.length +
                tool.function.description.length +
                tool.function.parameters.asJsonObject().toString().length
        }

        /**
         * Conservative provider-neutral estimate of the actual serialized tool surface.
         *
         * The component sum above deliberately measures schema content. It omits keys such as
         * `type`, `function`, `input_schema`, and `functionDeclarations`; with many small tools
         * that wrapper can cost more than the schemas themselves. This estimate takes the maximum
         * of the OpenAI/Ollama, Anthropic, and Gemini request shapes.
         */
        private fun wireSchemaEnvelopes(tools: List<ToolDefinition>): List<JsonObject> {
            if (tools.isEmpty()) return emptyList()
            val forcedName = tools.maxByOrNull { it.function.name.length }
                ?.function
                ?.name
                .orEmpty()

            val openAi = buildJsonObject {
                putJsonArray("tools") {
                    tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", tool.function.name)
                                put("description", tool.function.description)
                                put("parameters", tool.function.parameters.asJsonObject())
                            })
                        })
                    }
                }
                put("tool_choice", buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject { put("name", forcedName) })
                })
            }
            val anthropic = buildJsonObject {
                putJsonArray("tools") {
                    tools.forEachIndexed { index, tool ->
                        add(buildJsonObject {
                            put("name", tool.function.name)
                            put("description", tool.function.description)
                            put("input_schema", tool.function.parameters.asJsonObject())
                            if (index == tools.lastIndex) {
                                put(
                                    "cache_control",
                                    buildJsonObject { put("type", "ephemeral") },
                                )
                            }
                        })
                    }
                }
                put("tool_choice", buildJsonObject {
                    put("type", "tool")
                    put("name", forcedName)
                })
                put("cache_control", buildJsonObject { put("type", "ephemeral") })
            }
            val gemini = buildJsonObject {
                putJsonArray("tools") {
                    add(buildJsonObject { put("code_execution", buildJsonObject {}) })
                    add(buildJsonObject { put("google_search", buildJsonObject {}) })
                    add(buildJsonObject {
                        putJsonArray("function_declarations") {
                            tools.forEach { tool ->
                                add(buildJsonObject {
                                    put("name", tool.function.name)
                                    put("description", tool.function.description)
                                    // The exact-schema key is the longest of Gemini's two variants.
                                    put(
                                        "parametersJsonSchema",
                                        tool.function.parameters.asJsonObject(),
                                    )
                                })
                            }
                        }
                    })
                }
                put("toolConfig", buildJsonObject {
                    put("includeServerSideToolInvocations", true)
                    put("functionCallingConfig", buildJsonObject {
                        put("mode", "ANY")
                        putJsonArray("allowedFunctionNames") {
                            add(JsonPrimitive(forcedName))
                        }
                    })
                })
            }
            return listOf(openAi, anthropic, gemini)
        }

        fun estimateWireSchemaTokens(tools: List<ToolDefinition>): Int =
            estimateWireSchema(tools).tokens

        fun estimateWireSchemaChars(tools: List<ToolDefinition>): Int =
            estimateWireSchema(tools).chars

        /**
         * Measures tokens and characters in one pass.
         *
         * The admission loop below probes every candidate against both budgets. Asking for them
         * separately rebuilt and re-serialized all three provider envelopes twice per candidate,
         * which is the dominant remaining cost once the tokenizer itself is cheap.
         */
        fun estimateWireSchema(tools: List<ToolDefinition>): WireSchemaEstimate {
            var tokens = 0
            var chars = 0
            wireSchemaEnvelopes(tools).forEach { envelope ->
                val serialized = envelope.toString()
                tokens = maxOf(tokens, TokenEstimator.estimate(serialized))
                chars = maxOf(chars, serialized.length)
            }
            return WireSchemaEstimate(tokens = tokens, chars = chars)
        }

        /** Gemini-native tool objects exist even when no function definition is present. */
        fun estimateNativeToolEnvelopeTokens(
            codeExecutionEnabled: Boolean,
            googleSearchEnabled: Boolean,
        ): Int {
            if (!codeExecutionEnabled && !googleSearchEnabled) return 0
            return TokenEstimator.estimate(
                buildJsonObject {
                    putJsonArray("tools") {
                        if (codeExecutionEnabled) {
                            add(buildJsonObject { put("code_execution", buildJsonObject {}) })
                        }
                        if (googleSearchEnabled) {
                            add(buildJsonObject { put("google_search", buildJsonObject {}) })
                        }
                    }
                }.toString(),
            )
        }

        fun plan(
            tools: List<ToolDefinition>,
            contextTokens: Int,
            currentText: String,
            recentTexts: List<String> = emptyList(),
            recentSuccessfulToolNames: Set<String> = emptySet(),
            forcedDirectToolNames: Set<String> = emptySet(),
        ): ToolExposurePlan {
            if (tools.isEmpty()) {
                val route = CapabilityRouter.route(currentText, recentTexts, tools)
                return ToolExposurePlan(route, emptyList(), emptyList(), 0)
            }

            val trivialNoToolTurn = CapabilityRouter.isTrivialNoToolTurn(currentText)
            val directEligible = tools
                .filter {
                    it.defer != DeferPolicy.ALWAYS &&
                        !(trivialNoToolTurn && it.defer == DeferPolicy.EAGER)
                }
                .sortedBy { it.function.name }
            val directComplete = directEligible.map { it.withCompleteSchema() }
            // Measuring the complete registry is the single most expensive step in a generation's
            // critical path: it serializes and tokenizes every full MCP schema, which for a couple
            // of connectors is hundreds of kilobytes. Each measurement is therefore lazy, so the
            // cheap structural predicates below can short-circuit it away entirely — a 65-tool
            // Todoist+Notion registry is routed on a list size, never on a tokenizer pass.
            val totalSchemaChars by lazy(LazyThreadSafetyMode.NONE) { estimateSchemaChars(directComplete) }
            val directWireSchemaChars by lazy(LazyThreadSafetyMode.NONE) { estimateWireSchemaChars(directComplete) }
            val totalSchemaTokens by lazy(LazyThreadSafetyMode.NONE) { estimateSchemaTokens(directComplete) }
            val directWireSchemaTokens by lazy(LazyThreadSafetyMode.NONE) { estimateWireSchemaTokens(directComplete) }
            // Character budgets are checked before their token equivalents. They bound the same
            // registry an order of magnitude more cheaply, so an oversized pool never reaches the
            // tokenizer at all. `mustRoute` is a boolean, so operand order cannot change routing.
            val mustRoute = tools.any { it.defer == DeferPolicy.ALWAYS } ||
                (trivialNoToolTurn && tools.any { it.defer == DeferPolicy.EAGER }) ||
                directEligible.size >= LARGE_REGISTRY_TOOLS ||
                directEligible.size > MAX_WIRE_TOOLS ||
                totalSchemaChars > MAX_INLINE_SCHEMA_CHARS ||
                directWireSchemaChars > MAX_WIRE_SCHEMA_CHARS ||
                totalSchemaTokens > ROUTING_TRIGGER_SCHEMA_TOKENS ||
                directWireSchemaTokens > MAX_WIRE_SCHEMA_TOKENS

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
                    wireSchemaTokens = directWireSchemaTokens,
                )
            }

            val route = CapabilityRouter.route(
                currentText = currentText,
                recentTexts = recentTexts,
                tools = tools.filter { it.defer == DeferPolicy.AUTO },
                topK = TOP_K_DIRECT_TOOLS,
                recentSuccessfulToolNames = recentSuccessfulToolNames,
            )
            val mandatory = tools
                .filter { it.defer == DeferPolicy.NEVER }
                .sortedBy { it.function.name }
            val eager = if (trivialNoToolTurn) {
                emptyList()
            } else {
                tools
                    .filter { it.defer == DeferPolicy.EAGER }
                    .sortedBy { it.function.name }
            }
            val forced = tools
                .filter {
                    it.defer != DeferPolicy.ALWAYS &&
                        it.function.name in forcedDirectToolNames
                }
                .sortedBy { it.function.name }
            // NEVER and explicitly forced tools cannot be silently deferred. EAGER tools are
            // preferred during packing, but remain safely broker-reachable when their complete
            // schemas do not fit.
            val protected = (mandatory + forced).distinctBy { it.function.name }
            val protectedNames = protected.mapTo(mutableSetOf()) { it.function.name }
            val hasUnprotectedTools = tools.any { it.function.name !in protectedNames }
            require(
                protected.size <= MAX_WIRE_TOOLS &&
                    !(protected.size == MAX_WIRE_TOOLS && hasUnprotectedTools)
            ) {
                "Cannot expose ${protected.size} NEVER-deferred/forced tools plus the capability broker " +
                    "within the $MAX_WIRE_TOOLS-tool provider limit."
            }
            val selected = route.selectedToolsFrom(tools)
                .filter { it.defer == DeferPolicy.AUTO }
            // Reserve one of the provider's 64 slots for the broker. If an impossible registry
            // contains too many protected items, fail explicitly instead of silently hiding one.
            val inlineLimit = if (hasUnprotectedTools) MAX_WIRE_TOOLS - 1 else MAX_WIRE_TOOLS
            fun wireEstimateFor(inlineTools: List<ToolDefinition>): WireSchemaEstimate {
                val inlineNames = inlineTools.mapTo(mutableSetOf()) { it.function.name }
                val remaining = tools.filter { it.function.name !in inlineNames }
                val wireTools = if (remaining.isEmpty()) {
                    inlineTools
                } else {
                    inlineTools + brokerDefinition(remaining)
                }
                return estimateWireSchema(wireTools)
            }

            val protectedComplete = protected
                .take(inlineLimit)
                .map { it.withCompleteSchema() }
            var inlineSchemaTokens = estimateSchemaTokens(protectedComplete)
            var inlineSchemaChars = estimateSchemaChars(protectedComplete)
            require(inlineSchemaTokens <= MAX_INLINE_SCHEMA_TOKENS) {
                "Required direct tool schemas cost $inlineSchemaTokens tokens, exceeding the " +
                    "$MAX_INLINE_SCHEMA_TOKENS-token final inline budget."
            }
            require(inlineSchemaChars <= MAX_INLINE_SCHEMA_CHARS) {
                "Required direct tool schemas serialize to $inlineSchemaChars characters, " +
                    "exceeding the $MAX_INLINE_SCHEMA_CHARS-character final inline budget."
            }
            val protectedWire = wireEstimateFor(protectedComplete)
            require(protectedWire.tokens <= MAX_WIRE_SCHEMA_TOKENS) {
                "Required direct tools plus the capability broker cost " +
                    "${protectedWire.tokens} serialized tokens, exceeding the " +
                    "$MAX_WIRE_SCHEMA_TOKENS-token final wire budget."
            }
            require(protectedWire.chars <= MAX_WIRE_SCHEMA_CHARS) {
                "Required direct tools plus the capability broker serialize to " +
                    "${protectedWire.chars} characters, exceeding the " +
                    "$MAX_WIRE_SCHEMA_CHARS-character final wire budget."
            }
            val admittedWithinBudget = buildList {
                (eager + selected)
                    .distinctBy { it.function.name }
                    .asSequence()
                    .filter { candidate ->
                        protectedComplete.none {
                            it.function.name == candidate.function.name
                        }
                    }
                    .map { it.withCompleteSchema() }
                    .forEach { candidate ->
                        if (protectedComplete.size + size >= inlineLimit) return@forEach
                        val candidateTokens = estimateSchemaTokens(listOf(candidate))
                        val candidateInlineTokens = inlineSchemaTokens + candidateTokens
                        val candidateChars = estimateSchemaChars(listOf(candidate))
                        val candidateInlineChars = inlineSchemaChars + candidateChars
                        // The wire probe is the expensive half of admission, so let the two cheap
                        // inline budgets reject a candidate before it is ever built.
                        if (
                            candidateInlineTokens > MAX_INLINE_SCHEMA_TOKENS ||
                            candidateInlineChars > MAX_INLINE_SCHEMA_CHARS
                        ) {
                            return@forEach
                        }
                        val candidateWire =
                            wireEstimateFor(protectedComplete + toList() + candidate)
                        if (
                            candidateWire.tokens <= MAX_WIRE_SCHEMA_TOKENS &&
                            candidateWire.chars <= MAX_WIRE_SCHEMA_CHARS
                        ) {
                            add(candidate)
                            inlineSchemaTokens = candidateInlineTokens
                            inlineSchemaChars = candidateInlineChars
                        }
                    }
            }
            val inline = protectedComplete + admittedWithinBudget
            val inlineNames = inline.map { it.function.name }.toSet()
            val deferred = tools.filter { it.function.name !in inlineNames }
            val admittedAutoNames = admittedWithinBudget
                .filter { it.defer == DeferPolicy.AUTO }
                .map { it.function.name }
            val effectiveMode = when {
                trivialNoToolTurn && route.mode == CapabilityRouteMode.NO_TOOL ->
                    CapabilityRouteMode.NO_TOOL
                deferred.isEmpty() -> CapabilityRouteMode.DIRECT
                admittedAutoNames.isNotEmpty() -> route.mode
                inline.isEmpty() -> CapabilityRouteMode.BROKER
                else -> CapabilityRouteMode.MIXED
            }
            val finalBroker = deferred.takeIf { it.isNotEmpty() }
                ?.let(::brokerDefinition)
            val brokerSchemaTokens = if (finalBroker == null) {
                0
            } else {
                estimateSchemaTokens(listOf(finalBroker))
            }
            val finalWire = estimateWireSchema(
                if (finalBroker == null) inline else inline + finalBroker,
            )
            val wireSchemaTokens = finalWire.tokens
            check(wireSchemaTokens <= MAX_WIRE_SCHEMA_TOKENS) {
                "Final serialized tool surface costs $wireSchemaTokens tokens, exceeding the " +
                    "$MAX_WIRE_SCHEMA_TOKENS-token wire budget."
            }
            check(finalWire.chars <= MAX_WIRE_SCHEMA_CHARS) {
                "Final serialized tool surface is ${finalWire.chars} characters, exceeding the " +
                    "$MAX_WIRE_SCHEMA_CHARS-character wire budget."
            }
            return ToolExposurePlan(
                route = route.copy(
                    mode = effectiveMode,
                    selectedToolNames = admittedAutoNames,
                    reason = if (admittedAutoNames.size < selected.size) {
                        "Selected ${admittedAutoNames.size} relevant schema(s) within the final " +
                            "$MAX_INLINE_SCHEMA_TOKENS-token inline / " +
                            "$MAX_WIRE_SCHEMA_TOKENS-token wire budgets; oversized matches remain " +
                            "broker-reachable."
                    } else {
                        route.reason
                    },
                    schemaTokenEstimate = inlineSchemaTokens,
                    requiresBroker = deferred.isNotEmpty(),
                ),
                inlineTools = inline,
                deferredTools = deferred,
                inlineSchemaTokens = inlineSchemaTokens,
                brokerSchemaTokens = brokerSchemaTokens,
                wireSchemaTokens = wireSchemaTokens,
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
                    description = "Access deferred capabilities not already listed as direct tools " +
                        "($catalog). Never search for a tool whose schema is already available. Search " +
                        "once by user intent, then invoke directly when input_hint says required constraints " +
                        "are complete. Inspect only when required constraints are flagged, or when you need " +
                        "omitted optional fields. Large safe schemas inspect required fields by default; use " +
                        "section=optional for optional fields or section=raw for lossless schema pages. " +
                        "Results are cursor-paginated across all deferred tools; " +
                        "never claim one unavailable before exhausting relevant search.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "action" to ToolProperty("string", "search, inspect, or invoke"),
                            "query" to ToolProperty("string", "For search: the user's concrete intent."),
                            "name" to ToolProperty("string", "For inspect/invoke: exact result name."),
                            "arguments" to ToolProperty("object", "For invoke: arguments matching input_hint or the inspected schema."),
                            "cursor" to ToolProperty("integer", "For paginated search/inspect: next_cursor."),
                            "limit" to ToolProperty("integer", "For search: summaries per page, 1-20."),
                            "max_chars" to ToolProperty("integer", "For inspect: section or raw chunk size, 1000-6000."),
                            "section" to ToolProperty("string", "For inspect: required, optional, or raw."),
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
                                    put("description", "Arguments matching input_hint or the inspected input_schema.")
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
                                    put("description", "Inspect section or raw chunk size from 1000 to 6000 characters.")
                                })
                                put("section", buildJsonObject {
                                    put("type", "string")
                                    put("description", "Inspect required fields, optional fields, or bounded raw schema.")
                                    put("enum", buildJsonArray {
                                        add(JsonPrimitive(INSPECT_REQUIRED))
                                        add(JsonPrimitive(INSPECT_OPTIONAL))
                                        add(JsonPrimitive(INSPECT_RAW))
                                    })
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
            val customMcpNames = tools
                .mapNotNull { tool ->
                    Regex("""\[MCP:\s*([^]]+)]""", RegexOption.IGNORE_CASE)
                        .find(tool.function.description)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                }
                .distinctBy { it.lowercase() }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
            val visibleMcpNames = customMcpNames.take(MAX_CATALOG_MCP_NAMES)
            return buildList {
                if ("todoist" in text) add("Todoist")
                if ("notion" in text) add("Notion")
                if ("github" in text) add("GitHub")
                addAll(visibleMcpNames.map { it.take(MAX_CATALOG_MCP_NAME_CHARS) })
                val hiddenMcpNames = customMcpNames.size - visibleMcpNames.size
                if (hiddenMcpNames > 0) add("+$hiddenMcpNames more MCP servers")
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

/** Token and character cost of one serialized tool surface, measured in a single pass. */
data class WireSchemaEstimate(
    val tokens: Int,
    val chars: Int,
)

data class ToolExposurePlan(
    val route: CapabilityRoute,
    val inlineTools: List<ToolDefinition>,
    val deferredTools: List<ToolDefinition>,
    val inlineSchemaTokens: Int,
    val brokerSchemaTokens: Int = 0,
    /** Maximum estimated serialized cost across all supported native tool request shapes. */
    val wireSchemaTokens: Int = inlineSchemaTokens + brokerSchemaTokens,
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
