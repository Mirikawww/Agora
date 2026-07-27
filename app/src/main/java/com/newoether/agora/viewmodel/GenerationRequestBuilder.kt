package com.newoether.agora.viewmodel



import android.content.Context

import com.newoether.agora.R

import com.newoether.agora.data.ConversationSettings

import com.newoether.agora.data.MemoryManager

import com.newoether.agora.data.PredefinedVariables

import com.newoether.agora.data.repository.ConversationRepository

import com.newoether.agora.data.repository.ModelsDevRepository

import com.newoether.agora.data.repository.SettingsRepository

import com.newoether.agora.model.ModelId

import com.newoether.agora.model.apiModelName

import com.newoether.agora.tool.NotionConnector
import com.newoether.agora.tool.TodoistConnector
import com.newoether.agora.util.Constants

import kotlinx.coroutines.flow.StateFlow



/**

 * Stateless builder for the LLM generation request. Extracted from ChatViewModel.

 * Reads configuration singletons only; holds NO mutable UI state.

 */

class GenerationRequestBuilder(

    private val settings: SettingsRepository,

    private val convRepo: ConversationRepository,

    private val memoryManager: MemoryManager,

    private val providerRegistry: ProviderRegistry,

    private val ragManager: RagManager,

    private val modelsDevRepository: ModelsDevRepository,

    private val appContext: Context,

    // currentActiveModel 是一个 StateFlow,buildGenerationPair / buildEffectiveSystemPrompt 用到它的 .value

    private val currentActiveModel: StateFlow<String>,

    // _pendingConversationSettings 也是 StateFlow,buildEffectiveConversationSettings 读它的 .value

    private val pendingConversationSettings: StateFlow<ConversationSettings?>,

    // resolveProviderKey 需要 emit snackbar

    private val onSnackbar: (String) -> Unit,
    private val forceWebSearch: () -> Boolean = { false },
    private val forceImageGen: () -> Boolean = { false },
    private val forceGithub: () -> Boolean = { false },
    private val forceTodoist: () -> Boolean = { false },
    private val forceNotion: () -> Boolean = { false },

) {

    data class ProviderKey(val providerName: String, val apiKey: String)



    /** Resolves the active provider+key for [modelId] and verifies configuration.

     *  Emits a snackbar and returns null when the provider is not configured. */

    internal fun resolveProviderKey(modelId: String): ProviderKey? {

        val providerName = providerRegistry.providerForModel(modelId)

        // Prefer the key that owns this model in the catalog; do not rotate across keys.
        val activeKey = settings.resolveApiKeyForModel(modelId, providerName) ?: ""

        if (!providerRegistry.isConfigured(providerName, activeKey)) {

            onSnackbar(appContext.getString(R.string.no_api_key_for_provider, providerName))

            return null

        }

        return ProviderKey(providerName, activeKey)

    }



    private fun resolveTranscriptionProviderName(): String =

        settings.imageTranscriptionModel.value?.let { providerRegistry.providerForModel(it) } ?: ""



    private fun resolveTranscriptionModelId(): String =

        settings.imageTranscriptionModel.value?.let { ModelId.parse(it).modelName } ?: ""



    private fun resolveTranscriptionApiKey(): String {

        val model = settings.imageTranscriptionModel.value ?: return ""

        val providerName = providerRegistry.providerForModel(model)

        if (providerName == Constants.PROVIDER_LOCAL) return ""

        return settings.resolveActiveKey(providerName) ?: ""

    }



    private fun resolveTranscriptionBaseUrl(): String? {

        val model = settings.imageTranscriptionModel.value ?: return null

        return providerRegistry.getEffectiveBaseUrl(providerRegistry.providerForModel(model))

    }



    // Image generation reuses the selected model's provider credentials (mirrors transcription).

    private fun resolveImageGenModelId(): String =

        settings.imageGenModel.value?.let { ModelId.parse(it).apiModelName } ?: ""



    private fun resolveImageGenApiKey(): String {

        val model = settings.imageGenModel.value ?: return ""

        val providerName = providerRegistry.providerForModel(model)

        if (providerName == Constants.PROVIDER_LOCAL) return ""

        return settings.resolveActiveKey(providerName) ?: ""

    }



    private fun resolveImageGenBaseUrl(): String {

        val model = settings.imageGenModel.value ?: return ""

        return providerRegistry.getEffectiveBaseUrl(providerRegistry.providerForModel(model)) ?: ""

    }



    fun buildEffectiveConversationSettings(conversationId: String): ConversationSettings {

        val overrides = settings.conversationSettings.value[conversationId]

            ?: pendingConversationSettings.value  // new chat: may not be saved to map yet

            ?: ConversationSettings()

        return ConversationSettings(

            contextWindow = overrides.contextWindow ?: settings.maxContextWindow.value,

            temperature = overrides.temperature ?: settings.defaultTemperature.value,

            maxTokens = overrides.maxTokens ?: settings.defaultMaxTokens.value,

            topP = overrides.topP ?: settings.defaultTopP.value,

            frequencyPenalty = overrides.frequencyPenalty ?: settings.defaultFrequencyPenalty.value,

            presencePenalty = overrides.presencePenalty ?: settings.defaultPresencePenalty.value,

            codeExecutionEnabled = overrides.codeExecutionEnabled ?: settings.codeExecutionEnabled.value,

            googleSearchEnabled = overrides.googleSearchEnabled ?: settings.googleSearchEnabled.value,

            thinkingEnabled = overrides.thinkingEnabled ?: settings.thinkingEnabled.value,

            thinkingLevel = overrides.thinkingLevel ?: settings.thinkingLevel.value,

            thinkingBudgetEnabled = overrides.thinkingBudgetEnabled ?: settings.thinkingBudgetEnabled.value,

            thinkingBudgetTokens = overrides.thinkingBudgetTokens ?: settings.thinkingBudgetTokens.value,

            fastEnabled = overrides.fastEnabled ?: settings.fastEnabled.value,

            webSearchEnabled = if (forceWebSearch()) true else (overrides.webSearchEnabled ?: settings.webSearchEnabled.value),

            shellEnabled = if (settings.shellEnabled.value) (overrides.shellEnabled ?: true) else false

        )

    }



    internal fun buildGenerationPair(

        providerName: String,

        modelId: String,

        activeKey: String,

        resolvedSystemPrompt: String?,

        resolvedUserPrepend: String?,

        resolvedUserPostpend: String?,

        effectiveSettings: ConversationSettings,

        currentId: String

    ): Pair<GenerationConfig, GenerationContext> {

        val parsedModel = ModelId.parse(modelId)

        val providerModelId = ModelId(providerName, parsedModel.apiModelName).prefixed

        val capabilities = modelsDevRepository.capabilitiesFor(

            providerName,

            parsedModel.apiModelName,

            providerFast = settings.modelFastSupport.value[providerModelId],

        )

        val config = GenerationConfig(

            providerName = providerName,

            modelId = parsedModel.modelName,

            apiKey = activeKey,
            alternateApiKeys = emptyList(), // no multi-key failover: wrong key = user/config issue

            effectiveSystemPrompt = run {
                var prompt = resolvedSystemPrompt.orEmpty()
                if (forceWebSearch()) {
                    val instr = "You MUST call the web_search tool to research before answering. Do not answer from memory alone."
                    prompt = if (prompt.isBlank()) instr else prompt + "\n\n" + instr
                }
                if (forceImageGen()) {
                    val instr = "You MUST call the generate_image tool to create the requested image(s). Do not only describe the image in text."
                    prompt = if (prompt.isBlank()) instr else prompt + "\n\n" + instr
                }
                prompt.ifBlank { null }
            },

            maxContextWindow = effectiveSettings.contextWindow ?: settings.maxContextWindow.value,

            codeExecutionEnabled = effectiveSettings.codeExecutionEnabled ?: settings.codeExecutionEnabled.value,

            googleSearchEnabled = effectiveSettings.googleSearchEnabled ?: settings.googleSearchEnabled.value,

            thinkingEnabled = (effectiveSettings.thinkingEnabled ?: settings.thinkingEnabled.value) && capabilities.reasoning,

            thinkingLevel = effectiveSettings.thinkingLevel ?: settings.thinkingLevel.value,

            thinkingBudgetEnabled = effectiveSettings.thinkingBudgetEnabled ?: settings.thinkingBudgetEnabled.value,

            thinkingBudgetTokens = effectiveSettings.thinkingBudgetTokens ?: settings.thinkingBudgetTokens.value,

            fastEnabled = (effectiveSettings.fastEnabled ?: settings.fastEnabled.value) && capabilities.fast,

            baseUrl = providerRegistry.getEffectiveBaseUrl(providerName),

            userPrepend = resolvedUserPrepend,

            userPostpend = resolvedUserPostpend,

            temperature = effectiveSettings.temperature,

            maxTokens = effectiveSettings.maxTokens,

            topP = effectiveSettings.topP,

            frequencyPenalty = effectiveSettings.frequencyPenalty,

            presencePenalty = effectiveSettings.presencePenalty

        )

        val genCtx = GenerationContext(

            conversationId = currentId,

            accessSavedMemories = settings.accessSavedMemories.value,

            accessActiveMemory = settings.accessActiveMemory.value,

            accessPastConversations = settings.accessPastConversations.value,

            modelSearchMethod = settings.modelSearchMethod.value,

            activeEmbeddingConfig = ragManager.activeEmbeddingModel.value,

            embeddingApiKey = ragManager.resolveEmbeddingApiKey() ?: "",

            ragThreshold = settings.ragThreshold.value,

            searchMatchLimit = settings.searchMatchLimit.value,

            searchContextWindow = settings.searchContextWindow.value,

            webSearchEnabled = if (forceWebSearch()) true else (effectiveSettings.webSearchEnabled ?: settings.webSearchEnabled.value),

            webSearchApiKeys = settings.webSearchApiKeys.value,

            webSearchProvider = settings.webSearchProvider.value,

            webSearchNumResults = settings.webSearchNumResults.value,

            webSearchBaseUrl = settings.webSearchBaseUrl.value,

            imageGenEnabled = (settings.imageGenEnabled.value || forceImageGen()) && settings.imageGenModel.value?.contains(":") == true,
            imageGenApiKey = resolveImageGenApiKey(),
            imageGenBaseUrl = resolveImageGenBaseUrl(),
            imageGenModel = resolveImageGenModelId(),
            imageGenSize = settings.imageGenSize.value,
            forceImageGen = forceImageGen(),
            skillsEnabled = settings.skillsEnabled.value,
            askToolEnabled = settings.askToolEnabled.value,
            personalizationToolsEnabled = settings.personalizationToolsEnabled.value,
            githubEnabled = (settings.githubConnectorEnabled.value || forceGithub()) && settings.githubToken.value.isNotBlank(),
            githubToken = settings.githubToken.value,
            todoistEnabled = TodoistConnector.isActive(
                enabled = settings.todoistConnectorEnabled.value || forceTodoist(),
                oauth = settings.todoistOAuth.value,
            ),
            todoistOAuth = settings.todoistOAuth.value,
            notionEnabled = NotionConnector.isActive(
                enabled = settings.notionConnectorEnabled.value || forceNotion(),
                oauth = settings.notionOAuth.value,
            ),
            notionOAuth = settings.notionOAuth.value,
            shellEnabled = effectiveSettings.shellEnabled ?: settings.shellEnabled.value,
            shellDevices = settings.shellDevices.value,
            // Built-in connectors inject synthetic MCP servers (OAuth). Keep the MCP tool
            // pipeline on whenever user MCP or any built-in connector is enabled.
            mcpEnabled = settings.mcpEnabled.value ||
                settings.todoistConnectorEnabled.value || forceTodoist() ||
                settings.notionConnectorEnabled.value || forceNotion(),
            mcpServers = buildList {
                val todoistOn = settings.todoistConnectorEnabled.value || forceTodoist()
                val notionOn = settings.notionConnectorEnabled.value || forceNotion()
                // Only strip a hand-added row when the matching connector is ON (its synthetic
                // row replaces it). With the connector off, stripping would leave the user with
                // no server at all for that service.
                addAll(
                    NotionConnector.withoutBuiltin(
                        TodoistConnector.withoutBuiltin(settings.mcpServers.value, todoistOn),
                        notionOn
                    )
                )
                if (todoistOn) add(TodoistConnector.serverConfig(settings.todoistOAuth.value))
                if (notionOn) add(NotionConnector.serverConfig(settings.notionOAuth.value))
            },
            sandboxEnabled = settings.sandboxEnabled.value,

            imageTranscriptionEnabled = settings.imageTranscriptionEnabledModels.value.contains(currentActiveModel.value),

            imageTranscriptionModel = settings.imageTranscriptionModel.value,

            imageTranscriptionBatchSize = settings.imageTranscriptionBatchSize.value,

            imageTranscriptionPrompt = settings.imageTranscriptionPrompt.value,

            transcriptionProviderName = resolveTranscriptionProviderName(),

            transcriptionModelId = resolveTranscriptionModelId(),

            transcriptionApiKey = resolveTranscriptionApiKey(),

            transcriptionBaseUrl = resolveTranscriptionBaseUrl()

        )

        return Pair(config, genCtx)

    }



    data class ResolvedPrompt(

        val systemPrompt: String?,

        val userPrepend: String?,

        val userPostpend: String?

    )



    internal suspend fun buildEffectiveSystemPrompt(currentId: String): ResolvedPrompt {

        val conversation = convRepo.getConversation(currentId)

        val targetPromptId = conversation?.systemPromptId ?: settings.activeSystemPromptId.value

        val entry = settings.systemPrompts.value.find { it.id == targetPromptId }

        val activeMemory = memoryManager.getActiveMemory()

        val includeActiveMemory = settings.accessActiveMemory.value

        val modelId = ModelId.parse(currentActiveModel.value).modelName



        val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

        val dateSdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)

        val now = java.util.Date()



        val runtimeValues = mapOf(

            PredefinedVariables.TIME to sdf.format(now),

            PredefinedVariables.DATE to dateSdf.format(now),

            PredefinedVariables.SENT_TIME to sdf.format(now),

            PredefinedVariables.SENT_DATE to dateSdf.format(now),

            PredefinedVariables.MODEL_ID to modelId,

            PredefinedVariables.ACTIVE_MEMORY to if (includeActiveMemory && activeMemory.isNotBlank()) activeMemory else ""

        )



        val profileBlock = buildUserProfileBlock()



        if (entry != null) {

            val systemItems = entry.resolvedSystemItems

            // Prepend/postpend: {sent_time}/{sent_date} stay as placeholders resolved per-message in applyUserTemplate

            val perMsgValues = runtimeValues.filterKeys { it !in PredefinedVariables.PER_MESSAGE_VARS }

            val compiled = PredefinedVariables.compile(systemItems, runtimeValues)

            val systemPrompt = mergeSystemPromptWithProfile(compiled, profileBlock)

            return ResolvedPrompt(

                systemPrompt = systemPrompt,

                userPrepend = PredefinedVariables.compile(entry.userPrependItems, perMsgValues, emptyMap()).ifBlank { null },

                userPostpend = PredefinedVariables.compile(entry.userPostpendItems, perMsgValues, emptyMap()).ifBlank { null }

            )

        }



        return ResolvedPrompt(

            systemPrompt = profileBlock.ifBlank { null },

            userPrepend = null,

            userPostpend = null

        )

    }



    /** Build a compact "User profile" section from personalization settings. Empty fields are skipped. */

    private fun buildUserProfileBlock(): String {

        val lines = mutableListOf<String>()

        fun add(label: String, value: String) {

            val v = value.trim()

            if (v.isNotEmpty()) lines += "- $label: $v"

        }

        add("Nickname", settings.userProfileNickname.value)

        add("Gender", settings.userProfileGender.value)

        add("Age", settings.userProfileAge.value)

        add("Height", settings.userProfileHeight.value)

        add("Occupation", settings.userProfileOccupation.value)

        add("Other", settings.userProfileOther.value)

        if (lines.isEmpty()) return ""

        return buildString {

            appendLine("User profile (use this to personalize replies; do not invent missing details):")

            lines.forEach { appendLine(it) }

        }.trimEnd()

    }



    private fun mergeSystemPromptWithProfile(base: String, profileBlock: String): String? {
        val b = base.trim()
        val p = profileBlock.trim()
        if (b.isEmpty() && p.isEmpty()) return null
        if (p.isEmpty()) return b.ifBlank { null }
        if (b.isEmpty()) return p
        return "$b\n\n$p"
    }
}
