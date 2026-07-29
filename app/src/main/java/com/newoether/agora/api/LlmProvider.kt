package com.newoether.agora.api

import com.newoether.agora.model.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

sealed class StreamEvent {
    data class TextChunk(val text: String) : StreamEvent()
    data class ThoughtChunk(val thought: String, val title: String? = null, val signature: String? = null) : StreamEvent()
    data class UsageUpdate(
        val tokenCount: Int,
        val thoughtsTokenCount: Int = 0,
        /** Prompt (input/upload) tokens for this request. 0 = not reported by provider. */
        val promptTokens: Int = 0,
        /** Prompt tokens served from a provider cache. Included in [promptTokens]. */
        val cachedPromptTokens: Int = 0,
        /** True only when the provider explicitly reported cache-token telemetry. */
        val cacheTelemetryAvailable: Boolean = false,
        /** Completion (output/download) tokens for this request. 0 = not reported by provider. */
        val completionTokens: Int = 0,
        /** Time-to-first-token in ms, measured client-side. 0 = not yet known. */
        val ttftMs: Long = 0L,
    ) : StreamEvent()
    data class Error(val error: GenerationError) : StreamEvent() {
        val message: String get() = error.userMessage()
    }
    data class ToolCallRequest(val id: String, val name: String, val arguments: String, val signature: String? = null) : StreamEvent()
    data class ToolCallsRequest(val calls: List<ToolCallRequest>) : StreamEvent()
    /** Emitted when the provider is retrying after a transient error (401, 429, 5xx).
     *  [attempt] is 1-based and always < [maxAttempts] (the final attempt does not emit). */
    data class Retrying(val attempt: Int, val maxAttempts: Int) : StreamEvent()
}

/** Provider-neutral request directive for native function selection. */
sealed interface ToolChoiceDirective {
    data class ForcedFunction(val name: String) : ToolChoiceDirective
}

data class ProviderConfig(
    val apiKey: String,
    val modelId: String,
    val systemPrompt: String? = null,
    val maxContextWindow: Int = 20,
    val codeExecutionEnabled: Boolean = false,
    val googleSearchEnabled: Boolean = false,
    val thinkingEnabled: Boolean = true,
    val thinkingLevel: String = "medium",
    val thinkingBudgetEnabled: Boolean = false,
    val thinkingBudgetTokens: Int = 4096,
    val fastEnabled: Boolean = false,
    val baseUrl: String? = null,
    val tools: List<ToolDefinition>? = null,
    val toolChoice: ToolChoiceDirective? = null,
    val userPrepend: String? = null,
    val userPostpend: String? = null,
    val includeImages: Boolean = true,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val topP: Float? = null,
    val frequencyPenalty: Float? = null,
    val presencePenalty: Float? = null,
    /** Additional enabled keys to try after a 401/invalid-key failure. */
    val alternateApiKeys: List<String> = emptyList(),
    /**
     * Conversation id, passed through to [HttpClient.streamPost] so Stop can cut this
     * conversation's socket immediately instead of waiting out a read tick.
     */
    val streamTag: String? = null
)

/**
 * Whether a tool's full schema may be withheld from the request and reached through the
 * deferred-discovery meta-tools instead.
 *
 * Deferring is not disabling: a deferred tool stays discoverable and callable through the
 * capability broker, so model capability is unchanged — only the per-request schema cost goes
 * away.
 */
enum class DeferPolicy {
    /** Always inline. Reserved for tools that must be visible to a native provider gate
     *  (e.g. a future approval flow), where deferring would bypass that gate. */
    NEVER,

    /**
     * Prefer inline on substantive requests even without a lexical match, but defer for a trivial
     * chat turn such as a greeting or acknowledgement. A complete schema that cannot fit the final
     * wire budget remains broker-reachable instead of silently breaking that hard token bound.
     * Use for small, high-frequency tools whose discovery round costs more than their schemas.
     */
    EAGER,

    /** Eligible for deferral when the schema budget is exceeded. */
    AUTO,

    /** Always deferred regardless of budget. */
    ALWAYS,
}

@Serializable
data class ToolDefinition(
    val type: String = "function",
    val function: ToolFunction,
    /** Not serialised: request-shaping metadata, never part of the wire format. */
    @Transient val defer: DeferPolicy = DeferPolicy.AUTO,
    /** Complete original schema retained locally when [function] carries a compact wire schema. */
    @Transient val fullParameters: ToolParameters? = null,
)

@Serializable
data class ToolFunction(
    val name: String,
    val description: String,
    val parameters: ToolParameters
)

@Serializable(with = ToolParametersSerializer::class)
data class ToolParameters(
    val type: String = "object",
    val properties: Map<String, ToolProperty>,
    val required: List<String> = emptyList(),
    /** Preserves an MCP server's complete JSON Schema (enum/oneOf/$defs/etc.). */
    val rawSchema: JsonObject? = null,
) {
    fun asJsonObject(): JsonObject = rawSchema ?: JsonObject(
        mapOf(
            "type" to JsonPrimitive(type),
            "properties" to JsonObject(properties.mapValues { (_, prop) ->
                val values = mutableMapOf<String, JsonElement>(
                    "type" to JsonPrimitive(prop.type),
                    "description" to JsonPrimitive(prop.description),
                )
                prop.items?.let { item ->
                    values["items"] = JsonObject(
                        mapOf(
                            "type" to JsonPrimitive(item.type),
                            "description" to JsonPrimitive(item.description),
                        )
                    )
                }
                JsonObject(values)
            }),
            "required" to JsonArray(required.map(::JsonPrimitive)),
        )
    )
}

object ToolParametersSerializer : KSerializer<ToolParameters> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ToolParameters) {
        encoder.encodeSerializableValue(JsonObject.serializer(), value.asJsonObject())
    }

    override fun deserialize(decoder: Decoder): ToolParameters {
        val schema = decoder.decodeSerializableValue(JsonObject.serializer())
        val type = (schema["type"] as? JsonPrimitive)?.content ?: "object"
        val required = (schema["required"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            .orEmpty()
        return ToolParameters(type = type, properties = emptyMap(), required = required, rawSchema = schema)
    }
}

@Serializable
data class ToolProperty(
    val type: String,
    val description: String,
    val items: ToolProperty? = null
)

@Serializable
data class OpenAiChatRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val stream: Boolean = true,
    @SerialName("stream_options") val streamOptions: OpenAiStreamOptions? = null,
    /** OpenAI-only cache-routing fingerprint. Null for compatible providers. */
    @SerialName("prompt_cache_key") val promptCacheKey: String? = null,
    val tools: List<ToolDefinition>? = null,
    @SerialName("tool_choice") val toolChoice: OpenAiToolChoice? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    val reasoning: OpenAiReasoning? = null,
    val plugins: List<OpenAiPlugin>? = null,
    val temperature: Float? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("top_p") val topP: Float? = null,
    @SerialName("frequency_penalty") val frequencyPenalty: Float? = null,
    @SerialName("presence_penalty") val presencePenalty: Float? = null,
    @SerialName("service_tier") val serviceTier: String? = null
)

@Serializable
data class OpenAiPlugin(
    val id: String
)

@Serializable
data class OpenAiReasoning(
    val effort: String? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val enabled: Boolean? = null
)

@Serializable
data class OpenAiStreamOptions(
    @SerialName("include_usage") val includeUsage: Boolean = true
)

@Serializable
data class OpenAiToolChoice(
    val type: String = "function",
    val function: OpenAiToolChoiceFunction,
)

@Serializable
data class OpenAiToolChoiceFunction(
    val name: String,
)

@Serializable
data class OpenAiMessage(
    val role: String,
    val content: List<OpenAiContentPart>,
    @SerialName("tool_calls") val toolCalls: List<OpenAiRequestToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null
)

@Serializable
data class OpenAiRequestToolCall(
    val id: String,
    val type: String = "function",
    val function: OpenAiRequestFunction
)

@Serializable
data class OpenAiRequestFunction(
    val name: String,
    val arguments: String
)

@Serializable
data class OpenAiContentPart(
    val type: String,
    val text: String? = null,
    @SerialName("image_url") val imageUrl: OpenAiImageUrl? = null
)

@Serializable
data class OpenAiImageUrl(
    val url: String
)



@Serializable
data class OpenAiTool(
    val type: String,
    val function: OpenAiFunction? = null
)

@Serializable
data class OpenAiFunction(
    val name: String,
    val description: String? = null,
    val parameters: JsonObject? = null
)

@Serializable
data class OpenAiStreamResponse(
    val id: String? = null,
    val choices: List<OpenAiChoice>? = null,
    val usage: OpenAiUsage? = null
)

@Serializable
data class OpenAiChoice(
    // Some proxies omit index on tool-only / final chunks.
    val index: Int = 0,
    val delta: OpenAiDelta? = null,
    /**
     * Non-standard but common among Chinese OpenAI-compatible proxies:
     * they put the completed assistant payload under `message` even in stream mode,
     * including `tool_calls`, instead of (or in addition to) incremental `delta`s.
     */
    val message: OpenAiStreamMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class OpenAiStreamMessage(
    val role: String? = null,
    /**
     * May be a plain string or (rarely) an array of content parts. Keep as [JsonElement]
     * so a non-string value doesn't fail the whole SSE event decode.
     */
    val content: JsonElement? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("tool_calls") val toolCalls: List<OpenAiToolCall>? = null
)

@Serializable
data class OpenAiDelta(
    val role: String? = null,
    /**
     * Same as [OpenAiStreamMessage.content]: accept string or other JSON so proxies that
     * emit `content: null` / arrays don't drop the entire event (and its tool_calls).
     */
    val content: JsonElement? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("reasoning_details") val reasoningDetails: List<OpenAiReasoningDetail>? = null,
    @SerialName("tool_calls") val toolCalls: List<OpenAiToolCall>? = null
)

@Serializable
data class OpenAiReasoningDetail(
    val type: String? = null,
    val text: String? = null
)

@Serializable
data class OpenAiToolCall(
    val index: Int? = null,
    val id: String? = null,
    val type: String? = null,
    val function: OpenAiFunctionCall? = null
)

@Serializable
data class OpenAiFunctionCall(
    val name: String? = null,
    val arguments: JsonElement? = null
)

@Serializable
data class OpenAiUsage(
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
    @SerialName("total_tokens") val totalTokens: Int,
    @SerialName("prompt_tokens_details") val promptTokensDetails: OpenAiPromptTokensDetails? = null,
    @SerialName("completion_tokens_details") val completionTokensDetails: OpenAiCompletionTokensDetails? = null
)

@Serializable
data class OpenAiPromptTokensDetails(
    @SerialName("cached_tokens") val cachedTokens: Int? = null
)

@Serializable
data class OpenAiCompletionTokensDetails(
    @SerialName("reasoning_tokens") val reasoningTokens: Int? = null
)

internal fun OpenAiUsage.toUsageUpdate() = StreamEvent.UsageUpdate(
    tokenCount = totalTokens,
    thoughtsTokenCount = completionTokensDetails?.reasoningTokens ?: 0,
    promptTokens = promptTokens,
    cachedPromptTokens = promptTokensDetails?.cachedTokens ?: 0,
    cacheTelemetryAvailable = promptTokensDetails?.cachedTokens != null,
    completionTokens = completionTokens,
)

@Serializable
data class OpenAiModelListResponse(val data: List<OpenAiModelInfo>)

@Serializable
data class OpenAiModelInfo(val id: String)

/** Model-list result with optional capability declarations supplied by the provider API. */
data class FetchedModel(
    val id: String,
    val fast: Boolean? = null,
)

private val FAST_FLAG_KEYS = listOf(
    "fast", "fast_mode", "supports_fast", "fast_supported", "priority", "priority_processing"
)

private fun JsonElement?.explicitSupportValue(): Boolean? {
    val primitive = this as? JsonPrimitive ?: return null
    primitive.booleanOrNull?.let { return it }
    return when (primitive.content.lowercase()) {
        "supported", "enabled", "available", "yes", "true" -> true
        "unsupported", "disabled", "unavailable", "no", "false" -> false
        else -> null
    }
}

private fun JsonElement?.nestedSupportValue(): Boolean? {
    explicitSupportValue()?.let { return it }
    val obj = this as? JsonObject ?: return null
    listOf("supported", "enabled", "available").forEach { key ->
        obj[key].explicitSupportValue()?.let { return it }
    }
    return null
}

/**
 * Extracts an explicit provider declaration without treating absence as false.
 * OpenAI-compatible providers commonly extend their model objects with different
 * shapes, so this accepts boolean/status fields, nested capability objects, and
 * advertised feature/parameter lists while preserving an unknown (`null`) state.
 */
internal fun explicitFastSupport(model: JsonObject): Boolean? {
    FAST_FLAG_KEYS.forEach { key ->
        val declaration = model[key]
        declaration.nestedSupportValue()?.let { return it }
        if (declaration is JsonObject) return true
    }

    listOf("capabilities", "features", "feature_support", "metadata").forEach { containerKey ->
        val container = model[containerKey] as? JsonObject ?: return@forEach
        FAST_FLAG_KEYS.forEach { key ->
            val declaration = container[key]
            declaration.nestedSupportValue()?.let { return it }
            if (declaration is JsonObject) return true
        }
    }

    val modes = model["modes"] as? JsonObject
        ?: (model["experimental"] as? JsonObject)?.get("modes") as? JsonObject
    if (modes?.containsKey("fast") == true) {
        return modes["fast"].nestedSupportValue() ?: true
    }

    val advertisedLists = listOf("supported_features", "features", "capabilities")
    advertisedLists.forEach { key ->
        val values = model[key] as? JsonArray ?: return@forEach
        val normalized = values.mapNotNull { (it as? JsonPrimitive)?.content?.lowercase() }
        if (normalized.any { it in setOf("fast", "fast_mode", "priority", "priority_processing") }) {
            return true
        }
    }

    val supportedParameters = model["supported_parameters"] as? JsonArray
    if (supportedParameters != null) {
        val normalized = supportedParameters.mapNotNull {
            (it as? JsonPrimitive)?.content?.lowercase()
        }
        if (normalized.any { it in setOf("service_tier", "speed", "fast") }) return true
    }
    return null
}

@Serializable
data class OpenAiErrorResponse(val error: OpenAiError)

@Serializable
data class OpenAiError(val message: String, val type: String? = null, val code: String? = null)

class PendingToolCall(
    var id: String = "",
    var name: String = "",
    val args: StringBuilder = StringBuilder()
)

/**
 * Maximum function-tool protocol supported by a provider family.
 *
 * Model-catalog support is intersected with this transport capability. Request-specific
 * restrictions (for example Anthropic forced choice while thinking) may still downgrade it.
 */
enum class FunctionToolTransport {
    /** No schema upload or native tool-call event path (on-device llama.cpp). */
    NONE,

    /** Native tool calls, but no protocol-level forced function selection (Ollama). */
    NATIVE_AUTO,

    /** Native tool calls plus a provider-level forced function directive. */
    NATIVE_CHOICE,
}

internal fun supportsFunctionTools(
    modelCatalogSupportsTools: Boolean,
    transport: FunctionToolTransport,
): Boolean = modelCatalogSupportsTools && transport != FunctionToolTransport.NONE

interface LlmProvider {
    val name: String
    val defaultBaseUrl: String
    val functionToolTransport: FunctionToolTransport
        get() = FunctionToolTransport.NATIVE_CHOICE

    fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig
    ): Flow<StreamEvent>
    
    suspend fun fetchModels(apiKey: String, baseUrl: String? = null): List<String>

    /**
     * Rich model catalog. Providers that expose feature metadata override this; the
     * default preserves compatibility and treats feature support as unspecified.
     */
    suspend fun fetchModelCatalog(apiKey: String, baseUrl: String? = null): List<FetchedModel> =
        fetchModels(apiKey, baseUrl).map { FetchedModel(id = it) }
}
