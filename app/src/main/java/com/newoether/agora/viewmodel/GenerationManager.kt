package com.newoether.agora.viewmodel

import android.app.Application
import com.newoether.agora.util.DebugLog
import com.newoether.agora.util.TimingLog
import com.newoether.agora.util.ToolLoopBudget
import com.newoether.agora.api.GenerationError
import com.newoether.agora.api.FunctionToolTransport
import com.newoether.agora.api.LlmProvider
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolChoiceDirective
import com.newoether.agora.api.supportsFunctionTools
import com.newoether.agora.api.gemini.effectiveGeminiToolCompatibility
import com.newoether.agora.data.MemoryManager

import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.GenerationRoundUsage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ToolCallData
import com.newoether.agora.R
import com.newoether.agora.service.AgoraForegroundService
import com.newoether.agora.service.AppForegroundTracker
import com.newoether.agora.api.util.buildToolCallId
import com.newoether.agora.api.util.normalizeToolArguments
import com.newoether.agora.api.util.projectAssistantImagesToLatestUserMessage
import com.newoether.agora.util.Constants
import com.newoether.agora.util.SearchResultFormatter
import com.newoether.agora.tool.AskToolProvider
import com.newoether.agora.tool.GitHubConnectorToolProvider
import com.newoether.agora.tool.ImageGenToolProvider
import com.newoether.agora.tool.McpDeferredToolProvider
import com.newoether.agora.tool.McpToolProvider
import com.newoether.agora.tool.DeviceInfoToolProvider
import com.newoether.agora.tool.MemoryToolProvider
import com.newoether.agora.tool.PersonalizationToolProvider
import com.newoether.agora.tool.ProviderBalanceToolProvider
import com.newoether.agora.tool.RagToolProvider
import com.newoether.agora.tool.ShellToolProvider
import com.newoether.agora.tool.SkillsToolProvider
import com.newoether.agora.tool.ToolProvider
import com.newoether.agora.tool.ToolExposurePlan
import com.newoether.agora.tool.ToolRoutingHistory
import com.newoether.agora.tool.WebSearchToolProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.util.UUID

data class GenerationConfig(
    val providerName: String,
    val modelId: String,
    val apiKey: String,
    val effectiveSystemPrompt: String?,
    val maxContextWindow: Int,
    val codeExecutionEnabled: Boolean,
    val googleSearchEnabled: Boolean,
    val thinkingEnabled: Boolean,
    val thinkingLevel: String = "medium",
    val thinkingBudgetEnabled: Boolean = false,
    val thinkingBudgetTokens: Int = 4096,
    val fastEnabled: Boolean = false,
    val baseUrl: String?,
    val userPrepend: String? = null,
    val userPostpend: String? = null,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val topP: Float? = null,
    val frequencyPenalty: Float? = null,
    val presencePenalty: Float? = null,
    val alternateApiKeys: List<String> = emptyList(),
    /** Model supports tool calling; when false no `tools` field is sent at all. */
    val toolsSupported: Boolean = true,
    /**
     * Real context window in tokens (models.dev `limit.context`), 0 when unknown.
     * Do not confuse with [maxContextWindow], which is a message count.
     */
    val contextTokens: Int = 0,
)

data class GenerationContext(
    val conversationId: String? = null,
    /** Unique generation key for request-scoped capability routing within one conversation. */
    val capabilityRequestId: String? = null,
    val accessSavedMemories: Boolean = true,
    val accessActiveMemory: Boolean = true,
    val accessPastConversations: Boolean = true,
    val modelSearchMethod: String = "keyword",
    val activeEmbeddingConfig: com.newoether.agora.data.EmbeddingModelConfig? = null,
    val embeddingApiKey: String = "",
    val ragThreshold: Float = 0.5f,
    val searchMatchLimit: Int = 10,
    val searchContextWindow: Int = 8,
    val webSearchEnabled: Boolean = false,
    val forceWebSearch: Boolean = false,
    val webSearchApiKeys: Map<String, String> = emptyMap(),
    val webSearchProvider: String = "duckduckgo",
    val webSearchNumResults: Int = 5,
    val webSearchBaseUrl: String = "",
    val imageGenEnabled: Boolean = false,
    val imageGenApiKey: String = "",
    val imageGenBaseUrl: String = "",
    val imageGenModel: String = "gpt-image-1",
    val imageGenSize: String = "1024x1024",
    val forceImageGen: Boolean = false,
    val skillsEnabled: Boolean = true,
    val askToolEnabled: Boolean = true,
    val personalizationToolsEnabled: Boolean = true,
    val githubEnabled: Boolean = false,
    val githubToken: String = "",
    val todoistEnabled: Boolean = false,
    val todoistOAuth: com.newoether.agora.data.McpOAuthState? = null,
    val notionEnabled: Boolean = false,
    val notionOAuth: com.newoether.agora.data.McpOAuthState? = null,
    val shellEnabled: Boolean = false,
    val shellDevices: List<com.newoether.agora.data.ShellDeviceConfig> = emptyList(),
    val mcpEnabled: Boolean = false,
    val mcpServers: List<com.newoether.agora.data.McpServerConfig> = emptyList(),
    val sandboxEnabled: Boolean = false,
    val imageTranscriptionEnabled: Boolean = false,
    val imageTranscriptionModel: String? = null,
    val imageTranscriptionBatchSize: Int = 3,
    val imageTranscriptionPrompt: String = com.newoether.agora.data.BuiltInPrompts.IMAGE_TRANSCRIPTION_USER,
    val transcriptionProviderName: String = "",
    val transcriptionModelId: String = "",
    val transcriptionApiKey: String = "",
    val transcriptionBaseUrl: String? = null,
    /**
     * Per-call result allowance reserved by the tool loop. Resumable providers must shape their
     * first page to this value so a later shared-round clip cannot skip cursor content.
     */
    val toolResultMaxChars: Int? = null,
)

internal const val FORCED_WEB_SEARCH_INSTRUCTION =
    "You MUST call the web_search tool to research before answering. Do not answer from memory alone."
internal const val FORCED_IMAGE_GEN_INSTRUCTION =
    "You MUST call the generate_image tool to create the requested image(s). Do not only describe the image in text."

internal fun forcedDirectToolNames(ctx: GenerationContext): Set<String> = buildSet {
    if (ctx.forceWebSearch) {
        add("web_search")
        add("web_fetch")
    }
    if (ctx.forceImageGen) add("generate_image")
}

internal fun continuationProviderConfig(
    config: ProviderConfig,
    completedToolNames: Set<String> = emptySet(),
): ProviderConfig {
    var prompt = config.systemPrompt
    val completedInstructions = buildList {
        if ("generate_image" in completedToolNames) add(FORCED_IMAGE_GEN_INSTRUCTION)
        if ("web_search" in completedToolNames) add(FORCED_WEB_SEARCH_INSTRUCTION)
    }
    for (instruction in completedInstructions) {
        prompt = when {
            prompt == instruction -> null
            prompt?.endsWith("\n\n$instruction") == true ->
                prompt.removeSuffix("\n\n$instruction").ifBlank { null }
            else -> prompt
        }
    }
    return config.copy(toolChoice = null, systemPrompt = prompt)
}

internal fun forcedToolChoice(
    ctx: GenerationContext,
    transport: FunctionToolTransport,
    wireTools: List<ToolDefinition>,
): ToolChoiceDirective? {
    if (transport != FunctionToolTransport.NATIVE_CHOICE) return null
    val requestedName = when {
        ctx.forceWebSearch -> "web_search"
        ctx.forceImageGen -> "generate_image"
        else -> null
    } ?: return null
    return requestedName
        .takeIf { name -> wireTools.any { it.function.name == name } }
        ?.let(ToolChoiceDirective::ForcedFunction)
}

internal fun applyUserTemplateToMessages(
    messages: List<ChatMessage>,
    prepend: String?,
    postpend: String?
): List<ChatMessage> {
    if (prepend == null && postpend == null) return messages
    val timeSdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
    val dateSdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
    return messages.map { msg ->
        val isToolMessage = msg.id.startsWith(Constants.TOOL_MSG_PREFIX) ||
            msg.id.startsWith(Constants.RESULT_MSG_PREFIX)
        if (!isToolMessage && msg.participant == Participant.USER && msg.text.isNotEmpty()) {
            val ts = java.util.Date(msg.timestamp)
            val rp = prepend?.replace("{sent_time}", timeSdf.format(ts))?.replace("{sent_date}", dateSdf.format(ts)) ?: ""
            val ra = postpend?.replace("{sent_time}", timeSdf.format(ts))?.replace("{sent_date}", dateSdf.format(ts)) ?: ""
            if (rp.isEmpty() && ra.isEmpty()) msg
            else msg.copy(text = rp + msg.text + ra)
        } else msg
    }
}

/**
 * The token-gated UI callbacks a single generation drives. Built once per call by
 * [GenerationSession.callbacksFor], so each generation entry point ([ChatViewModel]'s
 * send / regenerate / edit) wires the session ownership tokens in exactly one place
 * instead of re-threading five lambdas by hand.
 */
data class GenerationCallbacks(
    val onStreamUpdate: (ChatMessage) -> Unit,
    val onLoadingChange: (Boolean) -> Unit,
    val onGeneratingIdChange: (String?) -> Unit,
    val onStreamClear: () -> Unit,
    val isLatestPersist: () -> Boolean,
    /**
     * Serializes generation-owned DB writes with delete/replacement mutations and rechecks the
     * generation epoch while holding that per-conversation lock.
     */
    val persistMessagesIfLatest: suspend (List<MessageEntity>) -> Boolean,
)
internal fun formatGenerationDiagnostic(error: GenerationError): String =
    error.userMessage().trim().take(1_000).ifBlank { "Unknown error" }

internal fun hasFinalAssistantResponse(
    text: String,
    segments: List<MessageSegment>,
    generatedImages: List<String>,
    usedTools: Boolean,
    answeredAfterLastTool: Boolean,
): Boolean {
    if (generatedImages.isNotEmpty()) return true
    if (usedTools) return answeredAfterLastTool
    return text.isNotBlank() || segments.any { it.type == "answer" && it.content.isNotBlank() }
}

/** Extracts only the broker operation name; arguments and result content are never retained. */
internal fun brokerAction(toolName: String, arguments: String): String? = when (toolName) {
    McpDeferredToolProvider.TOOL_BROKER -> runCatching {
        val parsed = Json.parseToJsonElement(arguments) as? JsonObject
        (parsed?.get("action") as? JsonPrimitive)
            ?.contentOrNull
            ?.trim()
            ?.lowercase()
            ?.takeIf {
                it == McpDeferredToolProvider.ACTION_SEARCH ||
                    it == McpDeferredToolProvider.ACTION_INSPECT ||
                    it == McpDeferredToolProvider.ACTION_INVOKE
            }
    }.getOrNull()
    McpDeferredToolProvider.LEGACY_SEARCH -> McpDeferredToolProvider.ACTION_SEARCH
    McpDeferredToolProvider.LEGACY_INSPECT -> McpDeferredToolProvider.ACTION_INSPECT
    McpDeferredToolProvider.LEGACY_INVOKE -> McpDeferredToolProvider.ACTION_INVOKE
    else -> null
}

private const val MAX_RECORDED_ORIGINAL_RESULT_CHARS = 1_000_000

/**
 * Resolves the capability a tool call actually exercised.
 *
 * A broker invoke arrives on the wire as `agora_capabilities`, with the concrete capability named
 * inside its arguments. Promotion has to key off the concrete name — keying off the wire name
 * would only ever promote the broker itself, which is already direct.
 *
 * Search and inspect return null: browsing a catalogue is not evidence that a capability is
 * needed, and promoting on it would put a schema on the wire the model never called.
 */
internal fun invokedCapabilityName(toolName: String, arguments: String): String? {
    if (toolName !in McpDeferredToolProvider.META_TOOL_NAMES) {
        return toolName.takeIf(String::isNotBlank)
    }
    if (toolName == McpDeferredToolProvider.TOOL_BROKER &&
        brokerAction(toolName, arguments) != McpDeferredToolProvider.ACTION_INVOKE
    ) {
        return null
    }
    if (toolName == McpDeferredToolProvider.LEGACY_SEARCH ||
        toolName == McpDeferredToolProvider.LEGACY_INSPECT
    ) {
        return null
    }
    return runCatching {
        val parsed = Json.parseToJsonElement(arguments) as? JsonObject
        (parsed?.get("name") as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
    }.getOrNull()
}

/** Reads only the pager's numeric size metadata; raw tool text is never retained in telemetry. */
internal fun originalToolResultChars(toolName: String, value: String): Int {
    val pagerMetadata = runCatching {
        val root = Json.parseToJsonElement(value) as? JsonObject
        val metadata = root?.get("_agora_mcp_result") as? JsonObject
        val originalChars = (metadata?.get("original_chars") as? JsonPrimitive)
            ?.contentOrNull
            ?.toIntOrNull()
        val offset = (metadata?.get("offset") as? JsonPrimitive)
            ?.contentOrNull
            ?.toIntOrNull()
        val sourceTool = (metadata?.get("source_tool") as? JsonPrimitive)
            ?.contentOrNull
        Triple(originalChars, offset, sourceTool)
    }.getOrNull()
    val (originalChars, offset, sourceTool) = pagerMetadata ?: return value.length
    val brokerInvocation =
        toolName == McpDeferredToolProvider.TOOL_BROKER ||
            toolName == McpDeferredToolProvider.LEGACY_INVOKE
    return when {
        offset == 0 &&
            (sourceTool == toolName || brokerInvocation) &&
            originalChars != null &&
            originalChars >= value.length ->
            originalChars.coerceAtMost(MAX_RECORDED_ORIGINAL_RESULT_CHARS)
        offset != null &&
            offset > 0 &&
            toolName == McpToolProvider.RESULT_PAGE ->
            0
        else -> value.length
    }
}

class GenerationManager(
    private val app: Application,
    private val conversations: com.newoether.agora.data.repository.ConversationRepository,
    private val memoryManager: MemoryManager,
    private val providers: Map<String, LlmProvider>,
    private val context: android.content.Context,
    private val settings: com.newoether.agora.data.repository.SettingsRepository,
    private val sandboxFactory: com.newoether.agora.sandbox.SandboxManagerFactory? = null
) {
    var onMessagePersisted: ((messageId: String, text: String) -> Unit)? = null

    /** User-confirmation gate for remote shell mutations. Set by the ViewModel.
     *  Returns true to proceed, false to deny. */
    var onConfirmShellCommand: (suspend (server: String, summary: String) -> Boolean)? = null
    var onConfirmMcpTool: (suspend (server: String, summary: String) -> Boolean)? = null

    /** Account-balance probe for one provider, one reading per stored API key. Set by the
     *  ViewModel (ProviderRegistry owns Base URL / key resolution); empty = no runnable probe. */
    var onProbeProviderBalance: (suspend (provider: String) -> List<ProviderKeyBalance>)? = null

    /** Puts the model's question to the user and suspends for the answer. Set by the
     *  ViewModel; null result = the user dismissed the sheet. */
    var onAskUser: (suspend (question: String, header: String, mode: AskMode, options: List<String>) -> AskController.AskAnswer?)? = null

    private val memoryToolProvider = MemoryToolProvider(memoryManager)
    private val webSearchToolProvider = WebSearchToolProvider()
    private val ragToolProvider = RagToolProvider(conversations)
    private val imageGenToolProvider = ImageGenToolProvider(app)
    private val personalizationToolProvider = PersonalizationToolProvider(settings)
    private val providerBalanceToolProvider = ProviderBalanceToolProvider(settings).also { btp ->
        // Read the var lazily so the ViewModel can wire the probe after construction.
        btp.probe = { provider -> onProbeProviderBalance?.invoke(provider).orEmpty() }
    }
    private val askToolProvider = AskToolProvider().also { atp ->
        atp.ask = { question, header, mode, options -> onAskUser?.invoke(question, header, mode, options) }
    }
    private val skillsManager = com.newoether.agora.data.SkillsManager(app)
    private val skillsToolProvider = SkillsToolProvider(skillsManager, settings)
    private val githubConnectorToolProvider = GitHubConnectorToolProvider()
    val skillsManagerPublic: com.newoether.agora.data.SkillsManager get() = skillsManager
    private val shellToolProvider = ShellToolProvider(sandboxFactory).also { stp ->
        // Forward to the ViewModel-provided gate at call time (read the var lazily).
        stp.confirm = { server, summary -> onConfirmShellCommand?.invoke(server, summary) ?: true }
    }
    private val mcpToolProvider = McpToolProvider(
        com.newoether.agora.mcp.McpClientManager(context.applicationContext)
    ).also { mtp ->
        mtp.confirm = { server, summary -> onConfirmMcpTool?.invoke(server, summary) ?: false }
        // Observe user MCP servers + the built-in connectors so the connection pool
        // stays warm even when the global MCP toggle is off.
        mtp.observeServers(
            kotlinx.coroutines.flow.combine(
                settings.mcpEnabled,
                settings.mcpServers,
                kotlinx.coroutines.flow.combine(
                    settings.todoistConnectorEnabled,
                    settings.todoistOAuth,
                ) { on, oauth -> on to oauth },
                kotlinx.coroutines.flow.combine(
                    settings.notionConnectorEnabled,
                    settings.notionOAuth,
                ) { on, oauth -> on to oauth },
            ) { mcpOn, servers, todoist, notion ->
                buildList {
                    if (mcpOn) {
                        // A hand-added row is only stripped when the matching connector is on
                        // (its synthetic row takes over); otherwise the user's own entry stays.
                        addAll(
                            com.newoether.agora.tool.NotionConnector.withoutBuiltin(
                                com.newoether.agora.tool.TodoistConnector.withoutBuiltin(servers, todoist.first),
                                notion.first
                            )
                        )
                    }
                    // Always register the synthetic server when a connector is on so OAuth can
                    // run even before the first successful authorization.
                    if (todoist.first) {
                        add(com.newoether.agora.tool.TodoistConnector.serverConfig(todoist.second))
                    }
                    if (notion.first) {
                        add(com.newoether.agora.tool.NotionConnector.serverConfig(notion.second))
                    }
                }
            },
            settings::updateMcpServerNow,
        )
    }
    private val mcpDeferredToolProvider = McpDeferredToolProvider(
        deferredExecute = { name: String, arguments: String, ctx: GenerationContext -> executeTool(name, arguments, ctx) }
    )
    private val deviceInfoToolProvider = DeviceInfoToolProvider(context.applicationContext)
    private val toolProviders: List<ToolProvider> = listOf(
        memoryToolProvider, webSearchToolProvider, ragToolProvider, imageGenToolProvider,
        personalizationToolProvider, providerBalanceToolProvider, askToolProvider,
        skillsToolProvider, githubConnectorToolProvider, shellToolProvider, mcpToolProvider,
        mcpDeferredToolProvider, deviceInfoToolProvider,
    )

    fun buildImageGenTool(ctx: GenerationContext): List<ToolDefinition> =
        imageGenToolProvider.definitions(ctx)

    private val transcriptionManager = TranscriptionManager(providers, conversations, context)

    companion object {
        private val FILE_TOOL_NAMES = setOf("file_read", "file_write", "file_edit", "file_glob", "file_grep")
        private const val MAX_ROUND_TOOL_NAMES = 32
        private const val MAX_RECORDED_TOOL_NAME_CHARS = 64
    }

    private fun getProviderInstance(name: String): LlmProvider =
        providers[name] ?: providers.values.first()

    // Image/video frame extraction lives in ImageProcessor (single source of truth).
    private val imageProcessor = ImageProcessor(app)

    suspend fun processImages(
        uris: List<String>,
        sliceConfigs: Map<String, VideoSliceConfig> = emptyMap()
    ): List<String> = imageProcessor.processImagesAndVideos(uris, sliceConfigs)

    fun buildMemoryTools(ctx: GenerationContext): List<ToolDefinition> =
        memoryToolProvider.definitions(ctx)

    fun buildWebSearchTool(ctx: GenerationContext): List<ToolDefinition> =
        webSearchToolProvider.definitions(ctx)

    fun buildRagTool(ctx: GenerationContext): List<ToolDefinition> =
        ragToolProvider.definitions(ctx)

    fun buildShellTool(ctx: GenerationContext): List<ToolDefinition> {
        val all = shellToolProvider.definitions(ctx)
        return all.filter { it.function.name !in FILE_TOOL_NAMES }
    }

    fun buildFileTool(ctx: GenerationContext): List<ToolDefinition> {
        val all = shellToolProvider.definitions(ctx)
        return all.filter { it.function.name in FILE_TOOL_NAMES }
    }

    suspend fun testMcpServer(server: com.newoether.agora.data.McpServerConfig) =
        mcpToolProvider.test(server)

    val mcpStatuses get() = mcpToolProvider.statuses

    fun startMcpAuthorization(server: com.newoether.agora.data.McpServerConfig) =
        mcpToolProvider.startAuthorization(server)

    fun cancelMcpAuthorization(serverId: String) = mcpToolProvider.cancelAuthorization(serverId)

    suspend fun clearMcpAuthorization(server: com.newoether.agora.data.McpServerConfig) =
        mcpToolProvider.clearAuthorization(server)

    fun close() = mcpToolProvider.close()


    /** Semantic message search — delegates to [RagToolProvider], which owns the
     *  embedding-search logic. Kept here as the entry point used by ChatViewModel's
     *  in-app conversation search. */
    suspend fun semanticSearch(query: String, limit: Int, ctx: GenerationContext): List<Pair<MessageEntity, Float>> =
        ragToolProvider.semanticSearch(query, limit, ctx)

    private suspend fun executeTool(name: String, arguments: String, ctx: GenerationContext): String {
        val normalizedArgs = normalizeToolArguments(arguments)
        return try {
            for (provider in toolProviders) {
                if (provider.handles(name, ctx)) {
                    return provider.execute(name, normalizedArgs, ctx)
                }
            }
            "Unknown tool: $name"
        } catch (e: CancellationException) {
            // Must not swallow CancellationException — doing so prevents the coroutine from
            // stopping when the user presses Stop mid-tool, causing the generation loop to
            // keep running even after cancellation was requested.
            throw e
        } catch (e: Exception) {
            "Error executing tool '$name': ${e.localizedMessage ?: "Unknown error"}"
        }
    }

    private fun applyUserTemplate(messages: List<ChatMessage>, prepend: String?, postpend: String?): List<ChatMessage> {
        return applyUserTemplateToMessages(messages, prepend, postpend)
    }

    private fun appendMergedSegment(target: MutableList<MessageSegment>, segment: MessageSegment) {
        val last = target.lastOrNull()
        if (last != null && last.type == segment.type && (segment.type == "answer" || segment.type == "thought")) {
            target[target.lastIndex] = last.copy(
                content = last.content + segment.content,
                signature = segment.signature ?: last.signature,
                durationMs = mergeDurationMs(last.durationMs, segment.durationMs)
            )
        } else {
            target.add(segment)
        }
    }

    private fun mergeDurationMs(first: Long?, second: Long?): Long? {
        val merged = (first ?: 0L) + (second ?: 0L)
        return merged.takeIf { it > 0L }
    }

    private fun buildLiveSegments(
        flushed: List<MessageSegment>,
        answerBuf: StringBuilder,
        thoughtBuf: StringBuilder,
        signature: String? = null,
        thoughtDurationMs: Long? = null
    ): List<MessageSegment>? {
        val result = flushed.toMutableList()
        if (answerBuf.isNotEmpty()) {
            appendMergedSegment(result, MessageSegment(type = "answer", content = answerBuf.toString()))
        }
        if (thoughtBuf.isNotEmpty()) {
            appendMergedSegment(result, MessageSegment(
                type = "thought",
                content = thoughtBuf.toString(),
                signature = signature,
                durationMs = thoughtDurationMs
            ))
        }
        return result.ifEmpty { null }
    }

    private suspend fun buildApiPath(
        parentId: String?,
        conversationId: String,
        isRegenerate: Boolean,
        replaceMessageId: String?,
        config: GenerationConfig,
        ctx: GenerationContext,
        provider: LlmProvider,
    ): Triple<List<ChatMessage>, ProviderConfig, ToolExposurePlan?> {
        val t_buildApiPath = System.currentTimeMillis()
        val dbMessages = conversations.getMessagesForConversationSnapshot(conversationId)
        TimingLog.mark {
            "buildApiPath: getMessages took ${System.currentTimeMillis() - t_buildApiPath}ms " +
                "msgs=${dbMessages.size}"
        }
        val pathEntities = mutableListOf<MessageEntity>()
        var currId: String? = parentId
        while (currId != null) {
            val msg = dbMessages.find { it.id == currId } ?: break
            pathEntities.add(0, msg)
            currId = msg.parentId
        }
        val expanded = mutableListOf<MessageEntity>()
        for (entity in pathEntities) {
            val toolChildren = dbMessages
                .filter { it.parentId == entity.id && it.id.startsWith(Constants.TOOL_MSG_PREFIX) }
                .sortedBy { it.timestamp }
            if (toolChildren.isEmpty()) {
                expanded.add(entity)
            } else {
                for (toolMsg in toolChildren) {
                    expanded.add(toolMsg)
                    val pending = mutableListOf(toolMsg)
                    var safety = 0
                    while (pending.isNotEmpty() && safety < 100) {
                        val current = pending.removeAt(0)
                        val children = dbMessages
                            .filter { it.parentId == current.id && (it.id.startsWith(Constants.RESULT_MSG_PREFIX) || it.id.startsWith(Constants.TOOL_MSG_PREFIX)) }
                            .sortedBy { it.timestamp }
                        for (child in children) {
                            val isResult = child.id.startsWith(Constants.RESULT_MSG_PREFIX)
                            if (isResult) {
                                // Include result_ messages so providers can emit
                                // correct tool_use/tool_result pairs. The result
                                // data lives in TOOL_MSG segments too, but Anthropic
                                // requires separate tool_result blocks in the next
                                // user-role message.
                                if (child !in expanded) {
                                    expanded.add(child)
                                }
                                pending.add(child)
                            } else if (child !in expanded) {
                                expanded.add(child)
                                pending.add(child)
                            }
                        }
                        safety++
                    }
                }
                expanded.add(entity.copy(toolCallJson = null))
            }
        }
        val t_pathBuild = System.currentTimeMillis()
        val currentPath = expanded.map {
            val segs = it.toolCallJson?.let { json -> try { Json.decodeFromString<List<MessageSegment>>(json) } catch (_: Exception) { null } }
            val toolCall = segs?.lastOrNull { s -> s.type == "tool" }?.let { s ->
                val args = normalizeToolArguments(s.toolArgs)
                val callId = s.toolCallId?.takeIf { it.isNotBlank() }
                    ?: buildToolCallId(s.toolName ?: "", args)
                // signature=null, toolCallId=callId — 4th positional arg is signature, not id.
                ToolCallData(
                    toolName = s.toolName ?: "",
                    arguments = args,
                    result = s.toolResult ?: "",
                    signature = s.signature,
                    toolCallId = callId,
                )
            }
            val meta = it.attachmentMeta?.let { json -> try { Json.decodeFromString<com.newoether.agora.model.AttachmentMeta>(json) } catch (_: Exception) { null } }
            val attachmentText = if (meta != null) {
                meta.items.mapNotNull { item ->
                    val content = item.textContent
                    val transcription = item.transcription
                    val includeTranscription = ctx.imageTranscriptionEnabled && transcription != null && transcription.isNotBlank()
                    when {
                        content != null -> {
                            val label = item.fileName ?: "file"
                            "\n\n--- File: $label ---\n$content"
                        }
                        includeTranscription -> {
                            val label = item.fileName ?: "image"
                            "\n\n--- Image Transcription: $label ---\n$transcription"
                        }
                        else -> null
                    }
                }.joinToString("")
            } else ""
            val combinedText = if (attachmentText.isNotBlank()) it.text + attachmentText else it.text
            val hasTranscription = ctx.imageTranscriptionEnabled && meta != null && meta.items.any { item -> !item.transcription.isNullOrBlank() }
            val effectiveImages = if (hasTranscription) emptyList() else it.images
            val roundUsage = it.roundUsageJson?.let { json ->
                runCatching {
                    Json.decodeFromString<List<GenerationRoundUsage>>(json)
                }.getOrDefault(emptyList())
            }.orEmpty()
            ChatMessage(id = it.id, parentId = it.parentId, text = combinedText, images = effectiveImages, thoughts = it.thoughts, thoughtTitle = it.thoughtTitle, tokenCount = it.tokenCount, promptTokens = it.promptTokens, cachedPromptTokens = it.cachedPromptTokens, cacheTelemetryAvailable = it.cacheTelemetryAvailable, completionTokens = it.completionTokens, ttftMs = it.ttftMs, roundUsage = roundUsage, status = it.status, participant = it.participant, timestamp = it.timestamp, completedAt = it.completedAt, thoughtTimeMs = it.thoughtTimeMs, segments = segs, toolCall = toolCall)
        }.filter { it.participant != Participant.ERROR }
            .let { path ->
                if (isRegenerate && replaceMessageId != null) {
                    val oldIdx = path.indexOfFirst { it.id == replaceMessageId }
                    if (oldIdx >= 0) path.take(oldIdx) else path
                } else path
            }

        val functionToolsSupported = supportsFunctionTools(
            modelCatalogSupportsTools = config.toolsSupported,
            transport = provider.functionToolTransport,
        )
        TimingLog.mark {
            "buildApiPath: pathBuild took ${System.currentTimeMillis() - t_pathBuild}ms " +
                "expanded=${expanded.size} path=${currentPath.size}"
        }
        val forcedTools = forcedDirectToolNames(ctx)
        if (!functionToolsSupported && forcedTools.isNotEmpty()) {
            // Text-only families (local llama.cpp) and tool-disabled Ollama models deliberately
            // degrade to ordinary generation. The request builder also omits the MUST prompt, so
            // the model is never instructed to call a protocol it cannot emit.
            DebugLog.d(
                "AgoraTiming",
                "forced tools ignored for text-only ${config.providerName}/${config.modelId}: " +
                    forcedTools.joinToString(),
            )
        }
        val builtinTools: List<ToolDefinition>
        val mcpTools: List<ToolDefinition>
        val exposure = if (functionToolsSupported) {
            val memoryTools = buildMemoryTools(ctx)
            val webSearchTool = buildWebSearchTool(ctx)
            val ragTool = buildRagTool(ctx)
            val shellTool = buildShellTool(ctx)
            val fileTool = buildFileTool(ctx)
            val imageGenTool = buildImageGenTool(ctx)
            val personalizationTools = personalizationToolProvider.definitions(ctx)
            val balanceTools = providerBalanceToolProvider.definitions(ctx)
            val askTools = askToolProvider.definitions(ctx)
            val skillsTools = skillsToolProvider.definitions(ctx)
            val githubTools = githubConnectorToolProvider.definitions(ctx)
            val t0 = System.currentTimeMillis()
            mcpTools = mcpToolProvider.refresh(ctx)
            TimingLog.mark {
                "mcpToolProvider.refresh took ${System.currentTimeMillis() - t0}ms tools=${mcpTools.size}"
            }
            builtinTools = memoryTools + webSearchTool + ragTool + imageGenTool +
                personalizationTools + balanceTools + askTools + skillsTools + githubTools +
                shellTool + fileTool
            val toolPool = builtinTools + mcpTools
            val userTexts = currentPath
                .filter { it.participant == Participant.USER }
                .map { it.text }
            val t1 = System.currentTimeMillis()
            mcpDeferredToolProvider.prepare(
                requestId = ctx.capabilityRequestId ?: conversationId,
                allTools = toolPool,
                contextTokens = config.contextTokens,
                currentText = userTexts.lastOrNull().orEmpty(),
                recentTexts = userTexts.dropLast(1).takeLast(3),
                recentSuccessfulToolNames = ToolRoutingHistory.recentSuccessfulToolNames(currentPath),
                forcedDirectToolNames = forcedTools,
            ).also { plan ->
                // This step is pure CPU over every enabled tool schema, so it is the one most
                // likely to regress silently as a user adds connectors.
                TimingLog.mark {
                    "capability prepare took ${System.currentTimeMillis() - t1}ms " +
                        "pool=${toolPool.size} inline=${plan.inlineTools.size} " +
                        "deferred=${plan.deferredTools.size} route=${plan.route.mode}"
                }
            }.let { plan ->
                if (
                    toolPool.isEmpty() &&
                    config.providerName == Constants.PROVIDER_GOOGLE
                ) {
                    val nativeToolTokens =
                        McpDeferredToolProvider.estimateNativeToolEnvelopeTokens(
                            codeExecutionEnabled = config.codeExecutionEnabled,
                            googleSearchEnabled = config.googleSearchEnabled,
                        )
                    check(nativeToolTokens <= McpDeferredToolProvider.MAX_WIRE_SCHEMA_TOKENS)
                    plan.copy(wireSchemaTokens = nativeToolTokens)
                } else {
                    plan
                }
            }
        } else {
            builtinTools = emptyList()
            mcpTools = emptyList()
            mcpDeferredToolProvider.clear(ctx.capabilityRequestId ?: conversationId)
            null
        }
        val allTools = if (exposure == null) {
            // Model cannot call tools at all — omit the `tools` field completely.
            emptyList()
        } else {
            exposure.inlineTools + mcpDeferredToolProvider.definitions(ctx)
        }
        // The token estimate is deliberately computed inside the lambda: building this message
        // eagerly tokenized the entire wire tool surface on every request, including release
        // builds where DebugLog then discards the string.
        DebugLog.d("AgoraTiming") {
            "tools: builtin=${builtinTools.size} mcp=${mcpTools.size} " +
                "route=${exposure?.route?.mode ?: "unsupported"} " +
                "deferred=${exposure?.deferredTools?.size ?: 0} " +
                "supported=$functionToolsSupported transport=${provider.functionToolTransport} total=${allTools.size} " +
                "(~${McpDeferredToolProvider.estimateSchemaTokens(allTools)} tok)"
        }
        val geminiCompatibility = if (config.providerName == Constants.PROVIDER_GOOGLE) {
            effectiveGeminiToolCompatibility(
                modelId = config.modelId,
                hasFunctionTools = allTools.isNotEmpty(),
                codeExecutionEnabled = config.codeExecutionEnabled,
                googleSearchEnabled = config.googleSearchEnabled,
            )
        } else {
            null
        }
        val providerConfig = ProviderConfig(
            apiKey = config.apiKey,
            modelId = config.modelId,
            systemPrompt = config.effectiveSystemPrompt,
            maxContextWindow = config.maxContextWindow,
            codeExecutionEnabled =
                geminiCompatibility?.codeExecutionEnabled ?: config.codeExecutionEnabled,
            googleSearchEnabled =
                geminiCompatibility?.googleSearchEnabled ?: config.googleSearchEnabled,
            thinkingEnabled = config.thinkingEnabled,
            thinkingLevel = config.thinkingLevel,
            thinkingBudgetEnabled = config.thinkingBudgetEnabled,
            thinkingBudgetTokens = config.thinkingBudgetTokens,
            fastEnabled = config.fastEnabled,
            baseUrl = config.baseUrl,
            // An empty list still serialises as `"tools":[]` because the field is non-null and the
            // Json instance has encodeDefaults=true — some upstreams treat that as "tool calling
            // requested" and answer differently. Collapse to null so the key disappears entirely.
            tools = allTools.ifEmpty { null },
            toolChoice = forcedToolChoice(ctx, provider.functionToolTransport, allTools),
            userPrepend = config.userPrepend,
            userPostpend = config.userPostpend,
            temperature = config.temperature,
            maxTokens = config.maxTokens,
            topP = config.topP,
            frequencyPenalty = config.frequencyPenalty,
            presencePenalty = config.presencePenalty,
            alternateApiKeys = config.alternateApiKeys,
            // Lets Stop sever exactly this conversation's socket instead of waiting out a read
            // tick while the upstream keeps generating (and billing).
            streamTag = conversationId
        )
        TimingLog.since(t_buildApiPath) { "buildApiPath: total" }
        return Triple(currentPath, providerConfig, exposure)
    }

    suspend fun generate(
        conversationId: String,
        modelMessageId: String,
        startTime: Long,
        isRegenerate: Boolean,
        replaceMessageId: String?,
        modelName: String,
        config: GenerationConfig,
        ctx: GenerationContext,
        generationJob: kotlinx.coroutines.Job?,
        callbacks: GenerationCallbacks
    ) {
        // Destructure into locals so the body below reads exactly as before.
        val (
            onStreamUpdate,
            onLoadingChange,
            onGeneratingIdChange,
            onStreamClear,
            isLatestPersist,
            persistMessagesIfLatest,
        ) = callbacks
        val provider = getProviderInstance(config.providerName)
        // modelMessageId can be reused by retry/regenerate while the old coroutine is still
        // unwinding. Capability state needs a unique generation token so the old finally cannot
        // clear the replacement generation's registry.
        val capabilityRequestId = UUID.randomUUID().toString()
        val requestCtx = ctx.copy(capabilityRequestId = capabilityRequestId)

        onLoadingChange(true)
        onGeneratingIdChange(conversationId)
        com.newoether.agora.util.CrashReporter.note("generate provider=${config.providerName} regen=$isRegenerate")
        // Promote FGS ASAP on Main — before any IO/transcription delay — so startForeground
        // lands well inside the system 5s window even if generate fails immediately after.
        // Pair exactly one FGS.stop with a successful start in finally. User stop/regenerate
        // must only cancel the generation; its finally owns FGS teardown.
        var fgsStarted = false
        // Recorded INSIDE the Main-thread block: a cancel landing while this call is suspended
        // resumes with the refcount already incremented but never assigns the return value, so
        // the local alone cannot tell "claimed" from "never ran".
        val fgsClaimed = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            withContext(Dispatchers.Main.immediate) {
                fgsClaimed.set(AgoraForegroundService.start(app))
            }
            fgsStarted = fgsClaimed.get()
        } catch (e: CancellationException) {
            // On the JVM CancellationException extends IllegalStateException, so the generic
            // catch below silently swallowed the cancel signal: a stopped or retried generation
            // carried on running and its foreground-service refcount was never balanced, which
            // on API 35+ eventually trips the dataSync budget. Cancellation must propagate —
            // and since the terminal finally that owns teardown is not in scope yet, release
            // the claim here if it actually landed.
            if (fgsClaimed.get()) {
                withContext(NonCancellable) { AgoraForegroundService.stop(app) }
            }
            throw e
        } catch (e: Exception) {
            // Never let FGS start failure abort generation.
            fgsStarted = fgsClaimed.get()
            com.newoether.agora.util.CrashReporter.note("generate FGS.start threw ${e.javaClass.simpleName}")
            DebugLog.w("AgoraVM", "FGS start failed", e)
        }

        var totalText = ""
        var totalThoughts = ""
        val thinkingPlaceholder = context.getString(R.string.thinking_ellipsis)
        var totalThoughtTitle: String? = null
        // Usage is reported per REQUEST, and a tool-using turn makes several of them. The round
        // counter holds the in-flight request's figure; it is settled into the running total
        // after each collect. This used to be a single field that each round OVERWROTE, so the
        // number shown was the last round only and understated what the turn actually cost.
        val usageAccumulator = GenerationUsageAccumulator()
        /** Epoch-ms when the current HTTP request was dispatched (reset each round). */
        var requestDispatchMs = 0L
        var usageRoundOpen = false
        var roundToolExecutionMs = 0L
        val roundToolNames = mutableListOf<String>()
        var roundOriginalToolResultChars = 0
        var roundInjectedToolResultChars = 0
        val roundBrokerActions = mutableListOf<String>()
        var routeMode: String? = null
        var inlineSchemaTokens = 0
        var brokerSchemaTokens = 0
        var wireSchemaTokens = 0
        var wireToolCount = 0
        /** TTFT for this turn (first TextChunk or ThoughtChunk after request). */
        var ttftMs = 0L
        var totalThoughtTimeMs: Long? = null
        var cumulativeThoughtMs: Long = 0
        var currentThoughtStartMs: Long? = null
        var currentThoughtDurationMs: Long = 0
        var currentStatus = MessageStatus.SENDING
        var retryText: String? = null
        // Do NOT seed a blank answer segment — it makes tool-only turns look like
        // "has answer segments" in timeline mode while rendering nothing.
        val segments = mutableListOf<MessageSegment>()
        val generatedImages = mutableListOf<String>()
        var currentAnswerBuf = StringBuilder()
        var currentThoughtBuf = StringBuilder()
        var currentThoughtSignature: String? = null
        // This suspending read sits between the FGS claim and the terminal try/finally, so a Stop
        // landing here used to skip teardown entirely: the refcount leaked and the placeholder row
        // stayed SENDING forever. Everything from the claim onward has to release it on any exit.
        val placeholder = try {
            conversations.getMessagesForConversationSnapshot(conversationId).find { it.id == modelMessageId }
        } catch (t: Throwable) {
            if (fgsStarted) {
                withContext(NonCancellable) { AgoraForegroundService.stop(app) }
            }
            throw t
        }
        val parentId = placeholder?.parentId
        var toolPath = emptyList<ChatMessage>()

        fun liveThoughtDurationMs(): Long? {
            val liveElapsed = currentThoughtStartMs?.let { System.currentTimeMillis() - it } ?: 0L
            return (currentThoughtDurationMs + liveElapsed).takeIf { it > 0L }
        }

        fun finishCurrentThoughtTiming() {
            val startedAt = currentThoughtStartMs ?: return
            val elapsed = System.currentTimeMillis() - startedAt
            if (elapsed > 0L) {
                cumulativeThoughtMs += elapsed
                currentThoughtDurationMs += elapsed
                totalThoughtTimeMs = cumulativeThoughtMs
            }
            currentThoughtStartMs = null
        }

        fun settleUsageRound() {
            if (!usageRoundOpen) return
            val durationMs = requestDispatchMs
                .takeIf { it > 0L }
                ?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) }
                ?: 0L
            usageAccumulator.settleRound(
                RoundUsageMetadata(
                    durationMs = durationMs,
                    toolExecutionMs = roundToolExecutionMs,
                    toolNames = roundToolNames.toList(),
                    originalToolResultChars = roundOriginalToolResultChars,
                    injectedToolResultChars = roundInjectedToolResultChars,
                    brokerActions = roundBrokerActions.toList(),
                    routeMode = routeMode,
                    inlineSchemaTokens = inlineSchemaTokens,
                    brokerSchemaTokens = brokerSchemaTokens,
                    wireSchemaTokens = wireSchemaTokens,
                    wireToolCount = wireToolCount,
                ),
            )
            usageRoundOpen = false
            roundToolExecutionMs = 0L
            roundToolNames.clear()
            roundOriginalToolResultChars = 0
            roundInjectedToolResultChars = 0
            roundBrokerActions.clear()
        }

        try {
            // Stage 1: Image Transcription
            var transcriptionPerformed = false
            if (ctx.imageTranscriptionEnabled && ctx.transcriptionModelId.isNotEmpty()) {
                kotlinx.coroutines.delay(500) // let foreground service fully start
                val targets = transcriptionManager.collectTargets(conversationId, parentId)
                if (targets.isNotEmpty()) {
                    val (transcriptionSegments, transcriptionError) = transcriptionManager.transcribe(
                        targets, conversationId,
                        ctx.transcriptionProviderName, ctx.transcriptionModelId,
                        ctx.transcriptionApiKey, ctx.transcriptionBaseUrl,
                        ctx.imageTranscriptionPrompt,
                        generationJob, modelMessageId, startTime, onStreamUpdate
                    )
                    if (transcriptionError != null) {
                        totalText = transcriptionError
                        currentStatus = MessageStatus.ERROR
                        transcriptionPerformed = true
                    } else {
                        segments.addAll(0, transcriptionSegments)
                        transcriptionPerformed = true
                    }
                }
            }

            if (currentStatus != MessageStatus.ERROR) {
            val (currentPath, rawProviderConfig, exposurePlan) = buildApiPath(
                parentId,
                conversationId,
                isRegenerate,
                replaceMessageId,
                config,
                requestCtx,
                provider,
            )
            toolPath = currentPath
            routeMode = exposurePlan?.route?.mode?.name ?: "UNSUPPORTED"
            inlineSchemaTokens = exposurePlan?.inlineSchemaTokens ?: 0
            brokerSchemaTokens = exposurePlan?.brokerSchemaTokens ?: 0
            wireSchemaTokens = exposurePlan?.wireSchemaTokens ?: 0
            wireToolCount = (rawProviderConfig.tools?.size ?: 0) +
                if (config.providerName == Constants.PROVIDER_GOOGLE) {
                    listOf(
                        rawProviderConfig.codeExecutionEnabled,
                        rawProviderConfig.googleSearchEnabled,
                    ).count { it }
                } else {
                    0
                }
            val providerConfig = if (transcriptionPerformed) rawProviderConfig.copy(includeImages = false) else rawProviderConfig
            val wireToolNames = providerConfig.tools
                .orEmpty()
                .mapTo(mutableSetOf()) { it.function.name }

            var toolCallData: ToolCallData? = null
            var toolCallDataList: List<ToolCallData> = emptyList()
            var lastGenerationError: GenerationError? = null
            var usedTools = false
            var answeredAfterLastTool = false
            val roundToolSegments = mutableListOf<MessageSegment>()
            val completedToolNames = mutableSetOf<String>()
            // Concrete capabilities exercised this turn, including those reached through the
            // broker. Drives promotion so a repeat call skips the search/invoke detour.
            val invokedCapabilityNames = mutableSetOf<String>()
            val toolLoopBudget = ToolLoopBudget()
            var futureToolExecutionBlock: String? = null
            var continuationReservedForProviderRound = false
            var nextToolParentId: String? = modelMessageId

            class PendingProtocolRound(
                val messages: List<ChatMessage>,
                val entities: List<MessageEntity>,
                val nextParentId: String?,
            )

            var pendingProtocolRound: PendingProtocolRound? = null

            var lastEmitMs = 0L

            fun recordRoundToolName(name: String) {
                if (
                    name in wireToolNames &&
                    name !in roundToolNames &&
                    roundToolNames.size < MAX_ROUND_TOOL_NAMES
                ) {
                    roundToolNames += name.take(MAX_RECORDED_TOOL_NAME_CHARS)
                }
            }

            fun beginProviderRound() {
                toolLoopBudget.startProviderRound()
                continuationReservedForProviderRound = false
            }

            fun toolExecutionBlockDiagnostic(pendingResults: Int = 1): String? {
                futureToolExecutionBlock?.let { return it }
                toolLoopBudget.executionBlockDiagnostic(pendingResults)?.let { return it }
                if (!continuationReservedForProviderRound) {
                    val reservation = toolLoopBudget.startContinuation()
                    if (!reservation.allowed) return reservation.diagnostic
                    continuationReservedForProviderRound = true
                }
                return null
            }

            fun modelMessage(): ChatMessage {
                val usage = usageAccumulator.snapshot()
                return ChatMessage(
                    id = modelMessageId, parentId = parentId,
                    text = totalText, thoughts = totalThoughts.ifBlank { null },
                    thoughtTitle = totalThoughtTitle, tokenCount = usage.tokenCount,
                    promptTokens = usage.promptTokens,
                    cachedPromptTokens = usage.cachedPromptTokens,
                    cacheTelemetryAvailable = usage.cacheTelemetryAvailable,
                    completionTokens = usage.completionTokens,
                    ttftMs = ttftMs,
                    roundUsage = usageAccumulator.rounds(),
                    status = currentStatus, participant = Participant.MODEL,
                    timestamp = startTime, thoughtTimeMs = totalThoughtTimeMs,
                    modelName = modelName, toolCall = toolCallData,
                    images = generatedImages.toList(),
                    segments = buildLiveSegments(
                        segments,
                        currentAnswerBuf,
                        currentThoughtBuf,
                        currentThoughtSignature,
                        liveThoughtDurationMs()
                    ),
                    retryText = retryText
                )
            }

            fun flushAnswerSegment() {
                if (currentAnswerBuf.isNotEmpty()) {
                    appendMergedSegment(segments, MessageSegment(type = "answer", content = currentAnswerBuf.toString()))
                    currentAnswerBuf = StringBuilder()
                }
            }

            fun flushThoughtSegment() {
                finishCurrentThoughtTiming()
                if (currentThoughtBuf.isNotEmpty()) {
                    appendMergedSegment(segments, MessageSegment(
                        type = "thought",
                        content = currentThoughtBuf.toString(),
                        signature = currentThoughtSignature,
                        durationMs = currentThoughtDurationMs.takeIf { it > 0L }
                    ))
                    currentThoughtBuf = StringBuilder()
                    currentThoughtSignature = null
                }
                currentThoughtDurationMs = 0L
            }

            fun stageProtocolRound(
                calls: List<ToolCallData>,
                transmittedSegments: List<MessageSegment>,
            ) {
                if (calls.isEmpty() || pendingProtocolRound != null) return
                val toolMsgId = "${Constants.TOOL_MSG_PREFIX}${UUID.randomUUID()}"
                val toolSegments = transmittedSegments.ifEmpty { null }
                val allSegmentsJson = Json.encodeToString(toolSegments ?: calls.map { call ->
                    MessageSegment(
                        type = "tool",
                        toolName = call.toolName,
                        toolArgs = call.arguments,
                        toolResult = call.result,
                        signature = call.signature,
                        toolCallId = call.toolCallId,
                    )
                })
                val resultMessages = calls.map { call ->
                    ChatMessage(
                        id = "${Constants.RESULT_MSG_PREFIX}${UUID.randomUUID()}",
                        parentId = toolMsgId,
                        text = SearchResultFormatter.format(call.result, context),
                        participant = Participant.USER,
                        status = MessageStatus.SUCCESS,
                        toolCall = call,
                    )
                }
                val toolMessage = ChatMessage(
                    id = toolMsgId,
                    parentId = nextToolParentId,
                    text = "",
                    participant = Participant.MODEL,
                    status = MessageStatus.SUCCESS,
                    toolCall = calls.first(),
                    segments = toolSegments,
                )
                val timestamp = System.currentTimeMillis()
                val entities = buildList {
                    add(
                        MessageEntity(
                            id = toolMsgId,
                            conversationId = conversationId,
                            parentId = nextToolParentId,
                            text = "",
                            thoughts = null,
                            status = MessageStatus.SUCCESS,
                            participant = Participant.MODEL,
                            timestamp = timestamp,
                            toolCallJson = allSegmentsJson,
                        ),
                    )
                    calls.forEachIndexed { index, call ->
                        add(
                            MessageEntity(
                                id = resultMessages[index].id,
                                conversationId = conversationId,
                                parentId = toolMsgId,
                                text = call.result,
                                thoughts = null,
                                status = MessageStatus.SUCCESS,
                                participant = Participant.USER,
                                timestamp = timestamp + index + 1L,
                                toolCallJson = Json.encodeToString(
                                    listOf(
                                        MessageSegment(
                                            type = "tool",
                                            toolName = call.toolName,
                                            toolArgs = call.arguments,
                                            toolResult = call.result,
                                            signature = call.signature,
                                            toolCallId = call.toolCallId,
                                        ),
                                    ),
                                ),
                            ),
                        )
                    }
                }
                pendingProtocolRound = PendingProtocolRound(
                    messages = listOf(toolMessage) + resultMessages,
                    entities = entities,
                    nextParentId = resultMessages.lastOrNull()?.id ?: toolMsgId,
                )
            }

            suspend fun flushPendingProtocolRound(): Boolean {
                val pending = pendingProtocolRound ?: return true
                if (!persistMessagesIfLatest(pending.entities)) return false
                toolPath = toolPath + pending.messages
                nextToolParentId = pending.nextParentId
                pendingProtocolRound = null
                return true
            }

            suspend fun persistPendingProtocolOnCancellation() {
                // Only completed/diagnosed calls may enter protocol history. A raw null segment
                // would leave providers with a tool call that has no matching result.
                segments.removeAll { it.type == "tool" && it.toolResult == null }
                if (toolCallDataList.isEmpty()) return
                val callIds = toolCallDataList.mapTo(mutableSetOf()) { it.toolCallId }
                val completedSegments = roundToolSegments.filter { it.toolCallId in callIds }
                val thoughtSegments = segments.filter { it.type == "thought" }
                stageProtocolRound(
                    calls = toolCallDataList,
                    transmittedSegments =
                        if (thoughtSegments.isNotEmpty()) {
                            thoughtSegments + completedSegments
                        } else {
                            completedSegments
                        },
                )
                try {
                    withContext(NonCancellable) {
                        if (flushPendingProtocolRound()) {
                            toolCallDataList = emptyList()
                            roundToolSegments.clear()
                        }
                    }
                } catch (error: Exception) {
                    DebugLog.e(
                        "AgoraVM",
                        "Failed to persist completed tool protocol during cancellation",
                        error,
                    )
                }
            }

            suspend fun handleStreamEvent(event: StreamEvent) {
                when (event) {
                    is StreamEvent.TextChunk -> {
                        val answerText = if (currentStatus == MessageStatus.THINKING) event.text.trimStart() else event.text
                        if (currentStatus == MessageStatus.THINKING && answerText.isBlank()) {
                            retryText = null
                            return
                        }
                        if (currentStatus == MessageStatus.THINKING) {
                            flushThoughtSegment()
                        }
                        // Record TTFT on the very first visible token of this turn.
                        if (ttftMs == 0L && requestDispatchMs > 0L && answerText.isNotBlank()) {
                            ttftMs = System.currentTimeMillis() - requestDispatchMs
                        }
                        totalText += answerText
                        currentAnswerBuf.append(answerText)
                        if (answerText.isNotBlank()) {
                            currentStatus = MessageStatus.SENDING
                            if (usedTools) answeredAfterLastTool = true
                        }
                        retryText = null
                    }
                    is StreamEvent.ThoughtChunk -> {
                        flushAnswerSegment()
                        currentStatus = MessageStatus.THINKING
                        retryText = null
                        // Count TTFT on first thinking token too (thinking models think before speaking).
                        if (ttftMs == 0L && requestDispatchMs > 0L && event.thought.isNotBlank()) {
                            ttftMs = System.currentTimeMillis() - requestDispatchMs
                        }
                        if (currentThoughtStartMs == null) {
                            currentThoughtStartMs = System.currentTimeMillis()
                        }
                        if (totalThoughts.isEmpty()) totalThoughts = thinkingPlaceholder
                        if (event.thought.isNotEmpty()) {
                            currentThoughtBuf.append(event.thought)
                            if (totalThoughts == thinkingPlaceholder) totalThoughts = event.thought
                            else totalThoughts += event.thought
                        }
                        if (event.title != null) totalThoughtTitle = event.title
                        if (event.signature != null) currentThoughtSignature = event.signature
                    }
                    is StreamEvent.UsageUpdate -> {
                        usageAccumulator.update(event)
                        if (totalText.isEmpty() && event.thoughtsTokenCount > 0) {
                            currentStatus = MessageStatus.THINKING
                            if (currentThoughtStartMs == null) {
                                currentThoughtStartMs = System.currentTimeMillis()
                            }
                            if (totalThoughts.isEmpty()) totalThoughts = thinkingPlaceholder
                        }
                    }
                    is StreamEvent.Retrying -> {
                        retryText = context.getString(R.string.generation_retry_attempt, event.attempt, event.maxAttempts)
                        onStreamUpdate(modelMessage())
                    }
                    is StreamEvent.Error -> {
                        flushThoughtSegment()
                        retryText = null
                        lastGenerationError = event.error
                        if (toolCallData == null && toolCallDataList.isEmpty()) {
                            totalText = formatGenerationDiagnostic(event.error)
                            currentStatus = MessageStatus.ERROR
                        }
                    }
                    is StreamEvent.ToolCallRequest -> {
                        flushAnswerSegment()
                        flushThoughtSegment()
                        val args = normalizeToolArguments(event.arguments)
                        val callId = event.id.takeIf { it.isNotBlank() }
                            ?: buildToolCallId(event.name, args)
                        val ts = MessageSegment(
                            type = "tool",
                            toolName = event.name,
                            toolArgs = args,
                            toolResult = null,
                            toolCallId = callId,
                            signature = event.signature,
                        )
                        appendMergedSegment(segments, ts)
                        usedTools = true
                        answeredAfterLastTool = false
                        // Stay in TOOL_CALLING until the multi-tool loop finishes a follow-up
                        // answer. Switching to SENDING here made short tool-only turns look
                        // "completed" before the model could reply with search results.
                        currentStatus = MessageStatus.TOOL_CALLING
                        onStreamUpdate(modelMessage())
                        lastEmitMs = System.currentTimeMillis()
                        val executionBlock = toolExecutionBlockDiagnostic()
                        if (executionBlock != null) {
                            val budgetedDiagnostic =
                                toolLoopBudget.budgetDiagnostics(executionBlock, 1).single()
                            val idx = segments.indexOfLast { it.toolCallId == callId }
                            if (idx >= 0) {
                                segments[idx] = segments[idx].copy(toolResult = budgetedDiagnostic)
                                roundToolSegments.add(segments[idx])
                            }
                            if (continuationReservedForProviderRound) {
                                val blockedCall = ToolCallData(
                                    toolName = event.name,
                                    arguments = args,
                                    result = budgetedDiagnostic,
                                    signature = event.signature,
                                    toolCallId = callId,
                                )
                                if (toolCallData == null) toolCallData = blockedCall
                                toolCallDataList = toolCallDataList + blockedCall
                                roundInjectedToolResultChars += budgetedDiagnostic.length
                                currentStatus = MessageStatus.TOOL_CALLING
                            } else {
                                totalText = executionBlock
                                toolCallData = null
                                toolCallDataList = emptyList()
                                currentStatus = MessageStatus.ERROR
                            }
                            onStreamUpdate(modelMessage())
                            lastEmitMs = System.currentTimeMillis()
                            return
                        }
                        recordRoundToolName(event.name)
                        brokerAction(event.name, args)?.let(roundBrokerActions::add)
                        val toolStartedAt = System.currentTimeMillis()
                        val executionCtx = requestCtx.copy(
                            toolResultMaxChars = toolLoopBudget.maxResultCharsPerPending(),
                        )
                        val result = try {
                            val executed = executeTool(event.name, args, executionCtx)
                            // A provider may accidentally translate CancellationException into an
                            // ordinary error payload. Re-check the owning coroutine before treating
                            // a side effect as confirmed so cancellation remains "completion
                            // unknown" and is never retried automatically.
                            currentCoroutineContext().ensureActive()
                            executed
                        } catch (cancelled: CancellationException) {
                            val diagnostic = toolLoopBudget.budgetDiagnostics(
                                "[Agora stopped while this tool was running; completion is unknown. " +
                                    "Do not retry automatically.]",
                                1,
                            ).single()
                            val idx = segments.indexOfLast { it.toolCallId == callId }
                            if (idx >= 0) {
                                segments[idx] = segments[idx].copy(toolResult = diagnostic)
                                roundToolSegments.add(segments[idx])
                            }
                            val unknownCall = ToolCallData(
                                toolName = event.name,
                                arguments = args,
                                result = diagnostic,
                                signature = event.signature,
                                toolCallId = callId,
                            )
                            if (toolCallData == null) toolCallData = unknownCall
                            toolCallDataList = toolCallDataList + unknownCall
                            roundInjectedToolResultChars += diagnostic.length
                            throw cancelled
                        } finally {
                            roundToolExecutionMs +=
                                (System.currentTimeMillis() - toolStartedAt).coerceAtLeast(0L)
                        }
                        completedToolNames += event.name
                        invokedCapabilityName(event.name, args)?.let(invokedCapabilityNames::add)
                        roundOriginalToolResultChars +=
                            originalToolResultChars(event.name, result)
                        generatedImages.addAll(
                            imageGenToolProvider.drainImages(capabilityRequestId),
                        )
                        val budgetedRound = toolLoopBudget.budgetRound(listOf(result))
                        val clipped = budgetedRound.results.single()
                        roundInjectedToolResultChars += clipped.length
                        if (budgetedRound.diagnostic != null) {
                            futureToolExecutionBlock =
                                futureToolExecutionBlock ?: budgetedRound.diagnostic
                        }
                        val idx = segments.indexOfLast { it.toolCallId == callId }
                        if (idx >= 0) {
                            segments[idx] = segments[idx].copy(toolResult = clipped)
                            roundToolSegments.add(segments[idx])
                        }
                        val tcd = ToolCallData(
                            toolName = event.name,
                            arguments = args,
                            result = clipped,
                            signature = event.signature,
                            toolCallId = callId,
                        )
                        if (toolCallData == null) toolCallData = tcd
                        toolCallDataList = toolCallDataList + tcd
                        currentStatus = MessageStatus.TOOL_CALLING
                        onStreamUpdate(modelMessage())
                        lastEmitMs = System.currentTimeMillis()
                    }
                    is StreamEvent.ToolCallsRequest -> {
                        flushAnswerSegment()
                        flushThoughtSegment()
                        val normalizedCalls = event.calls.map { call ->
                            val args = normalizeToolArguments(call.arguments)
                            val callId = call.id.takeIf { it.isNotBlank() }
                                ?: buildToolCallId(call.name, args)
                            Triple(call, args, callId)
                        }
                        normalizedCalls.forEach { (call, args, callId) ->
                            appendMergedSegment(
                                segments,
                                MessageSegment(
                                    type = "tool",
                                    toolName = call.name,
                                    toolArgs = args,
                                    toolResult = null,
                                    toolCallId = callId,
                                    signature = call.signature,
                                ),
                            )
                        }
                        usedTools = true
                        answeredAfterLastTool = false
                        currentStatus = MessageStatus.TOOL_CALLING
                        onStreamUpdate(modelMessage())
                        lastEmitMs = System.currentTimeMillis()
                        if (normalizedCalls.isEmpty()) {
                            totalText = "[Agora tool loop stopped: provider returned an empty tool-call batch.]"
                            currentStatus = MessageStatus.ERROR
                            onStreamUpdate(modelMessage())
                            return
                        }
                        val executionBlock =
                            toolExecutionBlockDiagnostic(normalizedCalls.size)
                        if (executionBlock != null) {
                            val budgetedDiagnostics =
                                toolLoopBudget.budgetDiagnostics(executionBlock, normalizedCalls.size)
                            val blockedCalls = normalizedCalls.mapIndexed { index, (call, args, callId) ->
                                val budgetedDiagnostic = budgetedDiagnostics[index]
                                val idx = segments.indexOfLast { it.toolCallId == callId }
                                if (idx >= 0) {
                                    segments[idx] =
                                        segments[idx].copy(toolResult = budgetedDiagnostic)
                                    roundToolSegments.add(segments[idx])
                                }
                                ToolCallData(
                                    toolName = call.name,
                                    arguments = args,
                                    result = budgetedDiagnostic,
                                    signature = call.signature,
                                    toolCallId = callId,
                                )
                            }
                            if (continuationReservedForProviderRound) {
                                if (toolCallData == null) toolCallData = blockedCalls.firstOrNull()
                                toolCallDataList = toolCallDataList + blockedCalls
                                roundInjectedToolResultChars +=
                                    budgetedDiagnostics.sumOf(String::length)
                                currentStatus = MessageStatus.TOOL_CALLING
                            } else {
                                totalText = executionBlock
                                toolCallData = null
                                toolCallDataList = emptyList()
                                currentStatus = MessageStatus.ERROR
                            }
                            onStreamUpdate(modelMessage())
                            lastEmitMs = System.currentTimeMillis()
                            return
                        }
                        fun commitCompletedBatch(
                            calls: List<Triple<StreamEvent.ToolCallRequest, String, String>>,
                            rawResults: List<String>,
                        ): List<ToolCallData> {
                            val budgetedRound = toolLoopBudget.budgetRound(rawResults)
                            roundInjectedToolResultChars +=
                                budgetedRound.results.sumOf(String::length)
                            if (budgetedRound.diagnostic != null) {
                                futureToolExecutionBlock =
                                    futureToolExecutionBlock ?: budgetedRound.diagnostic
                            }
                            return calls.mapIndexed { index, (call, args, callId) ->
                                val clipped = budgetedRound.results[index]
                                val idx = segments.indexOfLast { it.toolCallId == callId }
                                if (idx >= 0) {
                                    segments[idx] = segments[idx].copy(toolResult = clipped)
                                    roundToolSegments.add(segments[idx])
                                }
                                ToolCallData(
                                    toolName = call.name,
                                    arguments = args,
                                    result = clipped,
                                    signature = call.signature,
                                    toolCallId = callId,
                                )
                            }
                        }

                        val rawResults = mutableListOf<String>()
                        var activeBatchIndex: Int? = null
                        val batchExecutionCtx = requestCtx.copy(
                            toolResultMaxChars =
                                toolLoopBudget.maxResultCharsPerPending(normalizedCalls.size),
                        )
                        try {
                            for ((index, normalizedCall) in normalizedCalls.withIndex()) {
                                val (call, args, _) = normalizedCall
                                recordRoundToolName(call.name)
                                brokerAction(call.name, args)?.let(roundBrokerActions::add)
                                val toolStartedAt = System.currentTimeMillis()
                                activeBatchIndex = index
                                val result = try {
                                    val executed =
                                        executeTool(call.name, args, batchExecutionCtx)
                                    currentCoroutineContext().ensureActive()
                                    executed
                                } finally {
                                    roundToolExecutionMs +=
                                        (System.currentTimeMillis() - toolStartedAt)
                                            .coerceAtLeast(0L)
                                }
                                completedToolNames += call.name
                                invokedCapabilityName(call.name, args)
                                    ?.let(invokedCapabilityNames::add)
                                roundOriginalToolResultChars +=
                                    originalToolResultChars(call.name, result)
                                generatedImages.addAll(
                                    imageGenToolProvider.drainImages(capabilityRequestId),
                                )
                                rawResults += result
                                activeBatchIndex = null
                            }
                        } catch (cancelled: CancellationException) {
                            // Preserve every result whose side effect completed before cancellation.
                            // Otherwise a retry sees no evidence of the successful prefix and may
                            // repeat those mutations.
                            val completedCalls = normalizedCalls.take(rawResults.size)
                            val completed = commitCompletedBatch(completedCalls, rawResults)
                            val unresolvedCalls = normalizedCalls.drop(rawResults.size)
                            val unknownCompletionDiagnostic =
                                "[Agora stopped before this tool returned; completion is unknown. " +
                                    "Do not retry automatically.]"
                            val notExecutedDiagnostic =
                                "[Agora did not execute this tool because generation stopped.]"
                            val hasInFlightCall = activeBatchIndex == rawResults.size
                            val unresolvedResults = toolLoopBudget.budgetDiagnostics(
                                unresolvedCalls.indices.map { unresolvedIndex ->
                                    if (hasInFlightCall && unresolvedIndex == 0) {
                                        unknownCompletionDiagnostic
                                    } else {
                                        notExecutedDiagnostic
                                    }
                                },
                            )
                            val unresolved = unresolvedCalls.mapIndexed {
                                    index, (call, args, callId) ->
                                val diagnostic = unresolvedResults[index]
                                val idx = segments.indexOfLast { it.toolCallId == callId }
                                if (idx >= 0) {
                                    segments[idx] = segments[idx].copy(toolResult = diagnostic)
                                    roundToolSegments.add(segments[idx])
                                }
                                ToolCallData(
                                    toolName = call.name,
                                    arguments = args,
                                    result = diagnostic,
                                    signature = call.signature,
                                    toolCallId = callId,
                                )
                            }
                            if (toolCallData == null) toolCallData = completed.firstOrNull()
                                ?: unresolved.firstOrNull()
                            toolCallDataList = toolCallDataList + completed + unresolved
                            roundInjectedToolResultChars +=
                                unresolvedResults.sumOf(String::length)
                            throw cancelled
                        }
                        val tcds = commitCompletedBatch(normalizedCalls, rawResults)
                        if (toolCallData == null) toolCallData = tcds.firstOrNull()
                        toolCallDataList = toolCallDataList + tcds
                        currentStatus = MessageStatus.TOOL_CALLING
                        onStreamUpdate(modelMessage())
                        lastEmitMs = System.currentTimeMillis()
                    }
                }

                // UI throttle: keep streaming snappy (~12-15 fps) without flooding Compose
                // on every SSE token. 500 ms made long replies feel laggy or stuck.
                val now = System.currentTimeMillis()
                val isSignificant = event is StreamEvent.Error
                if (now - lastEmitMs >= 80 || isSignificant) {
                    onStreamUpdate(modelMessage())
                    lastEmitMs = now
                }
            }

            val projectedPath = projectAssistantImagesToLatestUserMessage(currentPath, providerConfig.includeImages)
            val apiPath = applyUserTemplate(projectedPath, config.userPrepend, config.userPostpend)
            beginProviderRound()
            requestDispatchMs = System.currentTimeMillis()
            usageRoundOpen = true
            suspend fun collectProviderRound(
                path: List<ChatMessage>,
                roundConfig: ProviderConfig,
            ) {
                try {
                    provider.generateResponse(path, roundConfig).collect { event ->
                        handleStreamEvent(event)
                    }
                } catch (cancelled: CancellationException) {
                    persistPendingProtocolOnCancellation()
                    throw cancelled
                }
            }

            try {
            collectProviderRound(apiPath, providerConfig)
            // Settle this request's usage before any tool round issues the next one.
            settleUsageRound()
            finishCurrentThoughtTiming()
            // Always emit final state after collection completes
            if (generationJob?.isCancelled != true) {
                onStreamUpdate(modelMessage())
            }

            // Multi-tool loop
            var toolRound = 0
            var activeProviderConfig = providerConfig

            while (toolCallDataList.isNotEmpty() && currentStatus != MessageStatus.ERROR && currentCoroutineContext().isActive) {
                toolRound++
                val roundToolList = roundToolSegments.toList()
                roundToolSegments.clear()
                val thoughtSegs = segments.filter { it.type == "thought" }
                val txedSegments = if (thoughtSegs.isNotEmpty()) thoughtSegs + roundToolList else roundToolList
                val tcds = toolCallDataList
                stageProtocolRound(tcds, txedSegments)
                val persisted = withContext(NonCancellable) {
                    flushPendingProtocolRound()
                }
                if (!persisted) {
                    currentStatus = MessageStatus.STOPPED
                    break
                }

                toolCallData = null
                toolCallDataList = emptyList()

                lastEmitMs = 0L
                // Keep generating UI active while we ask the model to continue from tool results.
                currentStatus = MessageStatus.SENDING
                onStreamUpdate(modelMessage())

                activeProviderConfig = continuationProviderConfig(
                    config = activeProviderConfig,
                    completedToolNames = completedToolNames,
                )
                // The exposure plan was built before the model spoke, so anything it reached
                // through the broker is still deferred. Put those capabilities directly on the
                // wire now that an actual invocation has proven them relevant — otherwise calling
                // the same connector twice pays for a second search/invoke round trip.
                mcpDeferredToolProvider
                    .promoteInvoked(capabilityRequestId, invokedCapabilityNames)
                    ?.let { promotedTools ->
                        activeProviderConfig =
                            activeProviderConfig.copy(tools = promotedTools.ifEmpty { null })
                        TimingLog.mark {
                            "capability promote: wire=${promotedTools.size} " +
                                "invoked=${invokedCapabilityNames.size}"
                        }
                    }
                val continuationConfig = activeProviderConfig
                val projectedToolPath = projectAssistantImagesToLatestUserMessage(
                    toolPath,
                    continuationConfig.includeImages,
                )
                val apiToolPath = applyUserTemplate(projectedToolPath, config.userPrepend, config.userPostpend)
                // One normal continuation request after tool results. If the model returns no
                // final output, completion validation below marks the generation as ERROR.
                beginProviderRound()
                requestDispatchMs = System.currentTimeMillis()
                usageRoundOpen = true
                collectProviderRound(apiToolPath, continuationConfig)
                settleUsageRound()
                finishCurrentThoughtTiming()
                // Always emit final state after tool round completes
                onStreamUpdate(modelMessage())
            }

            if (!currentCoroutineContext().isActive) {
                persistPendingProtocolOnCancellation()
                currentStatus = MessageStatus.STOPPED
            }

            if (!isRegenerate && isLatestPersist()) {
                val existingIds = conversations
                    .getMessagesForConversationSnapshot(conversationId)
                    .mapTo(mutableSetOf()) { it.id }
                val missingToolEntities = toolPath.mapNotNull { msg ->
                    if (
                        (msg.id.startsWith(Constants.TOOL_MSG_PREFIX) ||
                            msg.id.startsWith(Constants.RESULT_MSG_PREFIX)) &&
                        msg.id !in existingIds
                    ) {
                        MessageEntity(
                            id = msg.id, conversationId = conversationId, parentId = msg.parentId,
                            text = msg.text, thoughts = null, status = msg.status,
                            participant = msg.participant, timestamp = System.currentTimeMillis(),
                            toolCallJson = msg.segments?.let { Json.encodeToString(it) }
                                ?: msg.toolCall?.let { Json.encodeToString(listOf(
                                    MessageSegment(type = "tool", toolName = it.toolName, toolArgs = it.arguments, toolResult = it.result, signature = it.signature, toolCallId = it.toolCallId)
                                )) },
                        )
                    } else {
                        null
                    }
                }
                persistMessagesIfLatest(missingToolEntities)
            }

            if (
                currentStatus != MessageStatus.ERROR &&
                currentStatus != MessageStatus.STOPPED
            ) {
                val hasFinalResponse = hasFinalAssistantResponse(
                    text = totalText,
                    segments = segments,
                    generatedImages = generatedImages,
                    usedTools = usedTools,
                    answeredAfterLastTool = answeredAfterLastTool,
                )
                currentStatus = if (hasFinalResponse) MessageStatus.SUCCESS else MessageStatus.ERROR
                if (!hasFinalResponse) {
                    totalText = when (val error = lastGenerationError) {
                        null -> if (usedTools) {
                            context.getString(R.string.generation_empty_after_tool)
                        } else {
                            context.getString(R.string.generation_empty_response)
                        }
                        else -> formatGenerationDiagnostic(error)
                    }
                }
            }
            if (generationJob?.isCancelled == true && currentStatus != MessageStatus.ERROR) {
                currentStatus = MessageStatus.STOPPED
            }
            } finally {
                // Stable pending ids make this an idempotent abnormal-exit drain. It covers
                // cancellation after collect, app-side exceptions, and retry after a partial DB
                // failure without ever leaving N tool calls paired with fewer than N results.
                persistPendingProtocolOnCancellation()
            }
            } // else { // called buildApiPath when currentStatus == ERROR
        } catch (e: CancellationException) {
            currentStatus = MessageStatus.STOPPED
            throw e
        } catch (e: Exception) {
            val isCancelled = generationJob?.isCancelled == true
            currentStatus = if (isCancelled) MessageStatus.STOPPED else MessageStatus.ERROR
            if (!isCancelled) {
                val detail = "${e.javaClass.simpleName}: ${e.localizedMessage ?: "No exception message"}"
                totalText = detail.take(1_000)
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    settleUsageRound()
                    finishCurrentThoughtTiming()
                    val finalSegments = buildLiveSegments(
                        segments,
                        currentAnswerBuf,
                        currentThoughtBuf,
                        currentThoughtSignature,
                        currentThoughtDurationMs.takeIf { it > 0L }
                    )
                        ?: segments.toList().ifEmpty { null }
                    val segmentsJson = finalSegments?.let { Json.encodeToString(it) }
                    val effectiveParentId = parentId
                    val finishedAt = System.currentTimeMillis()
                    val finalUsage = usageAccumulator.snapshot()
                    val persisted = persistMessagesIfLatest(
                        listOf(
                            MessageEntity(
                                id = modelMessageId, conversationId = conversationId, parentId = effectiveParentId,
                                text = totalText, images = generatedImages.toList(),
                                thoughts = totalThoughts.ifBlank { null },
                                thoughtTitle = totalThoughtTitle, tokenCount = finalUsage.tokenCount,
                                promptTokens = finalUsage.promptTokens,
                                cachedPromptTokens = finalUsage.cachedPromptTokens,
                                cacheTelemetryAvailable = finalUsage.cacheTelemetryAvailable,
                                completionTokens = finalUsage.completionTokens,
                                ttftMs = ttftMs,
                                status = currentStatus, participant = Participant.MODEL, timestamp = startTime,
                                completedAt = finishedAt,
                                thoughtTimeMs = totalThoughtTimeMs, modelName = modelName,
                                toolCallJson = segmentsJson,
                                roundUsageJson = usageAccumulator.rounds()
                                    .takeIf { it.isNotEmpty() }
                                    ?.let { Json.encodeToString(it) },
                            ),
                        ),
                    )
                    if (persisted && totalText.isNotBlank()) {
                        onMessagePersisted?.invoke(modelMessageId, totalText)
                    }
                } catch (e: Exception) {
                    DebugLog.e("AgoraVM", "Failed to persist message to DB", e)
                }
                // Terminal UI cleanup. These callbacks are token-gated at the sink
                // (in ChatViewModel), so they automatically no-op when this generation
                // was stopped or superseded — only the still-current generation resets
                // the loading/streaming/generating-id UI state.
                onStreamClear()
                onLoadingChange(false)
                onGeneratingIdChange(null)
                mcpDeferredToolProvider.clear(capabilityRequestId)
                mcpToolProvider.clearRequest(capabilityRequestId)
                imageGenToolProvider.clearImages(capabilityRequestId)
                // Only the generate() that successfully started the FGS may stop it.
                if (fgsStarted) {
                    try {
                        AgoraForegroundService.stop(app)
                    } catch (e: Exception) {
                        DebugLog.w("AgoraVM", "FGS stop failed", e)
                    }
                }
                if (!AppForegroundTracker.isInForeground && currentStatus == MessageStatus.SUCCESS && totalText.isNotBlank()) {
                    runCatching {
                        AgoraForegroundService.showCompletionNotification(app, totalText)
                    }
                }
            }
        }
    }
}
