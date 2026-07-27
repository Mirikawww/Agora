package com.newoether.agora.viewmodel

import android.app.Application
import com.newoether.agora.util.DebugLog
import com.newoether.agora.api.GenerationError
import com.newoether.agora.api.LlmProvider
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.data.MemoryManager

import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.ChatMessage
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
import com.newoether.agora.tool.MemoryToolProvider
import com.newoether.agora.tool.McpToolProvider
import com.newoether.agora.tool.PersonalizationToolProvider
import com.newoether.agora.tool.ProviderBalanceToolProvider
import com.newoether.agora.tool.RagToolProvider
import com.newoether.agora.tool.ShellToolProvider
import com.newoether.agora.tool.SkillsToolProvider
import com.newoether.agora.tool.ToolProvider
import com.newoether.agora.tool.WebSearchToolProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    val alternateApiKeys: List<String> = emptyList()
)

data class GenerationContext(
    val conversationId: String? = null,
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
    val transcriptionBaseUrl: String? = null
)

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
    private val toolProviders: List<ToolProvider> = listOf(
        memoryToolProvider, webSearchToolProvider, ragToolProvider, imageGenToolProvider,
        personalizationToolProvider, providerBalanceToolProvider, askToolProvider,
        skillsToolProvider, githubConnectorToolProvider, shellToolProvider, mcpToolProvider,
    )

    fun buildImageGenTool(ctx: GenerationContext): List<ToolDefinition> =
        imageGenToolProvider.definitions(ctx)

    private val transcriptionManager = TranscriptionManager(providers, conversations, context)

    companion object {
        private val FILE_TOOL_NAMES = setOf("file_read", "file_write", "file_edit", "file_glob", "file_grep")
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
                if (provider.handles(name)) {
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
        ctx: GenerationContext
    ): Pair<List<ChatMessage>, ProviderConfig> {
        val dbMessages = conversations.getMessagesForConversationSnapshot(conversationId)
        val pathEntities = mutableListOf<MessageEntity>()
        var currId: String? = parentId
        while (currId != null) {
            val msg = dbMessages.find { it.id == currId } ?: break
            pathEntities.add(0, msg)
            currId = msg.parentId
        }
        // Inject tool call chains that are children of messages in the ancestor path.
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
            ChatMessage(id = it.id, parentId = it.parentId, text = combinedText, images = effectiveImages, thoughts = it.thoughts, thoughtTitle = it.thoughtTitle, tokenCount = it.tokenCount, status = it.status, participant = it.participant, timestamp = it.timestamp, completedAt = it.completedAt, thoughtTimeMs = it.thoughtTimeMs, segments = segs, toolCall = toolCall)
        }.filter { it.participant != Participant.ERROR }
            .let { path ->
                if (isRegenerate && replaceMessageId != null) {
                    val oldIdx = path.indexOfFirst { it.id == replaceMessageId }
                    if (oldIdx >= 0) path.take(oldIdx) else path
                } else path
            }

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
        val mcpTools = mcpToolProvider.refresh(ctx)
        val allTools = memoryTools + webSearchTool + ragTool + imageGenTool +
            personalizationTools + balanceTools + askTools + skillsTools + githubTools +
            shellTool + fileTool + mcpTools
        val providerConfig = ProviderConfig(
            apiKey = config.apiKey,
            modelId = config.modelId,
            systemPrompt = config.effectiveSystemPrompt,
            maxContextWindow = config.maxContextWindow,
            codeExecutionEnabled = config.codeExecutionEnabled,
            googleSearchEnabled = config.googleSearchEnabled,
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
        return Pair(currentPath, providerConfig)
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
        val (onStreamUpdate, onLoadingChange, onGeneratingIdChange, onStreamClear, isLatestPersist) = callbacks
        val provider = getProviderInstance(config.providerName)

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
        var totalTokenCount = 0
        var roundTokenCount = 0
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
            val (currentPath, rawProviderConfig) = buildApiPath(parentId, conversationId, isRegenerate, replaceMessageId, config, ctx)
            val providerConfig = if (transcriptionPerformed) rawProviderConfig.copy(includeImages = false) else rawProviderConfig

            var toolCallData: ToolCallData? = null
            var toolCallDataList: List<ToolCallData> = emptyList()
            var lastGenerationError: GenerationError? = null
            var usedTools = false
            var answeredAfterLastTool = false
            val roundToolSegments = mutableListOf<MessageSegment>()

            var lastEmitMs = 0L

            fun modelMessage() = ChatMessage(
                id = modelMessageId, parentId = parentId,
                text = totalText, thoughts = totalThoughts.ifBlank { null },
                thoughtTitle = totalThoughtTitle, tokenCount = totalTokenCount + roundTokenCount,
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
                        if (event.tokenCount > 0) roundTokenCount = event.tokenCount
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
                        val result = executeTool(event.name, args, ctx)
                        generatedImages.addAll(imageGenToolProvider.drainImages())
                        val clipped = result.take(Constants.MAX_TOOL_RESULT_LENGTH)
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
                        val tcds = normalizedCalls.map { (call, args, callId) ->
                            val result = executeTool(call.name, args, ctx)
                            generatedImages.addAll(imageGenToolProvider.drainImages())
                            val clipped = result.take(Constants.MAX_TOOL_RESULT_LENGTH)
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
                        toolCallData = tcds.firstOrNull()
                        toolCallDataList = tcds
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
            provider.generateResponse(apiPath, providerConfig).collect { event ->
                handleStreamEvent(event)
            }
            // Settle this request's usage before any tool round issues the next one.
            totalTokenCount += roundTokenCount
            roundTokenCount = 0
            finishCurrentThoughtTiming()
            // Always emit final state after collection completes
            if (generationJob?.isCancelled != true) {
                onStreamUpdate(modelMessage())
            }

            // Multi-tool loop
            var toolRound = 0
            toolPath = currentPath

            while (toolCallDataList.isNotEmpty() && currentStatus != MessageStatus.ERROR && currentCoroutineContext().isActive) {
                toolRound++
                val roundToolList = roundToolSegments.toList()
                roundToolSegments.clear()
                val thoughtSegs = segments.filter { it.type == "thought" }
                val txedSegments = if (thoughtSegs.isNotEmpty()) thoughtSegs + roundToolList else roundToolList
                val prevLastId = if (toolRound == 1) modelMessageId else toolPath.lastOrNull()?.id
                val toolMsgId = "${Constants.TOOL_MSG_PREFIX}${UUID.randomUUID()}"
                val toolMsgSegs = txedSegments.ifEmpty { null }
                val tcds = toolCallDataList
                val allSegmentsJson = Json.encodeToString(toolMsgSegs ?: tcds.map { tc ->
                    MessageSegment(type = "tool", toolName = tc.toolName, toolArgs = tc.arguments, toolResult = tc.result, signature = tc.signature, toolCallId = tc.toolCallId)
                })
                val resultMsgs = tcds.map { tcData ->
                    val rid = "${Constants.RESULT_MSG_PREFIX}${UUID.randomUUID()}"
                    val displayText = SearchResultFormatter.format(tcData.result, context)
                    rid to ChatMessage(
                        id = rid, parentId = toolMsgId,
                        text = displayText,
                        participant = Participant.USER, status = MessageStatus.SUCCESS,
                        toolCall = tcData
                    )
                }
                toolPath = toolPath.toMutableList().apply {
                    add(ChatMessage(
                        id = toolMsgId, parentId = prevLastId,
                        text = "", participant = Participant.MODEL,
                        status = MessageStatus.SUCCESS, toolCall = tcds.first(),
                        segments = toolMsgSegs
                    ))
                    for ((_, msg) in resultMsgs) add(msg)
                }
                conversations.upsertMessage(MessageEntity(
                    id = toolMsgId, conversationId = conversationId, parentId = prevLastId,
                    text = "", thoughts = null, status = MessageStatus.SUCCESS,
                    participant = Participant.MODEL, timestamp = System.currentTimeMillis(),
                    toolCallJson = allSegmentsJson
                ))
                for ((index, entry) in resultMsgs.withIndex()) {
                    val (rid, _) = entry
                    conversations.upsertMessage(MessageEntity(
                        id = rid, conversationId = conversationId, parentId = toolMsgId,
                        text = tcds[index].result, thoughts = null, status = MessageStatus.SUCCESS,
                        participant = Participant.USER, timestamp = System.currentTimeMillis(),
                        toolCallJson = Json.encodeToString(listOf(
                            MessageSegment(type = "tool", toolName = tcds[index].toolName, toolArgs = tcds[index].arguments, toolResult = tcds[index].result, signature = tcds[index].signature, toolCallId = tcds[index].toolCallId)
                        ))
                    ))
                }

                toolCallData = null
                toolCallDataList = emptyList()

                lastEmitMs = 0L
                // Keep generating UI active while we ask the model to continue from tool results.
                currentStatus = MessageStatus.SENDING
                onStreamUpdate(modelMessage())

                val projectedToolPath = projectAssistantImagesToLatestUserMessage(toolPath, providerConfig.includeImages)
                val apiToolPath = applyUserTemplate(projectedToolPath, config.userPrepend, config.userPostpend)
                // One normal continuation request after tool results. If the model returns no
                // final output, completion validation below marks the generation as ERROR.
                provider.generateResponse(apiToolPath, providerConfig).collect { event ->
                    handleStreamEvent(event)
                }
                totalTokenCount += roundTokenCount
                roundTokenCount = 0
                finishCurrentThoughtTiming()
                // Always emit final state after tool round completes
                onStreamUpdate(modelMessage())
            }

            if (!currentCoroutineContext().isActive) {
                currentStatus = MessageStatus.STOPPED
            }

            if (!isRegenerate && isLatestPersist()) for (msg in toolPath) {
                if (msg.id.startsWith(Constants.TOOL_MSG_PREFIX) || msg.id.startsWith(Constants.RESULT_MSG_PREFIX)) {
                    val exists = conversations.getMessagesForConversationSnapshot(conversationId).any { it.id == msg.id }
                    if (!exists) {
                        conversations.upsertMessage(MessageEntity(
                            id = msg.id, conversationId = conversationId, parentId = msg.parentId,
                            text = msg.text, thoughts = null, status = msg.status,
                            participant = msg.participant, timestamp = System.currentTimeMillis(),
                            toolCallJson = msg.segments?.let { Json.encodeToString(it) }
                                ?: msg.toolCall?.let { Json.encodeToString(listOf(
                                    MessageSegment(type = "tool", toolName = it.toolName, toolArgs = it.arguments, toolResult = it.result, signature = it.signature, toolCallId = it.toolCallId)
                                )) }
                        ))
                    }
                }
            }

            if (currentStatus != MessageStatus.ERROR) {
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
                    if (isLatestPersist()) {
                        val conversationExists = conversations.getConversation(conversationId) != null
                        if (conversationExists) {
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
                            conversations.upsertMessage(MessageEntity(
                                id = modelMessageId, conversationId = conversationId, parentId = effectiveParentId,
                                text = totalText, images = generatedImages.toList(),
                                thoughts = totalThoughts.ifBlank { null },
                                thoughtTitle = totalThoughtTitle, tokenCount = totalTokenCount + roundTokenCount,
                                status = currentStatus, participant = Participant.MODEL, timestamp = startTime,
                                completedAt = finishedAt,
                                thoughtTimeMs = totalThoughtTimeMs, modelName = modelName, toolCallJson = segmentsJson
                            ))
                            if (totalText.isNotBlank()) {
                                onMessagePersisted?.invoke(modelMessageId, totalText)
                            }
                        }
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
