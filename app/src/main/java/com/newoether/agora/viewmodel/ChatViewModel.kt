package com.newoether.agora.viewmodel




import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.newoether.agora.R
import com.newoether.agora.api.*
import com.newoether.agora.api.LlamaEngine
import com.newoether.agora.api.anthropic.*
import com.newoether.agora.api.gemini.*
import com.newoether.agora.api.local.*
import com.newoether.agora.api.ollama.*
import com.newoether.agora.api.openai.*
import com.newoether.agora.data.AutoBackupManager
import com.newoether.agora.data.BuiltInPrompts
import com.newoether.agora.data.ClaudeChatImporter
import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.data.DataExporter
import com.newoether.agora.data.DataImporter
import com.newoether.agora.data.EmbeddingModelConfig
import com.newoether.agora.data.LocalChatModelConfig
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.PredefinedVariables




import com.newoether.agora.data.ShellDeviceConfig




import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.GenerationRoundUsage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.ModelId
import com.newoether.agora.model.apiModelName
import com.newoether.agora.model.Participant
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.model.ToolCallData
import com.newoether.agora.sandbox.SandboxManager
import com.newoether.agora.sandbox.SandboxManagerFactory
import com.newoether.agora.service.AgoraForegroundService
import com.newoether.agora.service.AutoBackupWorker
import com.newoether.agora.ui.settings.ImportStrategy
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import com.newoether.agora.util.PdfPageRenderer
import com.newoether.agora.util.SearchResultFormatter
import com.newoether.agora.util.SnackbarEvent
import com.newoether.agora.util.SshClient
import com.newoether.agora.util.UpdateChecker
import com.newoether.agora.util.UpdateInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID




/** Per-provider result of one model-sync pass. */
private enum class SyncOutcome { SUCCESS, FAILED, SKIPPED }

class ChatViewModel(
    application: Application,
    // [chatDao] and [settingsManager] are retained ONLY to pass to ImportExportManager,
    // which threads them into DataExporter/DataImporter (bulk data-layer utilities that
    // genuinely need raw DAO/DataStore). All other managers use repositories uniformly.
    private val chatDao: com.newoether.agora.data.local.ChatDao,
    private val settingsManager: com.newoether.agora.data.SettingsManager,
    val memoryManager: MemoryManager,
    private val appContext: Context,
    private val sandboxFactory: SandboxManagerFactory? = null,
    // All injected via AppContainer/ChatViewModelFactory — the single construction site.
    val autoBackupManager: AutoBackupManager,
    conversationRepository: ConversationRepository,
    settingsRepository: SettingsRepository
) : AndroidViewModel(application) {




    companion object {
        /** Overlay fade duration for conversation-switch transitions. */
        private const val SWITCH_OVERLAY_FADE_MS = 200L
        /** Auto-delete period tiers in hours: 7 days, 30 days, 365 days. */
        private val AUTO_DELETE_TIERS_HOURS = listOf(168, 720, 8760)
    }




    private val _forceWebSearch = kotlinx.coroutines.flow.MutableStateFlow(false)
    val forceWebSearch: kotlinx.coroutines.flow.StateFlow<Boolean> = _forceWebSearch
    fun setForceWebSearch(enabled: Boolean) { _forceWebSearch.value = enabled }

    private val _forceImageGen = kotlinx.coroutines.flow.MutableStateFlow(false)
    val forceImageGen: kotlinx.coroutines.flow.StateFlow<Boolean> = _forceImageGen
    fun setForceImageGen(enabled: Boolean) { _forceImageGen.value = enabled }

    private val _forceGithub = kotlinx.coroutines.flow.MutableStateFlow(false)
    val forceGithub: kotlinx.coroutines.flow.StateFlow<Boolean> = _forceGithub
    fun setForceGithub(enabled: Boolean) { _forceGithub.value = enabled }

    private val _forceTodoist = kotlinx.coroutines.flow.MutableStateFlow(false)
    val forceTodoist: kotlinx.coroutines.flow.StateFlow<Boolean> = _forceTodoist
    fun setForceTodoist(enabled: Boolean) { _forceTodoist.value = enabled }

    private val _forceNotion = kotlinx.coroutines.flow.MutableStateFlow(false)
    val forceNotion: kotlinx.coroutines.flow.StateFlow<Boolean> = _forceNotion
    fun setForceNotion(enabled: Boolean) { _forceNotion.value = enabled }

    /** Composer-based edit of an already-sent user message (queue-edit style). */
    private val _editingSentMessageId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val editingSentMessageId: kotlinx.coroutines.flow.StateFlow<String?> = _editingSentMessageId
    fun beginEditSentMessage(messageId: String): String? {
        val msg = _allMessages.value.find { it.id == messageId } ?: return null
        if (msg.participant != com.newoether.agora.model.Participant.USER) return null
        clearQueueEdit()
        _editingSentMessageId.value = messageId
        return msg.text
    }
    fun clearSentMessageEdit() { _editingSentMessageId.value = null }




    val settings: SettingsRepository = settingsRepository
    val skillsManager: com.newoether.agora.data.SkillsManager
        get() = generationManager.skillsManagerPublic
    private val modelsDevRepository = com.newoether.agora.data.repository.ModelsDevRepository(appContext)
    val modelsDevCapabilities = modelsDevRepository.capabilities




    fun modelCapabilitiesFor(modelId: String) = modelsDevRepository.capabilitiesFor(
        modelId = modelId,
        providerFast = settings.modelFastSupport.value[modelId],
    )




    /**
     * Conversation/message persistence behind the repository layer. CRUD, cascade-delete,
     * branch-selection and stuck-message logic live in [ConversationRepository]; managers
     * receive the repository (not raw DAO) for a uniform boundary.
     */
    private val convRepo: ConversationRepository = conversationRepository




    private val localProvider = LocalProvider(appContext, settings)




    /** Embedding subsystem: model CRUD + RAG cache + single-message indexing + key resolution. */
    val ragManager = RagManager(
        conversations = convRepo,
        settings = settings,
        localProvider = localProvider,
        appContext = appContext,
        scope = viewModelScope,
    ) { _snackbarMessage.emit(it) }




    /**
     * Data export/import orchestration (native backup + Claude + GPT formats).
     * [chatDao] and [settingsManager] are passed through to [DataExporter]/[DataImporter]
     * which need raw DAO/DataStore for bulk cross-table operations.
     */
    val importExport = ImportExportManager(
        app = getApplication(),
        conversations = convRepo,
        chatDao = chatDao,
        settingsManager = settingsManager,
        memoryManager = memoryManager,
        scope = viewModelScope,
        emitSnackbar = { _snackbarMessage.emit(it) },
        onDataChanged = { refreshDataCounts() },
    )




    /** Local (on-device) chat-model configuration CRUD. */
    val modelManager = ModelManager(settings, viewModelScope)




    /** Built-in + custom provider instances, resolution, and model discovery (see [ProviderRegistry]). */
    private val providerRegistry = ProviderRegistry(settings, localProvider, viewModelScope)




    /**
     * Startup jobs deferred until all StateFlow/property backing fields are
     * initialized — avoids the constructor this-escape where a Dispatchers.IO
     * coroutine accesses a field whose JVM backing field is still null.
     */
    /** Build the proxy config from settings and push it into the shared HttpClient. */
    private fun applyProxy() {
        val host = settings.proxyHost.value.trim()
        val cfg = if (settings.proxyEnabled.value && host.isNotEmpty()) {
            com.newoether.agora.api.HttpClient.ProxyConfig(
                type = if (settings.proxyType.value.equals("socks5", ignoreCase = true))
                    com.newoether.agora.api.HttpClient.ProxyType.SOCKS
                else com.newoether.agora.api.HttpClient.ProxyType.HTTP,
                host = host,
                port = settings.proxyPort.value.trim().toIntOrNull() ?: 0,
                username = settings.proxyUsername.value,
                password = settings.proxyPassword.value,
                bypass = settings.proxyBypass.value.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }
            )
        } else null
        com.newoether.agora.api.HttpClient.setProxy(cfg)
    }




    private fun startInitJobs() {
            loadPersistedQueuesAndDrafts()
            // Apply the network proxy at startup and whenever its settings change.
        viewModelScope.launch {
            val proxyFlows = listOf(
                settings.proxyEnabled.map { it.toString() },
                settings.proxyType, settings.proxyHost, settings.proxyPort,
                settings.proxyUsername, settings.proxyPassword, settings.proxyBypass
            )
            kotlinx.coroutines.flow.combine(proxyFlows) { it }.collect { applyProxy() }
        }
        // Auto-check for updates on launch (no daily throttle). Goes through
        // checkForUpdates() so it honours the selected channel — calling
        // UpdateChecker.check() directly pinned the launch check to stable, so a
        // user on the CI channel silently never got an automatic check at all.
        // A failure stays quiet here (no dialog); the About page reports the reason.
        viewModelScope.launch(Dispatchers.IO) {
            if (settings.getAutoUpdateCheck()) {
                val result = checkForUpdates()
                if (result is com.newoether.agora.util.UpdateCheckResult.Available) {
                    _updateDialogData.value = result.info
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            modelsDevRepository.loadAndRefresh()
        }
        viewModelScope.launch(Dispatchers.IO) {
            val models = settings.getEmbeddingModels()
            val activeId = settings.getActiveEmbeddingModelId()
            val active = models.find { it.id == activeId } ?: return@launch
            val total = convRepo.getAllMessagesForIndexing().count { it.text.isNotBlank() }
            val cached = convRepo.getEmbeddingCountByModel(active.id)
            val notCached = (total - cached).coerceAtLeast(0)
            if (notCached > 0 && !ragManager.cachingProgress.value.containsKey(active.id)) {
                _snackbarMessage.emit(SnackbarEvent(
                    getApplication<Application>().getString(R.string.messages_not_cached, notCached, total),
                    getApplication<Application>().getString(R.string.cache_now)
                ) { cacheMessagesForModel(active.id) })
            }
        }
        // Clean up orphaned embeddings (messages that no longer exist)
        viewModelScope.launch(Dispatchers.IO) {
            convRepo.deleteOrphanedEmbeddings()
        }
        // Sweep orphaned PDF render files (pdf_* / pdf_preview_*) left in filesDir by a
        // process death while the page-select dialog was open. At startup nothing is
        // rendering and no dialog is open, so any pdf_*.jpg not referenced by a stored
        // message's images is junk and gets deleted.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val referenced = convRepo.getAllMessagesList()
                    .asSequence()
                    .flatMap { it.images.asSequence() }
                    .map { it.removePrefix("file://") }
                    .toHashSet()
                getApplication<Application>().filesDir.listFiles { f ->
                    f.isFile && f.name.startsWith("pdf_") && f.name.endsWith(".jpg")
                }?.forEach { f ->
                    if (f.absolutePath !in referenced) runCatching { f.delete() }
                }
            } catch (e: Exception) { DebugLog.d("ChatViewModel", "PDF thumbnail cleanup error", e) }
        }
        // ── Auto Backup ──────────────────────────────────────────
        try { AutoBackupWorker.schedule(getApplication()) } catch (_: Exception) {}
        viewModelScope.launch(Dispatchers.IO) {
            try { autoBackupManager.checkAndBackup() } catch (e: Exception) { DebugLog.e("ChatViewModel", "Auto backup check failed", e) }
        }
        // Sync local chat models into available models
        viewModelScope.launch {
            var lastLocalIds: List<String>? = null
            var lastAliases: Map<String, String>? = null
            settings.localChatModels.collect { models ->
                val localIds = models.map { "Local:${it.modelId}" }
                val currentAliases = settings.getModelAliases()
                val aliases = currentAliases.toMutableMap()
                models.forEach { aliases["Local:${it.modelId}"] = it.alias }
                if (localIds != lastLocalIds) {
                    settings.saveAvailableModels(Constants.PROVIDER_LOCAL, localIds)
                    lastLocalIds = localIds
                }
                if (aliases != lastAliases) {
                    settings.saveModelAliases(aliases)
                    lastAliases = aliases
                }
            }
        }
        // Keep the provider map and cached model lists consistent with settings.
        providerRegistry.launchSyncJobs()
    }




    // Generation lifecycle (IO scope, current job, send gate, race-free stop/persist
    // ownership tokens) lives in [GenerationSession]; declared below once the
    // generation StateFlows it shares are initialized.




    private val generationManagerDelegate = lazy {
        GenerationManager(
            app = application,
            conversations = convRepo,
            memoryManager = memoryManager,
            providers = providerRegistry.all,
            context = appContext,
            settings = settings,
            sandboxFactory = sandboxFactory
        ).also { gm ->
            gm.onMessagePersisted = { messageId, text ->
                if (settings.autoCacheEnabled.value && (settings.modelSearchMethod.value == Constants.SEARCH_METHOD_RAG || settings.manualSearchMethod.value == Constants.SEARCH_METHOD_RAG)) {
                    indexMessageForRag(messageId, text)
                }
            }
            gm.onConfirmShellCommand = { server, summary -> shellConfirmation.confirm(server, summary) }
            gm.onConfirmMcpTool = { server, summary -> shellConfirmation.confirm(server, summary, enabled = true) }
            gm.onProbeProviderBalance = { provider -> providerRegistry.fetchBalances(provider) }
            gm.onAskUser = { question, header, mode, options -> askController.ask(question, header, mode, options) }
        }
    }
    private val generationManager by generationManagerDelegate




    val sandboxManager: SandboxManager? by lazy {
        sandboxFactory?.create()
    }
    val isSandboxFlavor: Boolean = sandboxFactory?.isAvailable() == true




    override fun onCleared() {
        super.onCleared()
        sandboxManager?.close()
        localProvider.close()
        session.cancelScope()
        autoBackupManager.destroy()
        if (generationManagerDelegate.isInitialized()) generationManager.close()
    }




    fun getProviderInstance(name: String): LlmProvider = providerRegistry.getInstance(name)

    /** Runs the provider's balance probe once per stored key; empty when not runnable. */
    suspend fun fetchProviderBalances(name: String): List<ProviderKeyBalance> =
        providerRegistry.fetchBalances(name)




    suspend fun testMcpServer(server: com.newoether.agora.data.McpServerConfig) =
        generationManager.testMcpServer(server)




    val mcpStatuses get() = generationManager.mcpStatuses




    fun startMcpAuthorization(server: com.newoether.agora.data.McpServerConfig) =
        generationManager.startMcpAuthorization(server)




    fun cancelMcpAuthorization(serverId: String) = generationManager.cancelMcpAuthorization(serverId)




    fun clearMcpAuthorization(server: com.newoether.agora.data.McpServerConfig) = viewModelScope.launch {
        generationManager.clearMcpAuthorization(server)
    }












    private val _scrollToMessage = MutableSharedFlow<String?>(replay = 0)
    val scrollToMessage = _scrollToMessage.asSharedFlow()




    /** One-shot: set when sendMessage creates a new conversation so the conversation-open
     *  auto-scroll skips once (the send's scroll-to-message already handles it), preventing
     *  a double scroll on the first message of a new chat. Consumed by ChatApp. */
    @Volatile
    var suppressNextOpenScroll: Boolean = false




    fun triggerScrollToMessage(messageId: String? = null) {
        viewModelScope.launch {
            _scrollToMessage.emit(messageId)
        }
    }




    private val _currentActiveModel = MutableStateFlow<String?>(null)
    val currentActiveModel = kotlinx.coroutines.flow.combine(_currentActiveModel, settings.selectedModel) { active, default ->
        active ?: default
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Constants.EXAMPLE_MODEL_ID)




    fun getProviderForModel(modelId: String): String = providerRegistry.providerForModel(modelId)
    




        
    // Embedding subsystem state lives in [ragManager]; exposed here for the UI.
    val activeEmbeddingModel get() = ragManager.activeEmbeddingModel
    val cachingProgress get() = ragManager.cachingProgress
    val cacheCounts get() = ragManager.cacheCounts
    fun loadCacheCounts() = ragManager.loadCacheCounts()




    // ── Remote shell command confirmation gate ───────────────────────────
    /** Shell-command confirmation policy + pending-prompt handshake (see [ShellConfirmationController]). */
    private val shellConfirmation = ShellConfirmationController(settings)
    val pendingShellCommand: StateFlow<ShellConfirmationController.PendingShellCommand?>
        get() = shellConfirmation.pendingShellCommand




    /** Called by the UI to resolve a pending confirmation. */
    fun resolveShellConfirmation(allow: Boolean, alwaysAllowServer: Boolean = false) =
        shellConfirmation.resolve(allow, alwaysAllowServer)

    // ── ask_user question sheet ──────────────────────────────────────────
    /** Question/answer handshake for the `ask_user` tool (see [AskController]). */
    private val askController = AskController()
    val pendingAsk: StateFlow<AskController.PendingAsk?> get() = askController.pending

    /** Called by the sheet with the user's picks (in order, for rank mode). */
    fun resolveAsk(values: List<String>, custom: Boolean) = askController.resolve(values, custom)

    /** Called when the sheet is dismissed without an answer. */
    fun dismissAsk() = askController.dismiss()




    fun setShellConfirmEnabled(enabled: Boolean) = shellConfirmation.setEnabled(enabled)




    // ── Auto Backup ───────────────────────────────────────────




        val conversations: StateFlow<List<ChatConversation>> = convRepo.getAllConversations()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _currentConversationId = MutableStateFlow<String?>(null)
    val currentConversationId: StateFlow<String?> = _currentConversationId.asStateFlow()




    private val _allMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val allMessages: StateFlow<List<ChatMessage>> = _allMessages.asStateFlow()




    private val _isSyncingModels = MutableStateFlow(false)
    val isSyncingModels: StateFlow<Boolean> = _isSyncingModels.asStateFlow()




    private val _snackbarMessage = MutableSharedFlow<SnackbarEvent>(replay = 1)
    val snackbarMessage = _snackbarMessage.asSharedFlow()
    fun emitSnackbar(message: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        viewModelScope.launch { _snackbarMessage.emit(SnackbarEvent(message, actionLabel, onAction)) }
    }




    private val _updateDialogData = MutableStateFlow<UpdateInfo?>(null)
    val updateDialogData: StateFlow<UpdateInfo?> = _updateDialogData.asStateFlow()
    fun dismissUpdateDialog() { _updateDialogData.value = null }
    fun showUpdateDialog(info: UpdateInfo) { _updateDialogData.value = info }




    /** PDF / text-file preview state (see [MediaPreviewState]). */
    private val mediaPreview = MediaPreviewState()
    val previewPdfPages: StateFlow<List<String>> get() = mediaPreview.pdfPages
    val previewPdfIndex: StateFlow<Int> get() = mediaPreview.pdfIndex
    val previewFileContent: StateFlow<String?> get() = mediaPreview.fileContent
    val previewFileName: StateFlow<String?> get() = mediaPreview.fileName




    fun showPdfPreview(pages: List<String>, startIndex: Int) = mediaPreview.showPdf(pages, startIndex)
    fun showFilePreview(fileName: String, content: String) = mediaPreview.showFile(fileName, content)
    fun clearPreviews() = mediaPreview.clear()




    private val _streamingMessage = MutableStateFlow<ChatMessage?>(null)
    private val _selectedChildren = MutableStateFlow<Map<String?, String>>(emptyMap())




    val messages: StateFlow<List<ChatMessage>> = combine(
        _allMessages,
        _streamingMessage,
        _selectedChildren
    ) { allMsgs, streaming, selectedChildren ->
        // Single source of truth for the visible-path walk: the tested
        // ConversationUiState.resolvePath (covered by ConversationUiStateTest).
        ConversationUiState.resolvePath(allMsgs, streaming, selectedChildren)
    }.distinctUntilChanged()
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())




    val totalTokens: StateFlow<Int> = _allMessages.map { list ->
        list.sumOf { it.tokenCount }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)




    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    private val _generatingInConversationId = MutableStateFlow<String?>(null)
    val generatingInConversationId: StateFlow<String?> = _generatingInConversationId.asStateFlow()
    private val _generatingConversationIds = MutableStateFlow<Set<String>>(emptySet())
    val generatingConversationIds: StateFlow<Set<String>> = _generatingConversationIds.asStateFlow()

    private val messageQueueStore = MessageQueueStore()
    private val _messageQueues = MutableStateFlow<Map<String, List<QueuedMessage>>>(emptyMap())
    val messageQueues: StateFlow<Map<String, List<QueuedMessage>>> = _messageQueues.asStateFlow()
    /** Per-conversation composer text drafts ("new" for empty new-chat). */
    private val _composerDrafts = MutableStateFlow<Map<String, String>>(emptyMap())
    val composerDrafts: StateFlow<Map<String, String>> = _composerDrafts.asStateFlow()

    /** When non-null, composer is editing this queued item; send updates it in place. */
    private val _editingQueueItemId = MutableStateFlow<String?>(null)
    val editingQueueItemId: StateFlow<String?> = _editingQueueItemId.asStateFlow()
    private val _editingQueueConversationId = MutableStateFlow<String?>(null)
    val editingQueueConversationId: StateFlow<String?> = _editingQueueConversationId.asStateFlow()




    /** Race-free generation lifecycle: IO scope, current job, send gate, stop/persist tokens. */
    private val session = GenerationSession(
        app = application,
        convRepo = convRepo,
        settings = settings,
        isLoading = _isLoading,
        streamingMessage = _streamingMessage,
        generatingConversationIds = _generatingConversationIds,
        generatingInConversationId = _generatingInConversationId,
        allMessages = _allMessages,
        currentConversationId = _currentConversationId,
        onCacheMessages = { cacheMessagesForModel(it, silent = true) },
    )




    private val _isSwitching = MutableStateFlow(false)
    val isSwitching: StateFlow<Boolean> = _isSwitching.asStateFlow()




    private var switchingJob: Job? = null




    fun setSwitching(switching: Boolean) {
        _isSwitching.value = switching
    }




    private val _isNewChatMode = MutableStateFlow(true)
    val isNewChatMode: StateFlow<Boolean> = _isNewChatMode.asStateFlow()




    private val _isTransitioningToNewChat = MutableStateFlow(false)
    val isTransitioningToNewChat: StateFlow<Boolean> = _isTransitioningToNewChat.asStateFlow()




    private val _pendingSystemPromptId = MutableStateFlow<String?>(null)
    val pendingSystemPromptId: StateFlow<String?> = _pendingSystemPromptId.asStateFlow()




    fun setPendingSystemPrompt(promptId: String?) {
        _pendingSystemPromptId.value = promptId
    }




    private val _pendingConversationSettings = MutableStateFlow<ConversationSettings?>(null)
    val pendingConversationSettings: StateFlow<ConversationSettings?> = _pendingConversationSettings.asStateFlow()




    fun setPendingConversationSettings(settings: ConversationSettings?) {
        _pendingConversationSettings.value = settings
    }




    private val payloadBuilder by lazy {
        MessagePayloadBuilder(
            generationManager = generationManager,
            onSnackbar = { msg -> _snackbarMessage.emit(SnackbarEvent(msg)) },
        )
    }




    private val requestBuilder = GenerationRequestBuilder(
        settings = settings,
        convRepo = convRepo,
        memoryManager = memoryManager,
        providerRegistry = providerRegistry,
        ragManager = ragManager,
        modelsDevRepository = modelsDevRepository,
        appContext = appContext,
        currentActiveModel = currentActiveModel,
        pendingConversationSettings = _pendingConversationSettings,
        onSnackbar = { msg -> emitSnackbar(msg) },
        forceWebSearch = { _forceWebSearch.value },
        forceImageGen = { _forceImageGen.value },
        forceGithub = { _forceGithub.value },
        forceTodoist = { _forceTodoist.value },
        forceNotion = { _forceNotion.value },
    )



    private val generationController by lazy {
        MessageGenerationController(
            viewModelScope = viewModelScope,
            application = getApplication(),
            appContext = appContext,
            convRepo = convRepo,
            settings = settings,
            session = session,
            generationManagerProvider = { generationManager },
            requestBuilder = requestBuilder,
            payloadBuilder = payloadBuilder,
            providerRegistry = providerRegistry,
            localProvider = localProvider,
            allMessages = _allMessages,
            selectedChildren = _selectedChildren,
            streamingMessage = _streamingMessage,
            currentConversationId = _currentConversationId,
            isLoading = _isLoading,
            generatingInConversationId = _generatingInConversationId,
            isNewChatMode = _isNewChatMode,
            pendingConversationSettings = _pendingConversationSettings,
            pendingSystemPromptId = _pendingSystemPromptId,
            currentActiveModel = currentActiveModel,
            messages = messages,
            onScrollToMessage = { id -> triggerScrollToMessage(id) },
            onSnackbar = { msg -> emitSnackbar(msg) },
            onSnackbarSuspend = { msg -> _snackbarMessage.emit(SnackbarEvent(msg)) },
            onPersistSelectedChildren = { convId, map -> persistSelectedChildren(convId, map) },
            onConversationCreatedBySend = { suppressNextOpenScroll = true },
            onGenerationFinished = { convId -> drainMessageQueue(convId) },
        )
    }




    fun updateConversationSetting(convId: String?, update: (ConversationSettings) -> ConversationSettings) {
        if (convId != null) {
            val current = settings.conversationSettings.value[convId] ?: ConversationSettings()
            settings.setConversationSettings(convId, update(current))
        } else {
            val current = _pendingConversationSettings.value ?: ConversationSettings()
            _pendingConversationSettings.value = update(current)
        }
    }




    private val _branchSwitchTrigger = MutableStateFlow<String?>(null)
    val branchSwitchTrigger: StateFlow<String?> = _branchSwitchTrigger.asStateFlow()




    fun clearBranchSwitchTrigger() {
        _branchSwitchTrigger.value = null
    }




    // Export/Import state lives in [importExport]; exposed here for the UI.
    val exportProgress get() = importExport.exportProgress
    val importProgress get() = importExport.importProgress
    val importManifest get() = importExport.importManifest
    val importPreview get() = importExport.importPreview
    val claudeImportPreview get() = importExport.claudeImportPreview
    val claudeImportProgress get() = importExport.claudeImportProgress
    val claudeImportResult get() = importExport.claudeImportResult
    val gptImportPreview get() = importExport.gptImportPreview
    val gptImportProgress get() = importExport.gptImportProgress
    val gptImportResult get() = importExport.gptImportResult








    private val _conversationCount = MutableStateFlow(0)
    val conversationCount: StateFlow<Int> = _conversationCount.asStateFlow()




    private val _memoryCount = MutableStateFlow(0)
    val memoryCount: StateFlow<Int> = _memoryCount.asStateFlow()




    private val _systemPromptCount = MutableStateFlow(0)
    val systemPromptCount: StateFlow<Int> = _systemPromptCount.asStateFlow()




    init {
        startInitJobs()
        viewModelScope.launch {
            _currentConversationId.collectLatest { id ->
                if (id != null) {
                    // Fix stuck sending states when loading conversation — skip if currently generating.
                                        // Also skip very recent SENDING rows: sendMessage marks generating slightly
                                        // AFTER creating a new conversation id, and this collector would otherwise
                                        // race and stamp the brand-new assistant placeholder as STOPPED.
                                        if (!session.isGenerating(id)) {
                                            val now = System.currentTimeMillis()
                                            val stuckMessages = convRepo.getMessagesForConversation(id).first()
                                                .filter {
                                                    (it.status == MessageStatus.SENDING ||
                                                        it.status == MessageStatus.THINKING ||
                                                        it.status == MessageStatus.TOOL_CALLING ||
                                                        it.status == MessageStatus.TRANSCRIBING) &&
                                                        now - it.timestamp > 15_000L
                                                }

                                            stuckMessages.forEach { msg ->
                                                // Re-check generating before each write — a send may have started.
                                                if (!session.isGenerating(id)) {
                                                    convRepo.upsertMessage(msg.copy(status = MessageStatus.STOPPED))
                                                }
                                            }
                                        }




                    // Restore selected branches
                    val conversation = convRepo.getConversation(id)
                    if (conversation?.selectedBranchesJson != null) {
                        try {
                            val map = Json.decodeFromString<Map<String, String>>(conversation.selectedBranchesJson)
                            val decodedMap = map.mapKeys { if (it.key == "null") null else it.key }
                            _selectedChildren.value = decodedMap
                        } catch (e: Exception) {
                            _selectedChildren.value = emptyMap()
                        }
                    } else {
                        _selectedChildren.value = emptyMap()
                    }




                    convRepo.getMessagesForConversation(id).collect { entities ->
                        val mapped = entities.map {
                            ChatMessage(
                                id = it.id,
                                parentId = it.parentId,
                                text = SearchResultFormatter.format(it.text, appContext),
                                images = it.images,
                                thoughts = it.thoughts,
                                thoughtTitle = it.thoughtTitle,
                                tokenCount = it.tokenCount,
                                promptTokens = it.promptTokens,
                                cachedPromptTokens = it.cachedPromptTokens,
                                cacheTelemetryAvailable = it.cacheTelemetryAvailable,
                                completionTokens = it.completionTokens,
                                ttftMs = it.ttftMs,
                                roundUsage = it.roundUsageJson?.let { json ->
                                    try {
                                        Json.decodeFromString<List<GenerationRoundUsage>>(json)
                                    } catch (_: Exception) {
                                        emptyList()
                                    }
                                }.orEmpty(),
                                status = it.status,
                                participant = it.participant,
                                timestamp = it.timestamp,
                                completedAt = it.completedAt,
                                thoughtTimeMs = it.thoughtTimeMs,
                                modelName = it.modelName,
                                segments = it.toolCallJson?.let { json ->
                                    try { Json.decodeFromString<List<MessageSegment>>(json) } catch (_: Exception) { null }
                                } ?: it.thoughts?.takeIf { t -> t.isNotBlank() }?.let { listOf(MessageSegment(type = "thought", content = it)) },
                                toolCall = it.toolCallJson?.let { json ->
                                                                    try {
                                                                        val segs = Json.decodeFromString<List<MessageSegment>>(json)
                                                                        segs.lastOrNull { s -> s.type == "tool" }?.let { s ->
                                                                            val rawResult = s.toolResult ?: ""
                                                                            ToolCallData(
                                                                                toolName = s.toolName ?: "",
                                                                                arguments = s.toolArgs ?: "{}",
                                                                                result = SearchResultFormatter.format(rawResult, appContext),
                                                                                signature = s.signature,
                                                                                toolCallId = s.toolCallId,
                                                                            )
                                                                        }
                                                                    } catch (_: Exception) { null }
                                                                },
                                attachmentMeta = it.attachmentMeta?.let { json ->
                                    try { Json.decodeFromString<AttachmentMeta>(json) } catch (_: Exception) { null }
                                }
                            )
                        }
                        // Backfill toolCall for old result_ messages persisted without toolCallJson.
                        // They inherit the parent tool_ message's ToolCallData so the provider can
                        // format them as proper "tool" role messages with matching tool_call_id.
                        _allMessages.value = mapped.map { msg ->
                            if (msg.id.startsWith(Constants.RESULT_MSG_PREFIX) && msg.toolCall == null) {
                                val parentTool = mapped.find { it.id == msg.parentId }
                                if (parentTool != null && parentTool.toolCall != null) {
                                    msg.copy(toolCall = parentTool.toolCall)
                                } else msg
                            } else msg
                        }
                    }
                } else {
                    _allMessages.value = emptyList()
                    _selectedChildren.value = emptyMap()
                }
            }
        }
        
        viewModelScope.launch {
            _selectedChildren.collect { childrenMap ->
                val id = _currentConversationId.value
                if (id != null) {
                    persistSelectedChildren(id, childrenMap)
                }
            }
        }
    }




    private suspend fun persistSelectedChildren(conversationId: String, childrenMap: Map<String?, String>) {
        convRepo.saveBranchSelections(conversationId, childrenMap)
    }




    // ── Custom providers ──────────────────────────────────────
    // Settings persistence lives in SettingsRepository; ChatViewModel only maintains
    // the live in-memory provider instances (the `providers` map) via callbacks.
    fun addCustomProvider(name: String, baseUrl: String) = providerRegistry.addCustom(name, baseUrl)
    fun renameCustomProvider(oldName: String, newName: String) = providerRegistry.renameCustom(oldName, newName)
    fun deleteCustomProvider(name: String) = providerRegistry.deleteCustom(name)




    fun getCurrentVersion(): String {
        return try { appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "?" } catch (_: Exception) { "?" }
    }
    /**
     * Checks the channel the user selected. The CI channel compares Actions run
     * numbers (every CI build shares one versionName, so semver cannot tell them
     * apart); `BuildConfig.CI_RUN_NUMBER` is 0 for locally built APKs, which makes
     * any CI build count as newer.
     */
    suspend fun checkForUpdates(): com.newoether.agora.util.UpdateCheckResult {
        return when (com.newoether.agora.util.UpdateChannel.from(settings.updateChannel.value)) {
            com.newoether.agora.util.UpdateChannel.CI ->
                UpdateChecker.checkCi(com.newoether.agora.BuildConfig.CI_RUN_NUMBER)
            com.newoether.agora.util.UpdateChannel.STABLE ->
                UpdateChecker.check(getCurrentVersion())
        }
    }




    suspend fun fetchAllReleaseNotes(): List<com.newoether.agora.util.ReleaseNotes> =
        withContext(Dispatchers.IO) {
            UpdateChecker.fetchAllReleaseNotes(getCurrentVersion())
        }




    fun addEmbeddingModel(config: EmbeddingModelConfig) = ragManager.addEmbeddingModel(config)
    fun deleteEmbeddingModel(id: String) = ragManager.deleteEmbeddingModel(id)
    fun renameEmbeddingModel(id: String, newName: String, batchSize: Int? = null) =
        ragManager.renameEmbeddingModel(id, newName, batchSize)
    fun setActiveEmbeddingModel(id: String) = ragManager.setActiveEmbeddingModel(id)
    fun cacheMessagesForModel(modelId: String, recache: Boolean = false, silent: Boolean = false) =
        ragManager.cacheMessagesForModel(modelId, recache, silent)




    fun isLocalModelIdTaken(modelId: String, excludeId: String? = null) =
        modelManager.isLocalModelIdTaken(modelId, excludeId)
    fun addLocalChatModel(config: LocalChatModelConfig) = modelManager.addLocalChatModel(config)
    fun deleteLocalChatModel(uuid: String) = modelManager.deleteLocalChatModel(uuid)
    fun updateLocalChatModel(
        uuid: String, newModelId: String, newAlias: String, nCtx: Int, temperature: Float, topP: Float, maxTokens: Int,
        mmprojPath: String = ""
    ) = modelManager.updateLocalChatModel(uuid, newModelId, newAlias, nCtx, temperature, topP, maxTokens, mmprojPath)




    suspend fun semanticSearch(query: String, limit: Int = 20): List<Pair<MessageEntity, Float>> {
        val ctx = GenerationContext(
            accessSavedMemories = settings.accessSavedMemories.value,
            accessActiveMemory = settings.accessActiveMemory.value,
            accessPastConversations = settings.accessPastConversations.value,
            modelSearchMethod = settings.modelSearchMethod.value,
            activeEmbeddingConfig = activeEmbeddingModel.value,
            embeddingApiKey = ragManager.resolveEmbeddingApiKey() ?: "",
            ragThreshold = settings.ragThreshold.value,
            searchMatchLimit = settings.searchMatchLimit.value,
            searchContextWindow = settings.searchContextWindow.value,
            webSearchEnabled = settings.webSearchEnabled.value,
            webSearchApiKeys = settings.webSearchApiKeys.value,
            webSearchProvider = settings.webSearchProvider.value,
            webSearchNumResults = settings.webSearchNumResults.value,
            webSearchBaseUrl = settings.webSearchBaseUrl.value
        )
        return generationManager.semanticSearch(query, limit, ctx)
    }




    fun resolveEmbeddingKeyForProviderExact(targetProvider: String) =
        ragManager.resolveEmbeddingKeyForProviderExact(targetProvider)




    fun indexMessageForRag(messageId: String, text: String) = ragManager.indexMessageForRag(messageId, text)
    suspend fun searchMessages(query: String, limit: Int = 20) = convRepo.searchMessages(query, limit)
    // ── Auto Backup ───────────────────────────────────────────
    fun setAutoBackupEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            settings.saveAutoBackupEnabled(enabled)
            if (enabled) {
                try { AutoBackupWorker.schedule(getApplication()) } catch (_: Exception) {}
            } else {
                try { AutoBackupWorker.cancel(getApplication()) } catch (_: Exception) {}
            }
        }
    }
    fun setAutoBackupPeriodHours(hours: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            settings.saveAutoBackupPeriodHours(hours)
            // Enforce: auto-delete period must be strictly greater than backup period
            val deleteTiers = AUTO_DELETE_TIERS_HOURS
            val deleteHours = settings.autoDeletePeriodHours.value
            if (deleteHours <= hours) {
                val nextDelete = deleteTiers.firstOrNull { it > hours } ?: AUTO_DELETE_TIERS_HOURS.last()
                settings.saveAutoDeletePeriodHours(nextDelete)
            }
        }
    }
    fun setAutoBackupCategories(categories: String) {
        viewModelScope.launch(Dispatchers.IO) { settings.saveAutoBackupCategories(categories) }
    }
    fun setAutoBackupDirectory(path: String) {
        viewModelScope.launch(Dispatchers.IO) { settings.saveAutoBackupDirectory(path) }
    }
    fun setAutoDeleteEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { settings.saveAutoDeleteEnabled(enabled) }
    }
    fun setAutoDeletePeriodHours(hours: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val backupHours = settings.autoBackupPeriodHours.value
            val deleteTiers = AUTO_DELETE_TIERS_HOURS
            // Find the smallest valid delete tier that is > backupHours, and >= the requested hours
            val minValid = deleteTiers.firstOrNull { it > backupHours } ?: AUTO_DELETE_TIERS_HOURS.last()
            settings.saveAutoDeletePeriodHours(maxOf(hours, minValid))
        }
    }
    fun addShellDevice(device: ShellDeviceConfig) {
        settings.addShellDevice(device)
    }
    fun updateShellDevice(device: ShellDeviceConfig) {
        settings.updateShellDevice(device)
    }




    /**
     * Connects to an SSH host in capture mode and returns the server host key
     * (base64) together with its SHA-256 fingerprint, for the user to review and
     * pin. The host key is exchanged before authentication, so this succeeds even
     * if the password is wrong — letting the user pin the key first.
     */
    suspend fun verifySshHostKey(
        host: String, port: Int, user: String, password: String, timeoutSec: Int
    ): Result<Pair<String, String>> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        if (host.isBlank()) return@withContext Result.failure(Exception("Host is empty"))
        val client = SshClient(
            host, port, user.ifBlank { "root" }, password, timeoutSec * 1000,
            pinnedHostKey = "", allowUnknownHostKey = true
        )
        try {
            client.executeCommand("true")
        } catch (_: Exception) {
            // Ignore — the host key is captured during the handshake regardless of auth result.
        } finally {
            client.close()
        }
        val key = client.capturedHostKey
        if (key.isNullOrBlank()) Result.failure(Exception("Could not reach host or no host key presented"))
        else Result.success(key to SshClient.fingerprintSha256(key))
    }
    suspend fun testRemoteEmbedding(modelName: String, baseUrl: String, apiKey: String = ""): String? {
        val effectiveKey = apiKey.ifBlank { ragManager.resolveEmbeddingApiKey() ?: "" }
        val url = baseUrl.ifBlank { ragManager.resolveEmbeddingBaseUrl() }
        return withContext(Dispatchers.IO) {
            try {
                val result = EmbeddingClient.computeEmbedding("test connection", effectiveKey, modelName, url)
                if (result != null) "OK (dim=${result.size})" else "Request failed. Check API key, URL, and model name."
            } catch (e: Exception) {
                e.message ?: "Error"
            }
        }
    }




    fun createNewChat() {
        switchingJob?.cancel()
        if (!_isNewChatMode.value) {
            _pendingSystemPromptId.value = null
        }
        _isNewChatMode.value = true
        _isTransitioningToNewChat.value = true
        _isSwitching.value = true
        switchingJob = viewModelScope.launch {
            kotlinx.coroutines.delay(SWITCH_OVERLAY_FADE_MS) // Allow overlay to fade in
            _currentConversationId.value = null
            session.onConversationFocused(null)
            _currentActiveModel.value = null
            _pendingConversationSettings.value = null
            _allMessages.value = emptyList()
            _selectedChildren.value = emptyMap()
            _branchSwitchTrigger.value = null
            _isSwitching.value = false
            _isTransitioningToNewChat.value = false
        }
    }




    fun selectConversation(id: String) {
            if (_currentConversationId.value == id && !_isNewChatMode.value) return

            switchingJob?.cancel()
            _isTransitioningToNewChat.value = false
            _isSwitching.value = true
            switchingJob = viewModelScope.launch {
                kotlinx.coroutines.delay(SWITCH_OVERLAY_FADE_MS) // Allow overlay to fade in
                _isNewChatMode.value = false
                _branchSwitchTrigger.value = null
                _currentConversationId.value = id
                // Stop/send + streaming overlay follow the focused conversation only.
                session.onConversationFocused(id)
                val conversation = convRepo.getConversation(id)
                _currentActiveModel.value = conversation?.modelId
                triggerScrollToMessage()
            }
        }




    fun renameConversation(id: String, newTitle: String) {
        viewModelScope.launch {
            val existing = convRepo.getConversation(id)
            if (existing != null) {
                convRepo.upsertConversation(existing.copy(title = newTitle))
            }
        }
    }








    /**
     * One-shot generation that rewrites the holistic active memory from a user instruction.
     * Uses [modelIdWithPrefix] when provided, otherwise the currently selected model.
     * Returns generated text or null on failure.
     */
    suspend fun generateHolisticMemory(
        userInstruction: String,
        modelIdWithPrefix: String? = null,
        thinkingEnabled: Boolean = false,
        thinkingLevel: String = "medium",
        fastEnabled: Boolean = false,
    ): String? = withContext(Dispatchers.IO) {
        val resolvedModel = modelIdWithPrefix?.takeIf { it.isNotBlank() } ?: settings.selectedModel.value
        if (resolvedModel.isBlank()) return@withContext null
        val modelId = ModelId.parse(resolvedModel).modelName
        val providerName = providerRegistry.providerForModel(resolvedModel)
        val activeKey = settings.awaitApiKeyForModel(resolvedModel, providerName)
            ?: settings.resolveApiKeyForModel(resolvedModel, providerName)
            ?: settings.awaitActiveKey(providerName)
            ?: settings.resolveActiveKey(providerName)
            ?: ""
        if (!providerRegistry.isConfigured(providerName, activeKey)) return@withContext null
        val caps = modelCapabilitiesFor(resolvedModel)
        val current = memoryManager.getActiveMemory()
        val nl = 10.toChar()
        val system = listOf(
            "You are a memory compiler for a personal AI assistant.",
            "Rewrite the user's OVERALL memory as a single cohesive document.",
            "Keep durable facts, preferences, identity, ongoing projects, and constraints.",
            "Prefer concise bullet sections. Do not invent facts not implied by input.",
            "Output ONLY the final memory document, no preamble."
        ).joinToString(nl.toString())
        val user = buildString {
            append("User request:")
            append(nl)
            append(userInstruction.trim())
            append(nl)
            append(nl)
            if (current.isNotBlank()) {
                append("Current memory (may be empty/outdated):")
                append(nl)
                append(current)
                append(nl)
                append(nl)
            }
            append("Produce the updated overall memory document.")
        }
        val provider = providerRegistry.getInstance(providerName)
        val config = ProviderConfig(
            apiKey = activeKey,
            modelId = modelId,
            systemPrompt = system,
            maxContextWindow = 4,
            thinkingEnabled = thinkingEnabled && caps.reasoning,
            thinkingLevel = thinkingLevel,
            fastEnabled = fastEnabled && caps.fast,
            baseUrl = providerRegistry.getEffectiveBaseUrl(providerName),
            alternateApiKeys = emptyList(),
        )
        val msgs = listOf(
            ChatMessage(
                text = user,
                participant = Participant.USER,
                status = MessageStatus.SUCCESS
            )
        )
        var out = ""
        try {
            if (providerName == Constants.PROVIDER_LOCAL) {
                LlamaEngine.modelMutex.withLock {
                    provider.generateResponse(msgs, config).collect { event ->
                        if (event is StreamEvent.TextChunk) out += event.text
                    }
                    localProvider.releaseEngine()
                }
            } else {
                provider.generateResponse(msgs, config).collect { event ->
                    if (event is StreamEvent.TextChunk) out += event.text
                }
            }
        } catch (e: Exception) {
            DebugLog.e("AgoraVM", "Holistic memory generation failed", e)
            return@withContext null
        }
        out.trim().ifBlank { null }
    }




    fun generateTitle(conversationId: String) = generationController.generateTitle(conversationId)








    fun setConversationSystemPrompt(id: String, promptId: String?) {
        viewModelScope.launch {
            val existing = convRepo.getConversation(id)
            if (existing != null) {
                convRepo.upsertConversation(existing.copy(systemPromptId = promptId))
            }
        }
    }




    fun setActiveModel(model: String) {
        _currentActiveModel.value = model
        _currentConversationId.value?.let { id ->
            viewModelScope.launch {
                val existing = convRepo.getConversation(id)
                if (existing != null) {
                    convRepo.upsertConversation(existing.copy(modelId = model))
                }
            }
        }
    }




    fun deleteConversation(id: String) {
        val stopFinalization = if (session.isGenerating(id)) {
            session.stopConversation(id, releaseSendGate = false)
        } else {
            session.pendingStopFinalization(id)
        }
        viewModelScope.launch(Dispatchers.IO) {
            stopFinalization?.join()
            session.withInvalidatedPersistence(id) {
                convRepo.deleteConversation(id)
            }
            if (_currentConversationId.value == id) createNewChat()
        }
    }




    /**
     * Delete every conversation and related message data.
     * Stops any in-flight generation, wipes the chat DB, then returns UI to new-chat mode.
     */
    fun deleteAllConversations(onDone: ((Boolean) -> Unit)? = null) {
        val stopFinalizations = session.stopAllConversations(releaseSendGate = false)
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try {
                stopFinalizations.forEach { it.join() }
                val conversationIds = convRepo.getAllConversationsList().map { it.id }
                session.withAllInvalidatedPersistence(conversationIds) {
                    convRepo.deleteAllConversations()
                }
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
            if (ok) {
                // UI state updates on Main so Compose observers stay consistent.
                withContext(Dispatchers.Main) {
                    createNewChat()
                    refreshDataCounts()
                }
                emitSnackbar(getApplication<Application>().getString(R.string.data_delete_all_chats_done))
            } else {
                emitSnackbar(getApplication<Application>().getString(R.string.data_delete_all_chats_failed))
            }
            onDone?.invoke(ok)
        }
    }




    /**
     * Deletes a message and all its descendants (BFS cascade).
     * Hidden tool_/result_ children are included in the cascade.
     * Attachments, embeddings, and branch selections are cleaned up.
     * Returns the count of deleted messages (for the confirmation dialog).
     */
    fun deleteMessage(messageId: String): Int = generationController.deleteMessage(messageId)




    // Also drain when user manually stops generation for this conversation.
    fun stopGeneration() {
        val convId = _currentConversationId.value
        com.newoether.agora.util.CrashReporter.note("stopGeneration conv=${convId?.take(8)}")
        session.stop()
        // After user stops the current reply, immediately send the next queued message.
        if (convId != null) {
            viewModelScope.launch {
                kotlinx.coroutines.delay(50)
                drainMessageQueue(convId)
            }
        }
    }

    fun queueForConversation(conversationId: String?): List<QueuedMessage> {
        if (conversationId == null) return emptyList()
        return _messageQueues.value[conversationId].orEmpty()
    }

    fun isConversationGenerating(conversationId: String): Boolean =
        conversationId in _generatingConversationIds.value

    /**
     * Send immediately, or enqueue if this conversation is already generating.
     * When [editingQueueItemId] is set, updates that queue item instead of sending/enqueuing new.
     */
    fun sendOrEnqueueMessage(
        text: String,
        attachments: List<SelectedAttachment> = emptyList(),
    ): Boolean {
        val trimmed = text
        val convId = _currentConversationId.value
        val editingSentId = _editingSentMessageId.value
        if (editingSentId != null) {
            clearSentMessageEdit()
            generationController.editMessage(editingSentId, trimmed)
            return true
        }
        val editingId = _editingQueueItemId.value
        val editingConv = _editingQueueConversationId.value
        if (editingId != null && editingConv != null) {
            // Finish queue edit: replace item in place, leave queue order unchanged.
            _messageQueues.value = messageQueueStore.update(editingConv, editingId, trimmed, attachments).also { persistMessageQueues(it) }
            clearQueueEdit()
            return true
        }
        // Only queue when THIS conversation is already generating. Other conversations may generate in parallel.
        if (convId != null && session.isGenerating(convId)) {
            if (trimmed.isBlank() && attachments.isEmpty()) return false
            val item = QueuedMessage(conversationId = convId, text = trimmed, attachments = attachments)
            _messageQueues.value = messageQueueStore.enqueue(item).also { persistMessageQueues(it) }
            return true
        }
        return generationController.sendMessage(trimmed, attachments = attachments)
    }

    fun removeQueuedMessage(conversationId: String, itemId: String) {
        if (_editingQueueItemId.value == itemId) clearQueueEdit()
        _messageQueues.value = messageQueueStore.remove(conversationId, itemId).also { persistMessageQueues(it) }
    }

    fun beginEditQueuedMessage(conversationId: String, itemId: String): QueuedMessage? {
        val item = _messageQueues.value[conversationId].orEmpty().find { it.id == itemId } ?: return null
        _editingQueueItemId.value = itemId
        _editingQueueConversationId.value = conversationId
        return item
    }

    fun clearQueueEdit() {
        _editingQueueItemId.value = null
        _editingQueueConversationId.value = null
    }

    fun setComposerDraft(conversationKey: String, text: String) {
        val key = conversationKey.ifBlank { "new" }
        val next = _composerDrafts.value.toMutableMap()
        if (text.isEmpty()) next.remove(key) else next[key] = text
        _composerDrafts.value = next
        persistComposerDrafts(next)
    }

    fun composerDraftFor(conversationKey: String?): String {
        val key = conversationKey?.takeIf { it.isNotBlank() } ?: "new"
        return _composerDrafts.value[key].orEmpty()
    }

    private fun persistComposerDrafts(map: Map<String, String>) {
        viewModelScope.launch {
            try {
                settings.setComposerDraftsJson(Json.encodeToString(map))
            } catch (_: Exception) { }
        }
    }

    private fun persistMessageQueues(map: Map<String, List<QueuedMessage>>) {
        viewModelScope.launch {
            try {
                // Persist text-only queue items (attachments re-picked if needed after process death).
                val slim = map.mapValues { (_, list) ->
                    list.map { mapOf("id" to it.id, "conversationId" to it.conversationId, "text" to it.text) }
                }
                settings.setMessageQueuesJson(Json.encodeToString(slim))
            } catch (_: Exception) { }
        }
    }

    private fun loadPersistedQueuesAndDrafts() {
        viewModelScope.launch {
            try {
                val draftJson = settings.composerDraftsJson.value
                if (draftJson.isNotBlank() && draftJson != "{}") {
                    _composerDrafts.value = Json.decodeFromString(draftJson)
                }
            } catch (_: Exception) { }
            try {
                val qJson = settings.messageQueuesJson.value
                if (qJson.isNotBlank() && qJson != "{}") {
                    val raw: Map<String, List<Map<String, String>>> = Json.decodeFromString(qJson)
                    val restored = raw.mapValues { (convId, items) ->
                        items.map {
                            QueuedMessage(
                                id = it["id"] ?: UUID.randomUUID().toString(),
                                conversationId = it["conversationId"] ?: convId,
                                text = it["text"].orEmpty(),
                                attachments = emptyList(),
                            )
                        }
                    }
                    _messageQueues.value = messageQueueStore.replaceAll(restored)
                }
            } catch (_: Exception) { }
        }
    }


    private fun drainMessageQueue(conversationId: String) {
        // Per-conversation: only skip if THIS conversation is still generating.
        if (session.isGenerating(conversationId)) return
        val (next, snap) = messageQueueStore.popFirst(conversationId)
        _messageQueues.value = snap
        persistMessageQueues(snap)
        if (next == null) return
        if (_editingQueueItemId.value == next.id) clearQueueEdit()
        // Send against the queue's conversation without forcing a UI switch if user is elsewhere.
        // MessageGenerationController.sendMessage uses currentConversationId for new rows, so
        // temporarily pin the id for the send if needed, then restore focus for drain-only.
        val previousId = _currentConversationId.value
        val previousNewChat = _isNewChatMode.value
        val needPin = previousId != conversationId
        if (needPin) {
            _isNewChatMode.value = false
            _currentConversationId.value = conversationId
        }
        try {
            generationController.sendMessage(next.text, attachments = next.attachments)
        } finally {
            // If user stayed on another chat, keep them there; only restore if we pinned for drain
            // and the send did not create a different conversation.
            if (needPin && _currentConversationId.value == conversationId) {
                // leave on the drained conversation only if it was empty before — otherwise restore.
                // Safer: restore previous focus so parallel background drain doesn't steal UI.
                _currentConversationId.value = previousId
                _isNewChatMode.value = previousNewChat
                session.onConversationFocused(previousId)
            }
        }
    }

    fun regenerate(messageId: String) = generationController.regenerate(messageId)




    fun switchBranch(parentId: String?, currentMessageId: String, direction: Int) {
        if (_currentConversationId.value != null && session.isGenerating(_currentConversationId.value)) return
        val siblings = _allMessages.value.filter { it.parentId == parentId && !it.id.startsWith(Constants.TOOL_MSG_PREFIX) && !it.id.startsWith(Constants.RESULT_MSG_PREFIX) }.sortedBy { it.timestamp }
        if (siblings.size < 2) return
        var currentIndex = siblings.indexOfFirst { it.id == currentMessageId }
        if (currentIndex == -1) {
            val selectedId = _selectedChildren.value[parentId]
            currentIndex = siblings.indexOfFirst { it.id == selectedId }
        }
        if (currentIndex == -1) return
        val newIndex = (currentIndex + direction).coerceIn(0, siblings.size - 1)
        if (newIndex == currentIndex) return
        
        switchingJob?.cancel()
        _isSwitching.value = true
        switchingJob = viewModelScope.launch {
            kotlinx.coroutines.delay(SWITCH_OVERLAY_FADE_MS) // Allow overlay to fade in
            val newMap = _selectedChildren.value.toMutableMap()
            val targetMessage = siblings[newIndex]
            newMap[parentId] = targetMessage.id
            _selectedChildren.value = newMap
            
            _branchSwitchTrigger.value = null
            _branchSwitchTrigger.value = targetMessage.id
        }
    }




    fun editMessage(messageId: String, newText: String) = generationController.editMessage(messageId, newText)




    private fun getFileName(context: android.content.Context, uri: android.net.Uri): String {
        return try {
            val cursor = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) it.getString(idx) ?: uri.lastPathSegment ?: "unknown"
                    else uri.lastPathSegment ?: "unknown"
                } else uri.lastPathSegment ?: "unknown"
            } ?: (uri.lastPathSegment ?: "unknown")
        } catch (_: Exception) {
            uri.lastPathSegment ?: "unknown"
        }
    }




    fun sendMessage(text: String, images: List<String> = emptyList(), attachments: List<SelectedAttachment> = emptyList()): Boolean =
        sendOrEnqueueMessage(text, attachments)




    /**
     * Onboarding-focused model fetch for a single provider.
     *
     * Unlike [fetchAvailableModels] this carries no global side effects: no
     * `_isSyncingModels` guard (so re-entry always refetches the latest key),
     * no enabled-set intersection, and no snackbar. It is a plain suspend
     * function so the caller's coroutine owns its lifecycle — cancelling that
     * coroutine cooperatively aborts the in-flight network request, which keeps
     * the welcome flow seamless (no stale result can land after the user edits
     * their key and returns). Results are persisted so the [availableModels]
     * flow updates the list. Returns the prefixed model ids, or empty on
     * failure / unconfigured provider.
     */
    suspend fun fetchModelsForProvider(name: String): List<String> = providerRegistry.fetchModelsForProvider(name)




    fun computeProviderFingerprint(): String = providerRegistry.computeFingerprint()




    fun fetchAvailableModels() {
        viewModelScope.launch {
            if (!_isSyncingModels.compareAndSet(expect = false, update = true)) return@launch
            val successProviders = mutableListOf<String>()
            val failedProviders = mutableListOf<String>()
            var skippedCount = 0




            val message = try {
                // Inside the guarded block: this reads DataStore, and when it threw here the
                // `finally` below never ran, so `isSyncingModels` stayed true and the sync button
                // was permanently unclickable until the app was restarted.
                providerRegistry.ensureCustomProvidersRegistered()
                // Providers are probed concurrently. Serially, one unreachable endpoint delayed
                // every provider behind it by its full timeout, so a sync across several providers
                // could sit silent for minutes and read as "nothing happened". DataStore edits are
                // atomic read-modify-writes, so per-provider persistence is safe in parallel.
                val outcomes = providerRegistry.all.keys
                    .filter { it != Constants.PROVIDER_LOCAL }
                    .map { name ->
                        async {
                            if (!settings.isProviderEnabled(name)) return@async name to SyncOutcome.SKIPPED
                            try {
                                if (!providerRegistry.isConfigured(
                                        name,
                                        settings.resolveActiveKey(name) ?: "",
                                    )
                                ) {
                                    settings.saveAvailableModels(name, emptyList())
                                    return@async name to SyncOutcome.SKIPPED
                                }
                                val models = providerRegistry.fetchModelsForProvider(name)
                                name to if (models.isNotEmpty()) {
                                    SyncOutcome.SUCCESS
                                } else {
                                    SyncOutcome.FAILED
                                }
                            } catch (e: CancellationException) {
                                // On the JVM CancellationException extends IllegalStateException, so
                                // a bare `catch (Exception)` would swallow the cancel signal and let
                                // a stopped sync report a bogus result.
                                throw e
                            } catch (e: Exception) {
                                name to SyncOutcome.FAILED
                            }
                        }
                    }
                    .awaitAll()
                outcomes.forEach { (name, outcome) ->
                    when (outcome) {
                        SyncOutcome.SUCCESS -> successProviders.add(name)
                        SyncOutcome.FAILED -> failedProviders.add(name)
                        SyncOutcome.SKIPPED -> skippedCount++
                    }
                }




                val allFetchedModels = settings.getAvailableModels().values.flatten().toSet()
                val newEnabled = settings.enabledModels.value.intersect(allFetchedModels)
                settings.setEnabledModels(newEnabled)




                // Save the fingerprint only when nothing failed. Storing it after a partial sync
                // marked the current configuration as "already fetched", so re-entering the page
                // skipped the auto-sync and the failed provider stayed missing until a manual retry.
                if (failedProviders.isEmpty()) {
                    settings.saveLastModelsFetchFingerprint(computeProviderFingerprint())
                }




                when {
                    successProviders.isNotEmpty() && failedProviders.isEmpty() ->
                        appContext.getString(R.string.sync_success_providers, successProviders.size)
                    successProviders.isNotEmpty() && failedProviders.isNotEmpty() ->
                        appContext.getString(R.string.sync_partial, successProviders.joinToString(), failedProviders.joinToString())
                    successProviders.isEmpty() && failedProviders.isNotEmpty() ->
                        appContext.getString(R.string.sync_failed_providers, failedProviders.joinToString())
                    else -> if (skippedCount > 0) appContext.getString(R.string.sync_no_providers) else appContext.getString(R.string.sync_completed)
                }
            } catch (e: CancellationException) {
                // Must precede the generic handler: on the JVM CancellationException extends
                // IllegalStateException, so `catch (Exception)` swallowed the cancel signal and
                // reported a bogus "sync failed" for a sync the user had simply navigated away from.
                throw e
            } catch (e: Exception) {
                appContext.getString(R.string.sync_failed_providers, e.message ?: appContext.getString(R.string.unknown_error))
            } finally {
                _isSyncingModels.value = false
            }




            _snackbarMessage.tryEmit(SnackbarEvent(message))
        }
    }




    // ---- Data Control: Export / Import ----




    fun refreshDataCounts() {
        viewModelScope.launch(Dispatchers.IO) {
            _conversationCount.value = convRepo.getAllConversationsList().size
            _memoryCount.value = memoryManager.listFiles().size +
                (if (memoryManager.getActiveMemory().isNotEmpty()) 1 else 0)
            _systemPromptCount.value = settings.getSystemPrompts().size
        }
    }




    fun exportData(uri: Uri, categories: Set<DataExporter.ExportCategory>, includeApiKeys: Boolean) =
        importExport.exportData(uri, categories, includeApiKeys)
    fun previewImport(uri: Uri) = importExport.previewImport(uri)
    fun clearImportState() = importExport.clearImportState()
    fun setClaudeImportPreview(preview: ClaudeChatImporter.ImportPreview) = importExport.setClaudeImportPreview(preview)
    fun previewClaudeChat(uri: Uri) = importExport.previewClaudeChat(uri)
    fun setClaudeImportError(error: String) = importExport.setClaudeImportError(error)
    fun clearClaudeImportState() = importExport.clearClaudeImportState()
    fun importClaudeChat(uri: Uri, strategy: ImportStrategy, selectedIds: Set<String>) =
        importExport.importClaudeChat(uri, strategy, selectedIds)
    fun previewGptChat(uri: Uri) = importExport.previewGptChat(uri)
    fun setGptImportError(error: String) = importExport.setGptImportError(error)
    fun clearGptImportState() = importExport.clearGptImportState()
    fun importGptChat(uri: Uri, strategy: ImportStrategy, selectedIds: Set<String>) =
        importExport.importGptChat(uri, strategy, selectedIds)
    fun importData(uri: Uri, decisions: Map<DataExporter.ExportCategory, DataImporter.ImportStrategy>) =
        importExport.importData(uri, decisions)
}
