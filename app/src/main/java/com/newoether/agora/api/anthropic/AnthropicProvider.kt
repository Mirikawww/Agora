package com.newoether.agora.api.anthropic

import com.newoether.agora.api.*

import com.newoether.agora.util.DebugLog
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ThinkingLevels
import com.newoether.agora.api.util.buildToolCallId
import com.newoether.agora.api.util.prepareMessages
import com.newoether.agora.util.Constants
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.net.URI

private const val ANTHROPIC_API_HOST = "api.anthropic.com"

@Serializable
internal data class AnthropicRequest(
    val model: String,
    val messages: List<AnthropicMessage>,
    val system: String? = null,
    @SerialName("max_tokens") val maxTokens: Int = 4096,
    val stream: Boolean = true,
    val thinking: AnthropicThinking? = null,
    @SerialName("output_config") val outputConfig: AnthropicOutputConfig? = null,
    @SerialName("tool_choice") val toolChoice: AnthropicToolChoice? = null,
    val tools: List<AnthropicTool>? = null,
    val temperature: Float? = null,
    @SerialName("top_p") val topP: Float? = null,
    val speed: String? = null,
    @SerialName("cache_control") val cacheControl: AnthropicCacheControl? = null
)

@Serializable
internal data class AnthropicTool(
    val name: String,
    val description: String,
    @SerialName("input_schema") val inputSchema: JsonObject,
    @SerialName("cache_control") val cacheControl: AnthropicCacheControl? = null
)

@Serializable
internal data class AnthropicToolChoice(
    val type: String = "tool",
    val name: String,
)

internal data class AnthropicThinkingCapabilities(
    val supportsAdaptive: Boolean,
    val supportsManual: Boolean,
    val defaultsAdaptive: Boolean,
    val supportsDisabled: Boolean,
    val rejectsForcedToolChoice: Boolean,
)

/**
 * Anthropic thinking modes are model capabilities, not a monotonic version flag. In particular,
 * Mythos Preview still accepts manual budgets but rejects forced tool choice, whereas Mythos 5 is
 * adaptive-only and does support forced tool choice.
 */
internal fun anthropicThinkingCapabilities(modelId: String): AnthropicThinkingCapabilities {
    val normalized = modelId.lowercase().replace('.', '-').replace('_', '-')
    val isMythosPreview = "mythos-preview" in normalized
    val isMythos5 = !isMythosPreview && "mythos-5" in normalized
    val isFable5 = "fable-5" in normalized
    val isOpus5 = "opus-5" in normalized
    val isSonnet5 = "sonnet-5" in normalized
    val isOpus46 = "opus-4-6" in normalized
    val isSonnet46 = "sonnet-4-6" in normalized
    val isOpus47 = "opus-4-7" in normalized
    val isOpus48 = "opus-4-8" in normalized
    val adaptiveOnly =
        isFable5 || isMythos5 || isOpus5 || isSonnet5 || isOpus47 || isOpus48
    return AnthropicThinkingCapabilities(
        supportsAdaptive =
            isMythosPreview || adaptiveOnly || isOpus46 || isSonnet46,
        supportsManual = !adaptiveOnly,
        defaultsAdaptive =
            isOpus5 || isSonnet5 || isFable5 || isMythos5 || isMythosPreview,
        supportsDisabled = isOpus5 || isSonnet5,
        rejectsForcedToolChoice = isMythosPreview,
    )
}

internal fun ProviderConfig.toAnthropicToolChoice(
    thinking: AnthropicThinking?,
): AnthropicToolChoice? {
    // Manual extended thinking cannot be combined with forced tool use. Adaptive thinking can,
    // but Mythos Preview currently rejects forced choice regardless of thinking mode.
    if (
        thinking?.type == "enabled" ||
        anthropicThinkingCapabilities(modelId).rejectsForcedToolChoice
    ) {
        return null
    }
    val forced = toolChoice as? ToolChoiceDirective.ForcedFunction ?: return null
    if (tools.orEmpty().none { it.function.name == forced.name }) return null
    return AnthropicToolChoice(name = forced.name)
}

@Serializable
internal data class AnthropicCacheControl(
    val type: String = "ephemeral"
)

/**
 * A custom Anthropic-compatible endpoint may reject Anthropic-only cache controls.
 */
internal fun isOfficialAnthropicEndpoint(baseUrl: String?): Boolean {
    if (baseUrl.isNullOrBlank()) return true
    return runCatching { URI(baseUrl.trim()).host.equals(ANTHROPIC_API_HOST, ignoreCase = true) }
        .getOrDefault(false)
}

/**
 * Anthropic treats the final marked tool as a breakpoint after the complete tool prefix.
 * Preserve registry order so an unchanged capability list produces a byte-stable prefix.
 */
internal fun List<AnthropicTool>.withStablePrefixCacheBreakpoint(
    cacheControl: AnthropicCacheControl?
): List<AnthropicTool> {
    if (isEmpty() || cacheControl == null) return this
    return mapIndexed { index, tool ->
        tool.copy(cacheControl = cacheControl.takeIf { index == lastIndex })
    }
}

@Serializable
internal data class AnthropicThinking(
    val type: String = "enabled",
    @SerialName("budget_tokens") val budgetTokens: Int? = null,
    val display: String? = null
)

@Serializable
internal data class AnthropicOutputConfig(
    val effort: String
)

internal fun ProviderConfig.toAnthropicThinking(): AnthropicThinking? {
    val isLegacyClaude =
        modelId == "claude-3-opus-20240229" ||
            modelId == "claude-3-sonnet-20240229" ||
            modelId == "claude-3-haiku-20240307"
    if (!modelId.startsWith("claude") || isLegacyClaude) return null
    val capabilities = anthropicThinkingCapabilities(modelId)
    if (!thinkingEnabled) {
        return AnthropicThinking(type = "disabled")
            .takeIf { capabilities.defaultsAdaptive && capabilities.supportsDisabled }
    }
    val useAdaptive =
        capabilities.supportsAdaptive && (!thinkingBudgetEnabled || !capabilities.supportsManual)
    if (useAdaptive) {
        return AnthropicThinking(type = "adaptive", display = "summarized")
    }
    val budget = (
        if (thinkingBudgetEnabled) thinkingBudgetTokens else ThinkingLevels.DefaultBudgetTokens
        ).coerceIn(1024, 128000)
    return AnthropicThinking(type = "enabled", budgetTokens = budget, display = "summarized")
}

@Serializable
internal data class AnthropicMessage(
    val role: String,
    val content: List<AnthropicContentPart>
)

@Serializable
internal data class AnthropicContentPart(
    val type: String,
    val text: String? = null,
    val thinking: String? = null,
    val signature: String? = null,
    val source: AnthropicImageSource? = null,
    val id: String? = null,
    val name: String? = null,
    val input: JsonObject? = null,
    @SerialName("tool_use_id") val toolUseId: String? = null,
    val content: String? = null
)

@Serializable
internal data class AnthropicImageSource(
    val type: String = "base64",
    @SerialName("media_type") val mediaType: String,
    val data: String
)

@Serializable
internal data class AnthropicStreamEvent(
    val type: String,
    val delta: AnthropicDelta? = null,
    @SerialName("content_block") val contentBlock: AnthropicContentBlock? = null,
    val message: AnthropicMessageInfo? = null,
    val usage: AnthropicUsage? = null,
    val index: Int? = null
)

@Serializable
internal data class AnthropicDelta(
    val text: String? = null,
    val thinking: String? = null,
    val signature: String? = null,
    @SerialName("partial_json") val partialJson: String? = null,
    val type: String? = null
)

@Serializable
internal data class AnthropicContentBlock(
    val type: String,
    val id: String? = null,
    val name: String? = null,
    val input: JsonObject? = null,
    val thinking: String? = null,
    val signature: String? = null
)

@Serializable
internal data class AnthropicMessageInfo(
    val usage: AnthropicUsage? = null
)

@Serializable
internal data class AnthropicUsage(
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("cache_creation_input_tokens") val cacheCreationInputTokens: Int? = null,
    @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null
)

internal fun AnthropicUsage.toUsageUpdate(outputTokens: Int = this.outputTokens ?: 0): StreamEvent.UsageUpdate {
    val cached = cacheReadInputTokens ?: 0
    val totalInput = (inputTokens ?: 0) + (cacheCreationInputTokens ?: 0) + cached
    return StreamEvent.UsageUpdate(
        tokenCount = totalInput + outputTokens,
        promptTokens = totalInput,
        cachedPromptTokens = cached,
        cacheTelemetryAvailable =
            cacheCreationInputTokens != null || cacheReadInputTokens != null,
        completionTokens = outputTokens,
    )
}

class AnthropicProvider : LlmProvider {
    override val name: String = Constants.PROVIDER_ANTHROPIC
    override val defaultBaseUrl: String = "https://api.anthropic.com/v1"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    override fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig
    ): Flow<StreamEvent> = flow {
        val baseUrl = config.baseUrl?.trimEnd('/') ?: "https://api.anthropic.com/v1"
        val modelName = config.modelId
        val cacheControl = AnthropicCacheControl()
            .takeIf { isOfficialAnthropicEndpoint(baseUrl) }

        val validatedPath = prepareMessages(messages, config.maxContextWindow)

        // Convert ChatMessages to Anthropic API format.
        // Consecutive result_ messages are batched into a single user message
        // because Anthropic requires all tool_results for a batched assistant
        // tool_use to be in the single immediately-following user message.
        val apiMessages = buildList {
            var i = 0
            while (i < validatedPath.size) {
                val msg = validatedPath[i]
                when {
                    msg.id.startsWith(Constants.TOOL_MSG_PREFIX) -> {
                        add(buildAssistantToolUse(msg))
                        i++
                        // Batch all immediately following result_ messages into one user message
                        if (i < validatedPath.size && validatedPath[i].id.startsWith(Constants.RESULT_MSG_PREFIX)) {
                            val resultBlocks = mutableListOf<AnthropicContentPart>()
                            while (i < validatedPath.size && validatedPath[i].id.startsWith(Constants.RESULT_MSG_PREFIX)) {
                                resultBlocks.addAll(buildToolResultBlocks(validatedPath[i]))
                                i++
                            }
                            add(AnthropicMessage(role = "user", content = resultBlocks))
                        }
                    }
                    msg.id.startsWith(Constants.RESULT_MSG_PREFIX) -> {
                        // Orphan result_ — should not occur after validateToolMessages, but drop defensively
                        i++
                    }
                    else -> {
                        add(buildNormalMessage(if (config.includeImages) msg else msg.copy(images = emptyList())))
                        i++
                    }
                }
            }
        }

        val thinking = config.toAnthropicThinking()
        val outputConfig = if (thinking?.type == "adaptive") {
            AnthropicOutputConfig(effort = ThinkingLevels.anthropicEffort(config.thinkingLevel))
        } else null

        // Convert ToolDefinition to Anthropic format
        val anthropicTools = config.tools?.map { td ->
            AnthropicTool(
                name = td.function.name,
                description = td.function.description,
                inputSchema = td.function.parameters.asJsonObject()
            )
        }?.withStablePrefixCacheBreakpoint(cacheControl)

        val requestBody = AnthropicRequest(
            model = modelName,
            messages = apiMessages,
            system = config.systemPrompt,
            thinking = thinking,
            outputConfig = outputConfig,
            toolChoice = config.toAnthropicToolChoice(thinking),
            maxTokens = config.maxTokens ?: if (thinking?.budgetTokens != null) maxOf(thinking.budgetTokens + 1024, 4096) else 4096,
            tools = anthropicTools,
            temperature = config.temperature,
            topP = config.topP,
            speed = if (config.fastEnabled) "fast" else null,
            cacheControl = cacheControl
        )

        try {
                    val url = "$baseUrl/messages"
                    val headers = mutableMapOf("Content-Type" to "application/json")
                    val apiKey = config.apiKey.trim()
                    fun applyAuthHeader() {
                        headers["x-api-key"] = apiKey
                    }
                    applyAuthHeader()
                    headers["anthropic-version"] = "2023-06-01"
                    if (config.fastEnabled) headers["anthropic-beta"] = "fast-mode-2026-02-01"
                    val requestBodyJson = json.encodeToString(AnthropicRequest.serializer(), requestBody)
                    DebugLog.d("AgoraAPI", "[Anthropic] REQ → $baseUrl/messages | model=$modelName | msgs=${apiMessages.size} | thinking=${thinking != null} | tools=${anthropicTools?.size ?: 0}")
                    DebugLog.d("AgoraAPI", "[Anthropic] BODY: ${requestBodyJson.take(4000)}")
                    val maxAttempts = 3
                    val retryableCodes = setOf(429, 502, 503, 504)
                    var attempt = 0
                    var done = false

                    while (attempt < maxAttempts && !done) {
                        attempt++
                        applyAuthHeader()
                        val handle = HttpClient.streamPost(url, requestBodyJson, headers, config.streamTag)
                        try {
                        if (handle.code == 200) {
                            done = true
                            var line: String? = null
                            var currentType: String? = null
                            var toolUseId: String? = null
                            var toolUseName: String? = null
                    var toolUseArgs = StringBuilder()
                    var thinkingSignature: String? = null
                    var messageUsage = AnthropicUsage()
                    val liveness = com.newoether.agora.api.util.StreamLiveness()

                    while (currentCoroutineContext().isActive) {
                        try {
                            line = handle.readLine()
                            if (line == null) break
                            liveness.onLine(line)
                        } catch (e: java.net.SocketTimeoutException) {
                            if (!currentCoroutineContext().isActive) break
                            val stall = liveness.stalled()
                            if (stall != null) {
                                emit(StreamEvent.Error(stall))
                                break
                            }
                            continue
                        }
                        // Match the SSE field name, not a fixed-width literal: the space after
                        // the colon is optional in the grammar, and requiring it silently
                        // discarded every frame from servers emitting the compact form.
                        if (line.startsWith("event:")) {
                            currentType = line.removePrefix("event:").trim()
                        } else if (line.startsWith("data:")) {
                            val jsonStr = line.removePrefix("data:").trim()
                            try {
                                val event = json.decodeFromString<AnthropicStreamEvent>(jsonStr)
                                when (event.type) {
                                    "message_start" -> {
                                        event.message?.usage?.let { messageUsage = it }
                                    }
                                    "content_block_start" -> {
                                        event.contentBlock?.let { block ->
                                            when (block.type) {
                                                "thinking" -> {
                                                    block.signature?.takeIf { it.isNotBlank() }?.let { thinkingSignature = it }
                                                }
                                                "tool_use" -> {
                                                    toolUseId = block.id
                                                    toolUseName = block.name
                                                    toolUseArgs = StringBuilder()
                                                }
                                            }
                                        }
                                    }
                                    "content_block_delta" -> {
                                        event.delta?.let { delta ->
                                            when (delta.type) {
                                                "input_json_delta" -> {
                                                    delta.partialJson?.let { toolUseArgs.append(it) }
                                                }
                                                else -> {
                                                    delta.text?.let { emit(StreamEvent.TextChunk(it)) }
                                                    delta.thinking?.let {
                                                        if (delta.signature != null) thinkingSignature = delta.signature
                                                        emit(StreamEvent.ThoughtChunk(it, null, thinkingSignature))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    "content_block_stop" -> {
                                        if (toolUseId != null && toolUseName != null) {
                                            emit(StreamEvent.ToolCallRequest(
                                                toolUseId!!, toolUseName!!, toolUseArgs.toString()
                                            ))
                                            toolUseId = null
                                            toolUseName = null
                                        }
                                        thinkingSignature = null
                                    }
                                    "message_delta" -> {
                                        event.usage?.let { u ->
                                            emit(messageUsage.toUsageUpdate(u.outputTokens ?: 0))
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                DebugLog.e("AgoraAPI", "Parse error: ${e.message}", e)
                            }
                        }
                    }
                    if (!currentCoroutineContext().isActive) {
                        throw kotlinx.coroutines.CancellationException("Stream cancelled")
                    }
                } else {
                                    val errorRaw = handle.errorBody ?: "Unknown error"
                                    DebugLog.e("AgoraAPI", "[Anthropic] ERR ${handle.code}: $errorRaw")

                                    // Auth/key errors fail hard — no sibling-key rotation.
                                    if (handle.code in retryableCodes && attempt < maxAttempts) {
                                        DebugLog.w("AgoraAPI", "[Anthropic] Transient error ${handle.code} on attempt $attempt/$maxAttempts, retrying in ${1000 * attempt}ms...")
                                        emit(StreamEvent.Retrying(attempt, maxAttempts))
                                        delay(1000L * attempt)
                                    } else {
                                        val genError = try {
                                            val errorJson = json.decodeFromString<OpenAiErrorResponse>(errorRaw)
                                            GenerationError.Api(code = errorJson.error.code ?: handle.code.toString(), type = errorJson.error.type, message = errorJson.error.message)
                                        } catch (_: Exception) {
                                            GenerationError.Network(statusCode = handle.code, message = errorRaw)
                                        }
                                        emit(StreamEvent.Error(genError))
                                        done = true
                                    }
                                }
                } finally { handle.close() }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            emit(StreamEvent.Error(GenerationError.Timeout))
        } catch (e: java.net.ConnectException) {
            emit(StreamEvent.Error(GenerationError.Network(statusCode = 0, message = e.localizedMessage ?: "Connection refused")))
        } catch (e: java.net.UnknownHostException) {
            emit(StreamEvent.Error(GenerationError.Network(statusCode = 0, message = e.localizedMessage ?: "Unknown host")))
        } catch (e: Exception) {
            if (currentCoroutineContext().isActive) {
                emit(StreamEvent.Error(GenerationError.Unknown(e)))
            }
        }
    }.flowOn(Dispatchers.IO)

    // ── Message conversion helpers ──

    private fun buildAssistantToolUse(msg: ChatMessage): AnthropicMessage {
        val toolSegs = msg.segments?.filter { it.type == "tool" }
        if (!toolSegs.isNullOrEmpty()) {
            val blocks = toolSegs.map { seg -> buildToolUseBlock(seg.toolCallId, seg.toolName, seg.toolArgs) }
            return AnthropicMessage(role = "assistant", content = blocks)
        }
        val tc = msg.toolCall ?: return AnthropicMessage(role = "assistant", content = listOf(
            AnthropicContentPart(type = "text", text = "Continue")
        ))
        val block = buildToolUseBlock(tc.toolCallId, tc.toolName, tc.arguments)
        return AnthropicMessage(role = "assistant", content = listOf(block))
    }

    private fun buildToolUseBlock(id: String?, name: String?, args: String?): AnthropicContentPart {
        val toolId = id ?: buildToolCallId(name ?: "", args ?: "{}", "tool_")
        val input = try {
            json.parseToJsonElement(args ?: "{}") as? JsonObject ?: JsonObject(emptyMap())
        } catch (_: Exception) { JsonObject(emptyMap()) }
        return AnthropicContentPart(type = "tool_use", id = toolId, name = name ?: "", input = input)
    }

    private fun buildToolResultBlocks(msg: ChatMessage): List<AnthropicContentPart> {
        val toolSegs = msg.segments?.filter { it.type == "tool" }
        if (!toolSegs.isNullOrEmpty()) {
            return toolSegs.map { seg ->
                val toolId = seg.toolCallId ?: buildToolCallId(seg.toolName ?: "", seg.toolArgs ?: "{}", "tool_")
                AnthropicContentPart(type = "tool_result", toolUseId = toolId, content = seg.toolResult ?: "")
            }
        }
        val tc = msg.toolCall ?: return emptyList()
        val toolId = tc.toolCallId ?: buildToolCallId(tc.toolName, tc.arguments, "tool_")
        return listOf(AnthropicContentPart(type = "tool_result", toolUseId = toolId, content = tc.result))
    }

    private fun buildNormalMessage(msg: ChatMessage): AnthropicMessage {
        val parts = mutableListOf<AnthropicContentPart>()
        val imagePaths = if (msg.participant == Participant.USER) msg.images else emptyList()
        for (imagePath in imagePaths) {
            val encoded = com.newoether.agora.api.util.encodeImageToBase64(imagePath)
            if (encoded != null) {
                val (mimeType, base64) = encoded
                parts.add(AnthropicContentPart(
                    type = "image",
                    source = AnthropicImageSource(mediaType = mimeType, data = base64)
                ))
            }
        }
        if (msg.text.isNotEmpty()) {
            parts.add(AnthropicContentPart(type = "text", text = msg.text))
        }
        if (parts.isEmpty()) parts.add(AnthropicContentPart(type = "text", text = "Continue"))
        val role = if (msg.participant == Participant.USER) "user" else "assistant"
        return AnthropicMessage(role = role, content = parts)
    }

    override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> =
        fetchModelCatalog(apiKey, baseUrl).map { it.id }

    override suspend fun fetchModelCatalog(apiKey: String, baseUrl: String?): List<FetchedModel> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            val effectiveBaseUrl = baseUrl?.trimEnd('/') ?: "https://api.anthropic.com/v1"
            val responseText = HttpClient.fetchModelsCancellable(
                "$effectiveBaseUrl/models",
                mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01"),
                timeoutMs = Constants.MODEL_FETCH_TIMEOUT_MS,
            ) ?: run {
                DebugLog.e("AgoraAPI", "Failed to fetch Anthropic models: empty response")
                return@withContext emptyList()
            }
            val root = json.parseToJsonElement(responseText) as? JsonObject
                ?: return@withContext emptyList()
            val data = root["data"] as? JsonArray ?: return@withContext emptyList()
            data.mapNotNull { element ->
                val model = element as? JsonObject ?: return@mapNotNull null
                val id = (model["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                FetchedModel(id = id, fast = explicitFastSupport(model))
            }
        } catch (e: Exception) {
            DebugLog.e("AgoraAPI", "Failed to fetch Anthropic models", e)
            emptyList()
        }
    }
}

@Serializable
internal data class AnthropicModelsResponse(
    val data: List<AnthropicModelInfo>,
    @SerialName("has_more") val hasMore: Boolean = false
)

@Serializable
internal data class AnthropicModelInfo(
    val id: String,
    @SerialName("display_name") val displayName: String = ""
)
