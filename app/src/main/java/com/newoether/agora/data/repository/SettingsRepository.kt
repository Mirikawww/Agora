package com.newoether.agora.data.repository

import com.newoether.agora.api.openai.CustomOpenAiProvider
import com.newoether.agora.data.ApiKeyEntry
import com.newoether.agora.model.ModelId
import com.newoether.agora.data.BuiltInPrompts
import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.EmbeddingModelConfig
import com.newoether.agora.data.LocalChatModelConfig
import com.newoether.agora.data.McpServerConfig
import com.newoether.agora.data.PromptTemplateItem
import com.newoether.agora.data.ProviderBalanceConfig
import com.newoether.agora.data.SettingsManager
import com.newoether.agora.data.ShellDeviceConfig
import com.newoether.agora.data.SystemPromptEntry
import com.newoether.agora.model.ToolCallDisplayModes
import com.newoether.agora.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Repository wrapping DataStore-backed SettingsManager.
 *
 * Exposes every setting as a hot, eagerly-shared [StateFlow] (so the UI can
 * `collectAsState` and callers can read `.value` synchronously), plus the
 * setters and atomic batch mutations. This is the single shared owner of the
 * app settings surface; `ChatViewModel` and the settings pages both consume it
 * instead of re-exposing each setting individually.
 *
 * StateFlow initial values match the previous `ChatViewModel.stateIn` defaults
 * so observable behavior is unchanged.
 */
class SettingsRepository(
    private val settingsManager: SettingsManager,
    private val scope: CoroutineScope
) {
    private fun <T> hot(flow: kotlinx.coroutines.flow.Flow<T>, initial: T): StateFlow<T> =
        flow.stateIn(scope, SharingStarted.Eagerly, initial)

    // ── Read StateFlows (eagerly shared) ──────────────────────

    val selectedModel: StateFlow<String> = hot(settingsManager.selectedModel, Constants.EXAMPLE_MODEL_ID)
    val availableModels: StateFlow<Map<String, List<String>>> = hot(settingsManager.availableModels, emptyMap())
    val modelFastSupport: StateFlow<Map<String, Boolean>> = hot(settingsManager.modelFastSupport, emptyMap())
    val modelKeyNicknames: StateFlow<Map<String, List<String>>> = hot(settingsManager.modelKeyNicknames, emptyMap())
    val modelKeyIds: StateFlow<Map<String, String>> = hot(settingsManager.modelKeyIds, emptyMap())
    val enabledModels: StateFlow<Set<String>> = hot(settingsManager.enabledModels, emptySet())
    val disabledProviders: StateFlow<Set<String>> = hot(settingsManager.disabledProviders, emptySet())
    val modelAliases: StateFlow<Map<String, String>> = hot(settingsManager.modelAliases, emptyMap())
    val apiKeys: StateFlow<List<ApiKeyEntry>> = hot(settingsManager.apiKeys, emptyList())
    val activeApiKeyIds: StateFlow<Map<String, List<String>>> = hot(settingsManager.activeApiKeyIds, emptyMap())
    val systemPrompts: StateFlow<List<SystemPromptEntry>> = hot(settingsManager.systemPrompts, emptyList())
    val activeSystemPromptId: StateFlow<String?> = hot(settingsManager.activeSystemPromptId, null)
    val maxContextWindow: StateFlow<Int> = hot(settingsManager.maxContextWindow, 20)
    val visualizeContextRollout: StateFlow<Boolean> = hot(settingsManager.visualizeContextRollout, false)
    val codeExecutionEnabled: StateFlow<Boolean> = hot(settingsManager.codeExecutionEnabled, false)
    val googleSearchEnabled: StateFlow<Boolean> = hot(settingsManager.googleSearchEnabled, false)
    val thinkingEnabled: StateFlow<Boolean> = hot(settingsManager.thinkingEnabled, true)
    val thinkingLevel: StateFlow<String> = hot(settingsManager.thinkingLevel, "medium")
    val thinkingBudgetEnabled: StateFlow<Boolean> = hot(settingsManager.thinkingBudgetEnabled, false)
    val thinkingBudgetTokens: StateFlow<Int> = hot(settingsManager.thinkingBudgetTokens, 4096)
    val fastEnabled: StateFlow<Boolean> = hot(settingsManager.fastEnabled, false)
    val providerBaseUrls: StateFlow<Map<String, String>> = hot(settingsManager.providerBaseUrls, emptyMap())
    val providerProtocols: StateFlow<Map<String, String>> = hot(settingsManager.providerProtocols, emptyMap())
    val providerBalanceConfigs: StateFlow<Map<String, ProviderBalanceConfig>> = hot(settingsManager.providerBalanceConfigs, emptyMap())
    val titleGenerationEnabled: StateFlow<Boolean> = hot(settingsManager.titleGenerationEnabled, true)
    val titleGenerationModel: StateFlow<String?> = hot(settingsManager.titleGenerationModel, null)
    val titleGenerationPrompt: StateFlow<String> = hot(settingsManager.titleGenerationPrompt, BuiltInPrompts.TITLE_GENERATION_SYSTEM)
    val imageTranscriptionEnabledModels: StateFlow<Set<String>> = hot(settingsManager.imageTranscriptionEnabledModels, emptySet())
    val imageTranscriptionModel: StateFlow<String?> = hot(settingsManager.imageTranscriptionModel, null)
    val imageTranscriptionBatchSize: StateFlow<Int> = hot(settingsManager.imageTranscriptionBatchSize, 3)
    val imageTranscriptionPrompt: StateFlow<String> = hot(settingsManager.imageTranscriptionPrompt, BuiltInPrompts.IMAGE_TRANSCRIPTION_USER)
    val accessPastConversations: StateFlow<Boolean> = hot(settingsManager.accessPastConversations, true)
    val accessSavedMemories: StateFlow<Boolean> = hot(settingsManager.accessSavedMemories, true)
    val accessActiveMemory: StateFlow<Boolean> = hot(settingsManager.accessActiveMemory, true)
    val ragSearchEnabled: StateFlow<Boolean> = hot(settingsManager.ragSearchEnabled, false)
    val autoCacheEnabled: StateFlow<Boolean> = hot(settingsManager.autoCacheEnabled, true)
    val autoUpdateCheck: StateFlow<Boolean> = hot(settingsManager.autoUpdateCheck, true)
    val updateChannel: StateFlow<String> = hot(settingsManager.updateChannel, "stable")
    val lastUpdateCheckTime: StateFlow<Long> = hot(settingsManager.lastUpdateCheckTime, 0L)
    val modelSearchMethod: StateFlow<String> = hot(settingsManager.modelSearchMethod, "keyword")
    val manualSearchMethod: StateFlow<String> = hot(settingsManager.manualSearchMethod, "keyword")
    val embeddingModels: StateFlow<List<EmbeddingModelConfig>> = hot(settingsManager.embeddingModels, emptyList())
    val activeEmbeddingModelId: StateFlow<String> = hot(settingsManager.activeEmbeddingModelId, "")
    val appLanguage: StateFlow<String> = hot(settingsManager.appLanguage, "system")
    val webSearchEnabled: StateFlow<Boolean> = hot(settingsManager.webSearchEnabled, false)
    val webSearchProvider: StateFlow<String> = hot(settingsManager.webSearchProvider, "duckduckgo")
    val webSearchApiKeys: StateFlow<Map<String, String>> = hot(settingsManager.webSearchApiKeys, emptyMap())
    val webSearchNumResults: StateFlow<Int> = hot(settingsManager.webSearchNumResults, 5)
    val webSearchBaseUrl: StateFlow<String> = hot(settingsManager.webSearchBaseUrl, "")
    val imageGenEnabled: StateFlow<Boolean> = hot(settingsManager.imageGenEnabled, false)
    val imageGenModel: StateFlow<String?> = hot(settingsManager.imageGenModel, null)
    val imageGenSize: StateFlow<String> = hot(settingsManager.imageGenSize, "1024x1024")
    val showComposerExpandButton: StateFlow<Boolean> = hot(settingsManager.showComposerExpandButton, true)
    val welcomeMessages: StateFlow<String> = hot(settingsManager.welcomeMessages, "")
    val welcomeDisplayMode: StateFlow<String> = hot(settingsManager.welcomeDisplayMode, "random")
    val welcomeEnabled: StateFlow<Boolean> = hot(settingsManager.welcomeEnabled, true)
    val composerDraftsJson: StateFlow<String> = hot(settingsManager.composerDraftsJson, "{}")
    val messageQueuesJson: StateFlow<String> = hot(settingsManager.messageQueuesJson, "{}")
    val userProfileNickname: StateFlow<String> = hot(settingsManager.userProfileNickname, "")
    val userProfileGender: StateFlow<String> = hot(settingsManager.userProfileGender, "")
    val userProfileAge: StateFlow<String> = hot(settingsManager.userProfileAge, "")
    val userProfileHeight: StateFlow<String> = hot(settingsManager.userProfileHeight, "")
    val userProfileOccupation: StateFlow<String> = hot(settingsManager.userProfileOccupation, "")
    val userProfileOther: StateFlow<String> = hot(settingsManager.userProfileOther, "")
    val githubConnectorEnabled: StateFlow<Boolean> = hot(settingsManager.githubConnectorEnabled, false)
    val githubToken: StateFlow<String> = hot(settingsManager.githubToken, "")
    val todoistConnectorEnabled: StateFlow<Boolean> = hot(settingsManager.todoistConnectorEnabled, false)
    val todoistOAuth: StateFlow<com.newoether.agora.data.McpOAuthState?> = hot(settingsManager.todoistOAuth, null)
    val notionConnectorEnabled: StateFlow<Boolean> = hot(settingsManager.notionConnectorEnabled, false)
    val notionOAuth: StateFlow<com.newoether.agora.data.McpOAuthState?> = hot(settingsManager.notionOAuth, null)
    val skillsEnabled: StateFlow<Boolean> = hot(settingsManager.skillsEnabled, true)
    val askToolEnabled: StateFlow<Boolean> = hot(settingsManager.askToolEnabled, true)
    val personalizationToolsEnabled: StateFlow<Boolean> =
        hot(settingsManager.personalizationToolsEnabled, true)
    val skillsApiToken: StateFlow<String> = hot(settingsManager.skillsApiToken, "")
    val shellEnabled: StateFlow<Boolean> = hot(settingsManager.shellEnabled, false)
    val proxyEnabled: StateFlow<Boolean> = hot(settingsManager.proxyEnabled, false)
    val proxyType: StateFlow<String> = hot(settingsManager.proxyType, "http")
    val proxyHost: StateFlow<String> = hot(settingsManager.proxyHost, com.newoether.agora.data.SettingsManager.DEFAULT_PROXY_HOST)
    val proxyPort: StateFlow<String> = hot(settingsManager.proxyPort, com.newoether.agora.data.SettingsManager.DEFAULT_PROXY_PORT)
    val proxyUsername: StateFlow<String> = hot(settingsManager.proxyUsername, "")
    val proxyPassword: StateFlow<String> = hot(settingsManager.proxyPassword, "")
    val proxyBypass: StateFlow<String> = hot(settingsManager.proxyBypass, com.newoether.agora.data.SettingsManager.DEFAULT_PROXY_BYPASS)
    val shellConfirmEnabled: StateFlow<Boolean> = hot(settingsManager.shellConfirmEnabled, true)
    val shellDevices: StateFlow<List<ShellDeviceConfig>> = hot(settingsManager.shellDevices, emptyList())
    val mcpEnabled: StateFlow<Boolean> = hot(settingsManager.mcpEnabled, false)
    val mcpServers: StateFlow<List<McpServerConfig>> = hot(settingsManager.mcpServers, emptyList())
    val sandboxEnabled: StateFlow<Boolean> = hot(settingsManager.sandboxEnabled, false)
    val defaultTemperature: StateFlow<Float?> = hot(settingsManager.defaultTemperature, null)
    val defaultMaxTokens: StateFlow<Int?> = hot(settingsManager.defaultMaxTokens, null)
    val defaultTopP: StateFlow<Float?> = hot(settingsManager.defaultTopP, null)
    val defaultFrequencyPenalty: StateFlow<Float?> = hot(settingsManager.defaultFrequencyPenalty, null)
    val defaultPresencePenalty: StateFlow<Float?> = hot(settingsManager.defaultPresencePenalty, null)
    val conversationSettings: StateFlow<Map<String, ConversationSettings>> = hot(settingsManager.conversationSettings, emptyMap())
    val themeMode: StateFlow<String> = hot(settingsManager.themeMode, "FOLLOW_DEVICE")
    val colorScheme: StateFlow<String> = hot(settingsManager.colorScheme, "DEFAULT")
    val dynamicColor: StateFlow<Boolean> = hot(settingsManager.dynamicColor, true)
    val blurEffectsEnabled: StateFlow<Boolean> = hot(settingsManager.blurEffectsEnabled, true)
    val hapticsEnabled: StateFlow<Boolean> = hot(settingsManager.hapticsEnabled, true)
    val toolCallDisplayMode: StateFlow<String> = hot(settingsManager.toolCallDisplayMode, ToolCallDisplayModes.DEFAULT)
    val schemeStyle: StateFlow<String> = hot(settingsManager.schemeStyle, "TONAL_SPOT")
    val searchContextWindow: StateFlow<Int> = hot(settingsManager.searchContextWindow, 8)
    val searchMatchLimit: StateFlow<Int> = hot(settingsManager.searchMatchLimit, 10)
    val ragThreshold: StateFlow<Float> = hot(settingsManager.ragThreshold, 0.5f)
    val localChatModels: StateFlow<List<LocalChatModelConfig>> = hot(settingsManager.localChatModels, emptyList())
    val customProviders: StateFlow<List<CustomProviderConfig>> = hot(settingsManager.customProviders, emptyList())
    val lastModelsFetchFingerprint: StateFlow<String> = hot(settingsManager.lastModelsFetchFingerprint, "")
    // ── Auto Backup ───────────────────────────────────────────
    val autoBackupEnabled: StateFlow<Boolean> = hot(settingsManager.autoBackupEnabled, true)
    val autoBackupPeriodHours: StateFlow<Int> = hot(settingsManager.autoBackupPeriodHours, 24)
    val autoBackupCategories: StateFlow<String> = hot(settingsManager.autoBackupCategories, "conversations,memories,system_prompts,settings")
    val autoBackupDirectory: StateFlow<String> = hot(settingsManager.autoBackupDirectory, "Download/Agora/Backup")
    val autoDeleteEnabled: StateFlow<Boolean> = hot(settingsManager.autoDeleteEnabled, true)
    val autoDeletePeriodHours: StateFlow<Int> = hot(settingsManager.autoDeletePeriodHours, 168)
    val lastBackupTimestamp: StateFlow<Long> = hot(settingsManager.lastBackupTimestamp, 0L)

    // ── Write (fire-and-forget; read current state from own StateFlows) ──
    //
    // These setters launch on [scope] and read "current" list/map state from this
    // repository's own `.value`, so callers no longer pass it in. Absorbed from the
    // former `SettingsDelegate`; logic is byte-for-byte equivalent.

    // Model selection
    fun setSelectedModel(model: String) {
        scope.launch { settingsManager.saveSelectedModel(model) }
    }

    fun setEnabledModels(models: Set<String>) {
        scope.launch {
            settingsManager.saveEnabledModels(models)
            if (!models.contains(selectedModel.value)) {
                settingsManager.saveSelectedModel(models.firstOrNull() ?: "")
            }
        }
    }

    fun isProviderEnabled(provider: String): Boolean =
        provider == Constants.PROVIDER_LOCAL || provider !in disabledProviders.value

    fun setProviderEnabled(provider: String, enabled: Boolean) {
        if (provider == Constants.PROVIDER_LOCAL) return
        scope.launch {
            val next = disabledProviders.value.toMutableSet()
            if (enabled) next.remove(provider) else next.add(provider)
            settingsManager.saveDisabledProviders(next)
            // If the current default model belongs to a disabled provider, hop to another.
            if (!enabled) {
                val selected = selectedModel.value
                if (selected.isNotBlank() && ModelId.parse(selected).providerName == provider) {
                    val fallback = enabledModels.value.firstOrNull {
                        val p = ModelId.parse(it).providerName
                        p == Constants.PROVIDER_LOCAL || p !in next
                    } ?: ""
                    settingsManager.saveSelectedModel(fallback)
                }
            }
        }
    }

    fun updateModelAlias(model: String, alias: String) {
        scope.launch {
            val updated = modelAliases.value.toMutableMap()
            if (alias.isBlank()) updated.remove(model) else updated[model] = alias
            settingsManager.saveModelAliases(updated)
        }
    }


    /** Manual override for whether [model] supports "fast" tier (persisted in modelFastSupport). */
    fun setModelFastOverride(model: String, enabled: Boolean?) {
        scope.launch {
            val provider = ModelId.parse(model).providerName
            val current = modelFastSupport.value.toMutableMap()
            if (enabled == null) current.remove(model) else current[model] = enabled
            // saveModelFastSupport replaces whole provider slice; rebuild from current map for provider
            val forProvider = current.filterKeys { it.startsWith("$provider:") }
            settingsManager.saveModelFastSupport(provider, forProvider)
        }
    }

    // API keys
    /**
     * Refresh model-source (key nickname) badges for [provider] from the current active
     * keys + already-cached available models — no network fetch required.
     *
     * Call with the post-mutation [keysForProvider] / [activeIds] when StateFlows may still
     * hold pre-write values (DataStore edits are async).
     *
     * Full catalog refresh ([ProviderRegistry.fetchModelsForProvider]) still overwrites with
     * precise per-key model unions when the user refreshes models.
     */
    private suspend fun syncKeyNicknameBadges(
        provider: String,
        keysForProvider: List<ApiKeyEntry>? = null,
        activeIds: List<String>? = null,
    ) {
        val models = availableModels.value[provider].orEmpty()
        if (models.isEmpty()) {
            settingsManager.saveModelKeyNicknames(provider, emptyMap())
            return
        }
        val keys = keysForProvider ?: apiKeys.value.filter { it.provider == provider }
        val ids = activeIds ?: activeApiKeyIds.value[provider].orEmpty()
        val byId = keys.associateBy { it.id }
        val activeNames = ids.mapNotNull { id ->
            byId[id]?.name?.takeIf { it.isNotBlank() }
        }.distinct()
        val nickMap = if (activeNames.isEmpty()) {
            emptyMap()
        } else {
            models.associateWith { activeNames }
        }
        settingsManager.saveModelKeyNicknames(provider, nickMap)
    }

    fun addApiKey(name: String, key: String, provider: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        val duplicate = apiKeys.value.any {
            it.provider == provider && it.name.equals(trimmed, ignoreCase = true)
        }
        if (duplicate) return false
        scope.launch {
            val entry = ApiKeyEntry(name = trimmed, key = key, provider = provider)
            val newKeys = apiKeys.value + entry
            val newActive = activeApiKeyIds.value[provider].orEmpty() + entry.id
            settingsManager.saveApiKeys(newKeys)
            settingsManager.setActiveApiKeyIds(provider, newActive)
            syncKeyNicknameBadges(
                provider = provider,
                keysForProvider = newKeys.filter { it.provider == provider },
                activeIds = newActive,
            )
        }
        return true
    }

    /**
     * Store exactly one key for [provider]: update the existing entry in place if there
     * is one, otherwise add it — and drop any extra entries for the same provider.
     * Idempotent, so onboarding never accumulates duplicates.
     */
    fun upsertApiKey(name: String, key: String, provider: String) {
        scope.launch {
            val current = apiKeys.value
            val existing = current.firstOrNull { it.provider == provider }
            val entry = existing?.copy(name = name, key = key) ?: ApiKeyEntry(name = name, key = key, provider = provider)
            val newKeys = current.filter { it.provider != provider } + entry
            val newActive = listOf(entry.id)
            settingsManager.saveApiKeys(newKeys)
            settingsManager.setActiveApiKeyIds(provider, newActive)
            syncKeyNicknameBadges(
                provider = provider,
                keysForProvider = newKeys.filter { it.provider == provider },
                activeIds = newActive,
            )
        }
    }

    fun deleteApiKey(id: String) {
        scope.launch {
            val current = apiKeys.value
            val entry = current.find { it.id == id } ?: return@launch
            val newList = current.filter { it.id != id }
            val remainingEnabled = activeApiKeyIds.value[entry.provider].orEmpty().filter { it != id }
            settingsManager.setActiveApiKeyIds(entry.provider, remainingEnabled)
            settingsManager.saveApiKeys(newList)
            syncKeyNicknameBadges(
                provider = entry.provider,
                keysForProvider = newList.filter { it.provider == entry.provider },
                activeIds = remainingEnabled,
            )
            // Last key removed or no enabled keys left → drop stale catalog immediately.
            if (remainingEnabled.isEmpty()) {
                val stillHasKeys = newList.any { it.provider == entry.provider }
                if (!stillHasKeys || entry.provider != Constants.PROVIDER_OLLAMA) {
                    // No keys at all, or remaining keys are all disabled: clear catalog for
                    // non-keyless providers. Pure keyless (no keys ever) is handled elsewhere.
                    if (stillHasKeys || newList.none { it.provider == entry.provider }) {
                        val hasAnyKeysForProvider = newList.any { it.provider == entry.provider }
                        if (hasAnyKeysForProvider || entry.provider != Constants.PROVIDER_OLLAMA) {
                            // If provider still has disabled keys only, or zero keys after delete of last:
                            // clear when not Ollama keyless path.
                            if (entry.provider != Constants.PROVIDER_OLLAMA || hasAnyKeysForProvider) {
                                saveAvailableModels(entry.provider, emptyList())
                                saveModelKeyNicknames(entry.provider, emptyMap())
                                saveModelKeyIds(entry.provider, emptyMap())
                                saveModelFastSupport(entry.provider, emptyMap())
                                val allAvailable = getAvailableModels().values.flatten().toSet()
                                val newEnabled = enabledModels.value.intersect(allAvailable)
                                if (newEnabled != enabledModels.value) setEnabledModels(newEnabled)
                                val selected = selectedModel.value
                                if (selected.startsWith("${entry.provider}:") && selected !in allAvailable) {
                                    setSelectedModel(
                                        newEnabled.firstOrNull()
                                            ?: allAvailable.firstOrNull()
                                            ?: Constants.EXAMPLE_MODEL_ID
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun updateApiKey(id: String, name: String, key: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        val current = apiKeys.value.find { it.id == id } ?: return false
        val duplicate = apiKeys.value.any {
            it.id != id && it.provider == current.provider && it.name.equals(trimmed, ignoreCase = true)
        }
        if (duplicate) return false
        scope.launch {
            val newKeys = apiKeys.value.map {
                if (it.id == id) it.copy(name = trimmed, key = key) else it
            }
            settingsManager.saveApiKeys(newKeys)
            syncKeyNicknameBadges(
                provider = current.provider,
                keysForProvider = newKeys.filter { it.provider == current.provider },
                activeIds = activeApiKeyIds.value[current.provider].orEmpty(),
            )
        }
        return true
    }

    /** Enable or disable [id] among the multi-selected keys for [provider]. */
    fun setApiKeyEnabled(provider: String, id: String, enabled: Boolean) {
        scope.launch {
            val current = activeApiKeyIds.value[provider].orEmpty()
            val next = if (enabled) {
                if (id in current) current else current + id
            } else {
                current.filter { it != id }
            }
            settingsManager.setActiveApiKeyIds(provider, next)
            syncKeyNicknameBadges(
                provider = provider,
                keysForProvider = apiKeys.value.filter { it.provider == provider },
                activeIds = next,
            )
            // Immediately drop this provider's catalog when no enabled keys remain and
            // the provider is not keyless. Avoids stale model rows + stuck full sync.
            if (next.isEmpty()) {
                val hasStoredKeys = apiKeys.value.any { it.provider == provider }
                if (hasStoredKeys && provider != Constants.PROVIDER_OLLAMA) {
                    saveAvailableModels(provider, emptyList())
                    saveModelKeyNicknames(provider, emptyMap())
                    saveModelKeyIds(provider, emptyMap())
                    saveModelFastSupport(provider, emptyMap())
                    val allAvailable = getAvailableModels().values.flatten().toSet()
                    val newEnabled = enabledModels.value.intersect(allAvailable)
                    if (newEnabled != enabledModels.value) {
                        setEnabledModels(newEnabled)
                    }
                    val selected = selectedModel.value
                    if (selected.startsWith("$provider:") && selected !in allAvailable) {
                        setSelectedModel(
                            newEnabled.firstOrNull()
                                ?: allAvailable.firstOrNull()
                                ?: Constants.EXAMPLE_MODEL_ID
                        )
                    }
                }
            }
        }
    }

    // System prompts
    fun addSystemPrompt(
        title: String, systemItems: List<PromptTemplateItem>,
        userPrependItems: List<PromptTemplateItem>, userPostpendItems: List<PromptTemplateItem>
    ) {
        scope.launch {
            val newList = systemPrompts.value + SystemPromptEntry(title = title, systemItems = systemItems, userPrependItems = userPrependItems, userPostpendItems = userPostpendItems)
            settingsManager.saveSystemPrompts(newList)
            if (activeSystemPromptId.value == null) settingsManager.setActiveSystemPromptId(newList.last().id)
        }
    }

    fun deleteSystemPrompt(id: String) {
        scope.launch {
            val newList = systemPrompts.value.filter { it.id != id }
            settingsManager.saveSystemPrompts(newList)
            if (activeSystemPromptId.value == id) settingsManager.setActiveSystemPromptId(newList.firstOrNull()?.id)
        }
    }

    fun updateSystemPrompt(
        id: String, title: String, systemItems: List<PromptTemplateItem>,
        userPrependItems: List<PromptTemplateItem>, userPostpendItems: List<PromptTemplateItem>
    ) {
        scope.launch {
            settingsManager.saveSystemPrompts(systemPrompts.value.map { if (it.id == id) it.copy(title = title, content = "", systemItems = systemItems, userPrependItems = userPrependItems, userPostpendItems = userPostpendItems) else it })
        }
    }

    fun setActiveSystemPrompt(id: String) {
        scope.launch { settingsManager.setActiveSystemPromptId(id) }
    }

    // Custom provider CRUD (callbacks touch ChatViewModel's live provider map)
    fun addCustomProvider(name: String, baseUrl: String, onProviderAdd: (String, CustomOpenAiProvider) -> Unit) {
        scope.launch {
            settingsManager.saveProviderBaseUrl(name, baseUrl)
            settingsManager.saveCustomProviders(customProviders.value + CustomProviderConfig(name))
            onProviderAdd(name, CustomOpenAiProvider(name, baseUrl))
        }
    }

    fun renameCustomProvider(
        oldName: String, newName: String,
        onProviderRemove: (String) -> Unit,
        onProviderAdd: (String, CustomOpenAiProvider) -> Unit
    ) {
        val url = providerBaseUrls.value[oldName] ?: return
        scope.launch {
            onProviderRemove(oldName)
            val updated = customProviders.value.toMutableList()
            val idx = updated.indexOfFirst { it.name == oldName }
            if (idx >= 0) {
                updated[idx] = CustomProviderConfig(newName)
                settingsManager.saveCustomProviders(updated)
                settingsManager.saveProviderBaseUrl(oldName, "")
                settingsManager.saveProviderBaseUrl(newName, url)
                providerProtocols.value[oldName]?.let { protocol ->
                    settingsManager.saveProviderProtocol(newName, protocol)
                    settingsManager.saveProviderProtocol(oldName, "auto")
                }
                val models = availableModels.value.toMutableMap()
                models[newName] = models.remove(oldName) ?: emptyList()
                val movedFastSupport = modelFastSupport.value
                    .filterKeys { it.startsWith("$oldName:") }
                    .mapKeys { (modelId, _) -> modelId.replaceFirst("$oldName:", "$newName:") }
                settingsManager.saveAvailableModels(newName, models[newName] ?: emptyList())
                settingsManager.saveAvailableModels(oldName, emptyList())
                settingsManager.saveModelFastSupport(newName, movedFastSupport)
                val newEnabled = enabledModels.value.map { if (it.startsWith("$oldName:")) it.replace("$oldName:", "$newName:") else it }.toSet()
                settingsManager.saveEnabledModels(newEnabled)
                val newAliases = modelAliases.value.mapKeys { if (it.key.startsWith("$oldName:")) it.key.replace("$oldName:", "$newName:") else it.key }
                settingsManager.saveModelAliases(newAliases)
                val movedActive = activeApiKeyIds.value[oldName].orEmpty()
                settingsManager.setActiveApiKeyIds(oldName, emptyList())
                val newKeys = apiKeys.value.map { if (it.provider == oldName) it.copy(provider = newName) else it }
                settingsManager.saveApiKeys(newKeys)
                settingsManager.setActiveApiKeyIds(newName, movedActive)
                if (oldName in disabledProviders.value) {
                    settingsManager.saveDisabledProviders(disabledProviders.value - oldName + newName)
                }
                providerBalanceConfigs.value[oldName]?.let { balance ->
                    settingsManager.saveProviderBalanceConfig(newName, balance)
                    settingsManager.saveProviderBalanceConfig(oldName, ProviderBalanceConfig())
                }
            }
            onProviderAdd(newName, CustomOpenAiProvider(newName, url))
        }
    }

    fun deleteCustomProvider(name: String, onProviderRemove: (String) -> Unit) {
        scope.launch {
            settingsManager.saveCustomProviders(customProviders.value.filter { it.name != name })
            onProviderRemove(name)
            settingsManager.saveAvailableModels(name, emptyList())
            settingsManager.saveEnabledModels(enabledModels.value.filter { !it.startsWith("$name:") }.toSet())
            settingsManager.saveModelAliases(modelAliases.value.filterKeys { !it.startsWith("$name:") })
            settingsManager.saveProviderBaseUrl(name, "")
            settingsManager.saveProviderProtocol(name, "auto")
            settingsManager.saveApiKeys(apiKeys.value.filter { it.provider != name })
            settingsManager.setActiveApiKeyIds(name, emptyList())
            settingsManager.saveDisabledProviders(disabledProviders.value - name)
            settingsManager.saveProviderBalanceConfig(name, ProviderBalanceConfig())
        }
    }

    // Image transcription
    fun addImageTranscriptionModels(models: Set<String>) = scope.launch { settingsManager.saveImageTranscriptionEnabledModels(imageTranscriptionEnabledModels.value + models) }
    fun removeImageTranscriptionModel(model: String) = scope.launch { settingsManager.saveImageTranscriptionEnabledModels(imageTranscriptionEnabledModels.value - model) }

    // Shell devices
    fun removeShellDevice(deviceId: String) = scope.launch { settingsManager.saveShellDevices(shellDevices.value.filter { it.id != deviceId }) }

    fun setConversationSettings(convId: String, settings: ConversationSettings?) = scope.launch { settingsManager.saveConversationSettings(convId, settings) }

    // ── Simple setting toggles ────────────────────────────────
    fun setMaxContextWindow(window: Int) = scope.launch { settingsManager.saveMaxContextWindow(window) }
    fun setVisualizeContextRollout(enabled: Boolean) = scope.launch { settingsManager.saveVisualizeContextRollout(enabled) }
    fun setProviderBaseUrl(provider: String, url: String) = scope.launch { settingsManager.saveProviderBaseUrl(provider, url) }
    fun setProviderProtocol(provider: String, protocol: String) = scope.launch { settingsManager.saveProviderProtocol(provider, protocol) }
    fun setProviderBalanceConfig(provider: String, config: ProviderBalanceConfig) = scope.launch { settingsManager.saveProviderBalanceConfig(provider, config) }
    fun setTitleGenerationEnabled(enabled: Boolean) = scope.launch { settingsManager.saveTitleGenerationEnabled(enabled) }
    fun setTitleGenerationModel(model: String?) = scope.launch { settingsManager.saveTitleGenerationModel(model) }
    fun setTitleGenerationPrompt(prompt: String) = scope.launch { settingsManager.saveTitleGenerationPrompt(prompt) }
    fun setImageTranscriptionModel(model: String?) = scope.launch { settingsManager.saveImageTranscriptionModel(model) }
    fun setImageTranscriptionBatchSize(size: Int) = scope.launch { settingsManager.saveImageTranscriptionBatchSize(size) }
    fun setImageTranscriptionPrompt(prompt: String) = scope.launch { settingsManager.saveImageTranscriptionPrompt(prompt) }
    fun setAccessPastConversations(enabled: Boolean) = scope.launch { settingsManager.saveAccessPastConversations(enabled) }
    fun setAccessSavedMemories(enabled: Boolean) = scope.launch { settingsManager.saveAccessSavedMemories(enabled) }
    fun setAccessActiveMemory(enabled: Boolean) = scope.launch { settingsManager.saveAccessActiveMemory(enabled) }
    fun setRagSearchEnabled(enabled: Boolean) = scope.launch { settingsManager.saveRagSearchEnabled(enabled) }
    fun setAutoCacheEnabled(enabled: Boolean) = scope.launch { settingsManager.saveAutoCacheEnabled(enabled) }
    fun setAutoUpdateCheck(enabled: Boolean) = scope.launch { settingsManager.saveAutoUpdateCheck(enabled) }
    fun setUpdateChannel(channel: String) = scope.launch { settingsManager.saveUpdateChannel(channel) }
    fun setLastUpdateCheckTime(time: Long) = scope.launch { settingsManager.saveLastUpdateCheckTime(time) }
    fun setModelSearchMethod(method: String) = scope.launch { settingsManager.saveModelSearchMethod(method) }
    fun setManualSearchMethod(method: String) = scope.launch { settingsManager.saveManualSearchMethod(method) }
    fun setAppLanguage(language: String) = scope.launch { settingsManager.saveAppLanguage(language) }
    fun setWebSearchEnabled(enabled: Boolean) = scope.launch { settingsManager.saveWebSearchEnabled(enabled) }
    fun setWebSearchProvider(provider: String) = scope.launch { settingsManager.saveWebSearchProvider(provider) }
    fun setWebSearchApiKey(provider: String, apiKey: String) = scope.launch { settingsManager.saveWebSearchApiKey(provider, apiKey) }
    fun setWebSearchNumResults(n: Int) = scope.launch { settingsManager.saveWebSearchNumResults(n) }
    fun setWebSearchBaseUrl(url: String) = scope.launch { settingsManager.saveWebSearchBaseUrl(url) }
    fun setImageGenEnabled(enabled: Boolean) = scope.launch { settingsManager.saveImageGenEnabled(enabled) }
    fun setImageGenModel(model: String?) = scope.launch { settingsManager.saveImageGenModel(model) }
    fun setImageGenSize(size: String) = scope.launch { settingsManager.saveImageGenSize(size) }
    fun setShowComposerExpandButton(enabled: Boolean) = scope.launch { settingsManager.saveShowComposerExpandButton(enabled) }
    fun setWelcomeMessages(value: String) = scope.launch { settingsManager.saveWelcomeMessages(value) }
    fun setWelcomeDisplayMode(mode: String) = scope.launch { settingsManager.saveWelcomeDisplayMode(mode) }
    fun setWelcomeEnabled(enabled: Boolean) = scope.launch { settingsManager.saveWelcomeEnabled(enabled) }
    fun setComposerDraftsJson(value: String) = scope.launch { settingsManager.saveComposerDraftsJson(value) }
    fun setMessageQueuesJson(value: String) = scope.launch { settingsManager.saveMessageQueuesJson(value) }
    fun setUserProfileNickname(value: String) = scope.launch { settingsManager.saveUserProfileNickname(value) }
    fun setUserProfileGender(value: String) = scope.launch { settingsManager.saveUserProfileGender(value) }
    fun setUserProfileAge(value: String) = scope.launch { settingsManager.saveUserProfileAge(value) }
    fun setUserProfileHeight(value: String) = scope.launch { settingsManager.saveUserProfileHeight(value) }
    fun setUserProfileOccupation(value: String) = scope.launch { settingsManager.saveUserProfileOccupation(value) }
    fun setUserProfileOther(value: String) = scope.launch { settingsManager.saveUserProfileOther(value) }
    fun setGithubConnectorEnabled(enabled: Boolean) = scope.launch { settingsManager.saveGithubConnectorEnabled(enabled) }
    fun setGithubToken(token: String) = scope.launch { settingsManager.saveGithubToken(token) }
    fun setTodoistConnectorEnabled(enabled: Boolean) = scope.launch { settingsManager.saveTodoistConnectorEnabled(enabled) }
    fun setTodoistOAuth(oauth: com.newoether.agora.data.McpOAuthState?) = scope.launch { settingsManager.saveTodoistOAuth(oauth) }
    fun setNotionConnectorEnabled(enabled: Boolean) = scope.launch { settingsManager.saveNotionConnectorEnabled(enabled) }
    fun setNotionOAuth(oauth: com.newoether.agora.data.McpOAuthState?) = scope.launch { settingsManager.saveNotionOAuth(oauth) }
    fun setSkillsEnabled(enabled: Boolean) = scope.launch { settingsManager.saveSkillsEnabled(enabled) }
    fun setAskToolEnabled(enabled: Boolean) = scope.launch { settingsManager.saveAskToolEnabled(enabled) }
    fun setPersonalizationToolsEnabled(enabled: Boolean) =
        scope.launch { settingsManager.savePersonalizationToolsEnabled(enabled) }
    fun setSkillsApiToken(token: String) = scope.launch { settingsManager.saveSkillsApiToken(token) }
    fun setShellEnabled(enabled: Boolean) = scope.launch { settingsManager.saveShellEnabled(enabled) }
    fun setMcpEnabled(enabled: Boolean) = scope.launch { settingsManager.saveMcpEnabled(enabled) }
    fun setProxyEnabled(enabled: Boolean) = scope.launch { settingsManager.saveProxyEnabled(enabled) }
    fun setProxyType(type: String) = scope.launch { settingsManager.saveProxyType(type) }
    fun setProxyHost(host: String) = scope.launch { settingsManager.saveProxyHost(host) }
    fun setProxyPort(port: String) = scope.launch { settingsManager.saveProxyPort(port) }
    fun setProxyUsername(user: String) = scope.launch { settingsManager.saveProxyUsername(user) }
    fun setProxyPassword(pass: String) = scope.launch { settingsManager.saveProxyPassword(pass) }
    fun setProxyBypass(bypass: String) = scope.launch { settingsManager.saveProxyBypass(bypass) }
    fun setSandboxEnabled(enabled: Boolean) = scope.launch { settingsManager.saveSandboxEnabled(enabled) }
    fun setGoogleSearchEnabled(enabled: Boolean) = scope.launch { settingsManager.saveGoogleSearchEnabled(enabled) }
    fun setThinkingEnabled(enabled: Boolean) = scope.launch { settingsManager.saveThinkingEnabled(enabled) }
    fun setThinkingLevel(level: String) = scope.launch { settingsManager.saveThinkingLevel(level) }
    fun setThinkingBudgetEnabled(enabled: Boolean) = scope.launch { settingsManager.saveThinkingBudgetEnabled(enabled) }
    fun setThinkingBudgetTokens(tokens: Int) = scope.launch { settingsManager.saveThinkingBudgetTokens(tokens) }
    fun setFastEnabled(enabled: Boolean) = scope.launch { settingsManager.saveFastEnabled(enabled) }
    fun setDefaultTemperature(v: Float?) = scope.launch { settingsManager.saveDefaultTemperature(v) }
    fun setDefaultMaxTokens(v: Int?) = scope.launch { settingsManager.saveDefaultMaxTokens(v) }
    fun setDefaultTopP(v: Float?) = scope.launch { settingsManager.saveDefaultTopP(v) }
    fun setDefaultFrequencyPenalty(v: Float?) = scope.launch { settingsManager.saveDefaultFrequencyPenalty(v) }
    fun setDefaultPresencePenalty(v: Float?) = scope.launch { settingsManager.saveDefaultPresencePenalty(v) }
    fun setThemeMode(mode: String) = scope.launch { settingsManager.saveThemeMode(mode) }
    fun setColorScheme(scheme: String) = scope.launch { settingsManager.saveColorScheme(scheme) }
    fun setDynamicColor(enabled: Boolean) = scope.launch { settingsManager.saveDynamicColor(enabled) }
    fun setBlurEffectsEnabled(enabled: Boolean) = scope.launch { settingsManager.saveBlurEffectsEnabled(enabled) }
    fun setHapticsEnabled(enabled: Boolean) = scope.launch { settingsManager.saveHapticsEnabled(enabled) }
    fun setToolCallDisplayMode(mode: String) = scope.launch { settingsManager.saveToolCallDisplayMode(mode) }
    fun setSchemeStyle(style: String) = scope.launch { settingsManager.saveSchemeStyle(style) }
    fun setSearchMatchLimit(n: Int) = scope.launch { settingsManager.saveSearchMatchLimit(n) }
    fun setSearchContextWindow(n: Int) = scope.launch { settingsManager.saveSearchContextWindow(n) }
    fun setRagThreshold(threshold: Float) = scope.launch { settingsManager.saveRagThreshold(threshold) }

    fun setShellConfirmEnabled(enabled: Boolean) = scope.launch { settingsManager.saveShellConfirmEnabled(enabled) }
    fun addShellDevice(device: ShellDeviceConfig) = scope.launch { settingsManager.saveShellDevices(shellDevices.value + device) }
    fun updateShellDevice(device: ShellDeviceConfig) = scope.launch {
        settingsManager.saveShellDevices(shellDevices.value.map { if (it.id == device.id) device else it })
    }

    fun addMcpServer(server: McpServerConfig) = scope.launch {
        settingsManager.saveMcpServers(mcpServers.value + server)
    }

    fun addMcpServers(servers: List<McpServerConfig>) = scope.launch {
        if (servers.isNotEmpty()) settingsManager.saveMcpServers(mcpServers.value + servers)
    }

    fun updateMcpServer(server: McpServerConfig) = scope.launch {
        updateMcpServerNow(server)
    }

    /** Synchronous variant used by MCP metadata/OAuth reconciliation to avoid stale write races. */
    suspend fun updateMcpServerNow(server: McpServerConfig) {
        // Built-in connectors are not user MCP rows — persist their OAuth blobs separately.
        if (server.id == com.newoether.agora.tool.TodoistConnector.SERVER_ID) {
            settingsManager.saveTodoistOAuth(server.oauth)
            return
        }
        if (server.id == com.newoether.agora.tool.NotionConnector.SERVER_ID) {
            settingsManager.saveNotionOAuth(server.oauth)
            return
        }
        settingsManager.updateMcpServer(server)
    }

    fun removeMcpServer(serverId: String) = scope.launch {
        settingsManager.saveMcpServers(mcpServers.value.filter { it.id != serverId })
    }

    // ── Derived lookups ─────────────────────────────────────────
    /** Cleartext secrets for every enabled key of [provider], in enable-list order. */
    fun resolveActiveKeys(provider: String): List<String> {
        val ids = activeApiKeyIds.value[provider].orEmpty()
        if (ids.isEmpty()) return emptyList()
        val byId = apiKeys.value.associateBy { it.id }
        return ids.mapNotNull { byId[it]?.key }
    }

    /** First enabled cleartext API key for [provider], or `null`. */
    fun resolveActiveKey(provider: String): String? = resolveActiveKeys(provider).firstOrNull()

    /**
     * API key that owns [modelIdWithPrefix] ("Provider:model").
     * Prefer the key that listed this model in catalog fetch; never rotates across keys.
     */
    fun resolveApiKeyForModel(modelIdWithPrefix: String, provider: String): String? {
        val keyId = modelKeyIds.value[modelIdWithPrefix]
        if (!keyId.isNullOrBlank()) {
            val enabled = activeApiKeyIds.value[provider].orEmpty()
            if (keyId in enabled) {
                apiKeys.value.find { it.id == keyId && it.provider == provider }?.key
                    ?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return resolveActiveKey(provider)
    }

    suspend fun awaitApiKeyForModel(modelIdWithPrefix: String, provider: String): String? {
        val keyIdsMap = settingsManager.modelKeyIds.first()
        val keyId = keyIdsMap[modelIdWithPrefix]
        if (!keyId.isNullOrBlank()) {
            val enabled = settingsManager.activeApiKeyIds.first()[provider].orEmpty()
            if (keyId in enabled) {
                val keys = settingsManager.apiKeys.first()
                keys.find { it.id == keyId && it.provider == provider }?.key
                    ?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return awaitActiveKey(provider)
    }

    /**
     * Like [resolveActiveKeys] but awaits on-disk DataStore values instead of
     * reading the eagerly-shared `.value`, which may still be the empty default
     * during the startup window before DataStore loads.
     */
    suspend fun awaitActiveKeys(provider: String): List<String> {
        val activeIds = settingsManager.activeApiKeyIds.first()[provider].orEmpty()
        if (activeIds.isEmpty()) return emptyList()
        val byId = settingsManager.apiKeys.first().associateBy { it.id }
        return activeIds.mapNotNull { byId[it]?.key }
    }

    /**
     * Like [resolveActiveKey] but awaits the on-disk DataStore values instead of
     * reading the eagerly-shared `.value`, which may still be the empty default
     * during the startup window before DataStore loads. Use this on the request-
     * build path: reading `.value` there races the load and yields a blank key →
     * an empty `Authorization` header → intermittent 401s on providers that are
     * considered configured by base-URL alone (custom / OpenAI-compatible / Ollama).
     */
    suspend fun awaitActiveKey(provider: String): String? = awaitActiveKeys(provider).firstOrNull()

    // ── Suspending DataStore access ───────────────────────────
    //
    // The StateFlows above are eagerly-shared with a default initial value, so at app
    // startup `.value` may briefly be the default before DataStore loads. These suspend
    // accessors read/write DataStore directly (awaiting the on-disk value, preserving
    // write ordering) for callers that need the persisted value immediately or ordered,
    // read-after-write semantics. They keep [SettingsManager] encapsulated as an internal
    // detail of this repository — the single owner of the settings surface.

    suspend fun getAutoUpdateCheck(): Boolean = settingsManager.autoUpdateCheck.first()
    suspend fun getLastUpdateCheckTime(): Long = settingsManager.lastUpdateCheckTime.first()
    suspend fun getEmbeddingModels(): List<EmbeddingModelConfig> = settingsManager.embeddingModels.first()
    suspend fun getActiveEmbeddingModelId(): String = settingsManager.activeEmbeddingModelId.first()
    suspend fun getModelAliases(): Map<String, String> = settingsManager.modelAliases.first()
    suspend fun getProviderBaseUrls(): Map<String, String> = settingsManager.providerBaseUrls.first()
    suspend fun getAvailableModels(): Map<String, List<String>> = settingsManager.availableModels.first()
    suspend fun getSystemPrompts(): List<SystemPromptEntry> = settingsManager.systemPrompts.first()

    suspend fun saveAvailableModels(provider: String, models: List<String>) = settingsManager.saveAvailableModels(provider, models)
    suspend fun saveModelKeyNicknames(provider: String, nicknames: Map<String, List<String>>) =
        settingsManager.saveModelKeyNicknames(provider, nicknames)

    suspend fun saveModelKeyIds(provider: String, keyIds: Map<String, String>) =
        settingsManager.saveModelKeyIds(provider, keyIds)
    suspend fun saveModelFastSupport(provider: String, support: Map<String, Boolean>) =
        settingsManager.saveModelFastSupport(provider, support)
    suspend fun saveModelAliases(aliases: Map<String, String>) = settingsManager.saveModelAliases(aliases)
    suspend fun saveLastUpdateCheckTime(time: Long) = settingsManager.saveLastUpdateCheckTime(time)
    suspend fun saveLastModelsFetchFingerprint(fingerprint: String) = settingsManager.saveLastModelsFetchFingerprint(fingerprint)
    suspend fun saveLocalChatModels(models: List<LocalChatModelConfig>) = settingsManager.saveLocalChatModels(models)
    suspend fun saveEmbeddingModels(models: List<EmbeddingModelConfig>) = settingsManager.saveEmbeddingModels(models)
    suspend fun setActiveEmbeddingModelId(id: String) = settingsManager.setActiveEmbeddingModelId(id)
    suspend fun saveAutoBackupEnabled(enabled: Boolean) = settingsManager.saveAutoBackupEnabled(enabled)
    suspend fun saveAutoBackupPeriodHours(hours: Int) = settingsManager.saveAutoBackupPeriodHours(hours)
    suspend fun saveAutoBackupCategories(categories: String) = settingsManager.saveAutoBackupCategories(categories)
    suspend fun saveAutoBackupDirectory(path: String) = settingsManager.saveAutoBackupDirectory(path)
    suspend fun saveAutoDeleteEnabled(enabled: Boolean) = settingsManager.saveAutoDeleteEnabled(enabled)
    suspend fun saveAutoDeletePeriodHours(hours: Int) = settingsManager.saveAutoDeletePeriodHours(hours)
}
