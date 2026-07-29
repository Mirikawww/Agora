package com.newoether.agora.viewmodel

import android.app.Application
import android.content.Context
import com.newoether.agora.R
import com.newoether.agora.api.LlamaEngine
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.api.local.LocalProvider
import com.newoether.agora.data.BuiltInPrompts
import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.ModelId
import com.newoether.agora.model.Participant
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.isActive
import java.util.UUID

/**
 * Owns the message lifecycle (send / regenerate / edit / delete) and the
 * race-free generation handshake. Extracted VERBATIM from ChatViewModel.
 * Holds references to the SAME MutableStateFlow instances that ChatViewModel
 * exposes — do NOT create new ones here.
 */
class MessageGenerationController(
    // ── 协程作用域(用 viewModelScope 传进来)──
    private val viewModelScope: CoroutineScope,
    private val application: Application,
    private val appContext: Context,
    // ── 单例协作者 ──
    private val convRepo: ConversationRepository,
    private val settings: SettingsRepository,
    private val session: GenerationSession,
    private val generationManagerProvider: () -> GenerationManager,
    private val requestBuilder: GenerationRequestBuilder,
    private val payloadBuilder: MessagePayloadBuilder,
    private val providerRegistry: ProviderRegistry,
    private val localProvider: LocalProvider,
    // ── 共享 UI 状态:必须是 ChatViewModel 里的同一个实例 ──
    private val allMessages: MutableStateFlow<List<ChatMessage>>,          // = _allMessages
    private val selectedChildren: MutableStateFlow<Map<String?, String>>,  // = _selectedChildren
    private val streamingMessage: MutableStateFlow<ChatMessage?>,          // = _streamingMessage
    private val currentConversationId: MutableStateFlow<String?>,          // = _currentConversationId
    private val isLoading: MutableStateFlow<Boolean>,                      // = _isLoading
    private val generatingInConversationId: MutableStateFlow<String?>,     // = _generatingInConversationId
    private val isNewChatMode: MutableStateFlow<Boolean>,                  // = _isNewChatMode
    private val pendingConversationSettings: MutableStateFlow<ConversationSettings?>, // = _pendingConversationSettings
    private val pendingSystemPromptId: MutableStateFlow<String?>,          // = _pendingSystemPromptId
    private val currentActiveModel: StateFlow<String>,                     // = currentActiveModel(只读)
    private val messages: StateFlow<List<ChatMessage>>,                    // = messages(只读)
    // ── 回调:替换掉方法体里对 ChatViewModel 私有成员/方法的调用 ──
    private val onScrollToMessage: (String?) -> Unit,    // 替换 triggerScrollToMessage(...)
    private val onSnackbar: (String) -> Unit,            // 替换 emitSnackbar(...)
    private val onSnackbarSuspend: suspend (String) -> Unit,  // generateTitle 内的顺序 emit(等价原版 _snackbarMessage.emit）
    private val onPersistSelectedChildren: suspend (String, Map<String?, String>) -> Unit,
        // Called when sendMessage creates a NEW conversation, so the UI can suppress the
        // conversation-open auto-scroll (the send's own scroll-to-message handles it) and
        // avoid a double scroll on the first message of a new chat.
        private val onConversationCreatedBySend: () -> Unit = {},
        /** Invoked after a generation finishes (success/error/stop) so the host can drain the send queue. */
        private val onGenerationFinished: (conversationId: String) -> Unit = {},
    ) {
        private val generationManager: GenerationManager get() = generationManagerProvider()

    // ════════════════════════════════════════════════════════════════════
    // deleteMessage
    // ════════════════════════════════════════════════════════════════════

    /**
     * Deletes a message and all its descendants (BFS cascade).
     * Hidden tool_/result_ children are included in the cascade.
     * Attachments, embeddings, and branch selections are cleaned up.
     * Returns the count of deleted messages (for the confirmation dialog).
     */
    fun deleteMessage(messageId: String): Int {
        val currentId = currentConversationId.value ?: return 0

        // Synchronous snapshot for dialog count return — must stay on the calling thread.
        val snapshot = allMessages.value
        val targetMsg = snapshot.find { it.id == messageId } ?: return 0

        val previewIds = linkedSetOf(messageId)
        val queue = mutableListOf(messageId)
        while (queue.isNotEmpty()) {
            val pid = queue.removeAt(0)
            snapshot.filter { it.parentId == pid }.forEach {
                if (previewIds.add(it.id)) queue.add(it.id)
            }
        }

        // P1: Only stop generation if deleting within the currently-generating conversation.
        // P0: Use stopForReplacement() + join() to prevent the STOPPED-upsert race
        //     that can resurrect deleted messages (the only write path that was missing it).
        val stopFinalization = if (session.isGenerating(currentId)) {
            session.stopConversation(currentId, releaseSendGate = false)
        } else {
            session.pendingStopFinalization(currentId)
        }

        viewModelScope.launch(Dispatchers.IO) {
            // Wait for STOPPED DB finalization to complete before deleting.
            // Without this join, a concurrent upsertMessage from stop finalization
            // could resurrect the deleted row as a zombie/orphan after our DELETE.
            stopFinalization?.join()

            session.withInvalidatedPersistence(currentId) persistence@{
                // Recompute staleIds from the latest allMessages after join(),
                // in case the message tree changed during finalization.
                val allMsgs = allMessages.value
                if (allMsgs.none { it.id == messageId }) {
                    return@persistence
                }
                val staleIds = linkedSetOf(messageId)
                val queue = mutableListOf(messageId)
                while (queue.isNotEmpty()) {
                    val pid = queue.removeAt(0)
                    allMsgs.filter { it.parentId == pid }.forEach {
                        if (staleIds.add(it.id)) queue.add(it.id)
                    }
                }

                val staleList = allMsgs.filter { it.id in staleIds }
                convRepo.deleteMessageFiles(staleList)

                // Delete embeddings for all cascaded messages
                for (id in staleIds) {
                    convRepo.deleteEmbedding(id)
                }

                // DB delete
                convRepo.deleteMessagesByIds(staleIds.toList())

                // Update allMessages
                allMessages.update { it.filter { m -> m.id !in staleIds } }

                // Fix selectedChildren — remove entries where key or value is deleted.
                // If a deleted message was the selected branch, switch to the next sibling.
                val remainingMsgs = allMessages.value
                val newSelected = selectedChildren.value.toMutableMap()
                var changed = false
                for ((parentId, childId) in selectedChildren.value) {
                    if (parentId != null && parentId in staleIds) {
                        newSelected.remove(parentId)
                        changed = true
                        continue
                    }
                    if (childId in staleIds) {
                        val siblings = remainingMsgs.filter {
                            it.parentId == parentId &&
                                !it.id.startsWith(Constants.TOOL_MSG_PREFIX) &&
                                !it.id.startsWith(Constants.RESULT_MSG_PREFIX)
                        }.sortedBy { it.timestamp }
                        if (siblings.isNotEmpty()) {
                            newSelected[parentId] = siblings.last().id
                        } else {
                            newSelected.remove(parentId)
                        }
                        changed = true
                    }
                }
                if (changed) selectedChildren.value = newSelected
            }
        }

        return previewIds.size
    }

    // ════════════════════════════════════════════════════════════════════
    // regenerate
    // ════════════════════════════════════════════════════════════════════

    fun regenerate(messageId: String) {
        val currentId = currentConversationId.value ?: return
        val modelId = currentActiveModel.value
        val (providerName, activeKey) = requestBuilder.resolveProviderKey(modelId) ?: return

        com.newoether.agora.util.CrashReporter.note(
            "regenerate msg=${messageId.take(8)} conv=${currentId.take(8)}"
        )
        // Only stop THIS conversation's generation; other chats keep running.
        val stopFinalization = session.stopConversation(currentId, releaseSendGate = false)
        val myUiToken = session.captureUiToken(currentId)

        // Compute IDs and set placeholder on the calling thread before launching IO work,
        // so the combine function never sees streamingMessage=null while the error is in allMessages.
        val messageToRegenerate = allMessages.value.find { it.id == messageId } ?: return
        val parentId = messageToRegenerate.parentId ?: return
        val isErrorOrStopped = messageToRegenerate.status == MessageStatus.ERROR || messageToRegenerate.status == MessageStatus.STOPPED
        val isLatest = allMessages.value.none { it.parentId == messageId && !it.id.startsWith(Constants.TOOL_MSG_PREFIX) && !it.id.startsWith(Constants.RESULT_MSG_PREFIX) }
        // Error/stopped: purge and replace in-place. Normal: create new branch.
        val modelMessageId = if (isErrorOrStopped && isLatest) messageId else UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis() + 1

        // Insert placeholder into allMessages and update selectedChildren on the calling
        // thread BEFORE setting streamingMessage. This ensures the combine function sees a
        // consistent state where the new ID is both present and selected, avoiding a frame
        // where two model messages appear in the path.
        val placeholder = ChatMessage(
            id = modelMessageId, parentId = parentId, text = "", participant = Participant.MODEL,
            status = MessageStatus.SENDING, timestamp = startTime
        )
        allMessages.update { it.filter { m -> m.id != modelMessageId } + placeholder }
        val newMap = selectedChildren.value.toMutableMap()
        newMap[parentId] = modelMessageId
        val selectedAfterRegenerate = newMap.toMap()
        selectedChildren.value = selectedAfterRegenerate

        streamingMessage.value = placeholder
        isLoading.value = true
        session.markGenerating(myUiToken, currentId)

        val regenJob = session.scope.launch(start = CoroutineStart.LAZY) {
            // Register BEFORE the first suspension point. Until the job is in
            // jobsByConversation, stopConversation() cannot see it — so a Stop pressed during
            // the join below was silently ignored and this generation carried on anyway.
            // Safe to do this early: stopConversation() above already cancelled the previous
            // job, so attachJob's "cancel the one being replaced" branch is a no-op here.
            session.attachJob(currentId, coroutineContext[kotlinx.coroutines.Job]!!)
            // Wait for the cancelled generation's authoritative STOPPED write so its final
            // in-flight usage round cannot be overwritten by an older streaming snapshot.
            stopFinalization?.join()
            val myPersistId = session.beginPersistenceEpoch(currentId)
            try {
                allMessages.value.find { it.id == parentId } ?: return@launch

                if (isErrorOrStopped && isLatest) {
                    // Purge stale tool call children, thinking content, and embeddings
                    val allMsgs = allMessages.value
                    val staleIds = mutableListOf<String>()
                    val queue = mutableListOf(modelMessageId)
                    while (queue.isNotEmpty()) {
                        val pid = queue.removeAt(0)
                        allMsgs.filter { it.parentId == pid && (it.id.startsWith(Constants.TOOL_MSG_PREFIX) || it.id.startsWith(Constants.RESULT_MSG_PREFIX)) }
                            .forEach { staleIds.add(it.id); queue.add(it.id) }
                    }
                    if (staleIds.isNotEmpty()) {
                        convRepo.deleteMessagesByIds(staleIds)
                        allMessages.update { it.filter { m -> m.id !in staleIds } }
                    }
                    convRepo.deleteEmbedding(modelMessageId)
                    convRepo.upsertMessage(MessageEntity(
                        id = modelMessageId, conversationId = currentId, parentId = parentId,
                        text = "", thoughts = null, thoughtTitle = null, status = MessageStatus.SENDING, participant = Participant.MODEL, timestamp = startTime,
                        modelName = currentActiveModel.value, toolCallJson = null
                    ))
                } else {
                    // New branch — old message and its tool calls stay as a selectable branch
                    convRepo.upsertMessage(MessageEntity(
                        id = modelMessageId, conversationId = currentId, parentId = parentId,
                        text = "", thoughts = null, status = MessageStatus.SENDING, participant = Participant.MODEL, timestamp = startTime,
                        modelName = currentActiveModel.value
                    ))
                }
                onPersistSelectedChildren(currentId, selectedAfterRegenerate)
                convRepo.getConversation(currentId)?.let { conv ->
                    convRepo.upsertConversation(conv.copy(lastUpdated = System.currentTimeMillis()))
                }
                launchGeneration(
                    currentId, modelMessageId, startTime,
                    isRegenerate = true, replaceMessageId = messageId,
                    providerName, modelId, activeKey, myUiToken, myPersistId,
                    callerTag = "regenerate"
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Root coroutine on session.scope: without this, any Room / DataStore / file
                // failure here walks straight to the thread's default handler, which is
                // CrashReporter — i.e. the app kills its own process. The scope-level handler is
                // the net; this is not falling off the edge, and it keeps the failure attributable
                // to a specific entry point.
                com.newoether.agora.util.CrashReporter.note("regenerate body ${e.javaClass.simpleName}: ${e.message?.take(120)}")
                com.newoether.agora.util.DebugLog.e("AgoraVM", "regenerate body failed", e)
            } finally {
                session.loadingChange(myUiToken, currentId, false)
            }
        }
        session.attachJob(currentId, regenJob)
        regenJob.start()
    }

    // ════════════════════════════════════════════════════════════════════
    // launchGeneration
    // ════════════════════════════════════════════════════════════════════

    /**
     * Shared generation tail called by [sendMessage], [regenerate], and
     * [editMessage]: resolves system prompt + conversation settings, builds
     * [GenerationConfig]/[GenerationContext], and launches the provider stream.
     *
     * All three entry points converge here after their differing branch-setup
     * heads, eliminating copy-pasted prompt-resolution / config-building /
     * callback-wiring code.
     */
    private suspend fun launchGeneration(
        currentId: String,
        modelMessageId: String,
        startTime: Long,
        isRegenerate: Boolean,
        replaceMessageId: String?,
        providerName: String,
        modelId: String,
        activeKey: String,
        uiToken: Long,
        persistId: Long,
        callerTag: String
    ) {
        // These four suspends run on EVERY generation and each can throw — a corrupt DataStore
        // file, a memory file that vanished between exists() and readText(), a Room read on a
        // conversation deleted mid-flight. They used to sit outside the try, so those failures
        // escaped the root coroutine and killed the process instead of failing this one turn.
        try {
            val t_launch = System.currentTimeMillis()
            // One UI-state snapshot must drive settings, prompt instructions, enabled providers,
            // and direct-tool routing for the entire generation.
            val forceSnapshot = requestBuilder.captureForceSnapshot()
            val resolved = requestBuilder.buildEffectiveSystemPrompt(currentId)
            android.util.Log.d("AgoraTiming", "launchGeneration: systemPrompt took ${System.currentTimeMillis() - t_launch}ms")
            val effectiveSettings = requestBuilder.buildEffectiveConversationSettings(
                currentId,
                forceSnapshot,
            )
            android.util.Log.d("AgoraTiming", "launchGeneration: effectiveSettings took ${System.currentTimeMillis() - t_launch}ms")
            // Re-resolve the key against on-disk settings here (the suspend convergence
            // point for all entry paths). The synchronous [activeKey] resolved by the
            // callers can be blank if DataStore had not finished loading when Send was
            // tapped, which would build the request with an empty key → 401.
            // Model-bound key only — never fall across sibling keys on the same provider.
            val freshKey = settings.awaitApiKeyForModel(modelId, providerName)?.takeIf { it.isNotBlank() } ?: activeKey
            android.util.Log.d("AgoraTiming", "launchGeneration: awaitApiKey took ${System.currentTimeMillis() - t_launch}ms")
            val (config, genCtx) = requestBuilder.buildGenerationPair(
                providerName, modelId, freshKey,
                resolved.systemPrompt, resolved.userPrepend, resolved.userPostpend,
                effectiveSettings, currentId, forceSnapshot,
            )
            android.util.Log.d("AgoraTiming", "launchGeneration: buildPair took ${System.currentTimeMillis() - t_launch}ms → calling generate()")
            generationManager.generate(
                conversationId = currentId,
                modelMessageId = modelMessageId,
                startTime = startTime,
                isRegenerate = isRegenerate,
                replaceMessageId = replaceMessageId,
                modelName = currentActiveModel.value,
                config = config,
                ctx = genCtx,
                // The session's legacy last-started slot is global across conversations. Always
                // pass this generation's own coroutine so stopping chat B cannot mark chat A as
                // cancelled (and transcription observes the same correct owner).
                generationJob = coroutineContext[Job],
                callbacks = session.callbacksFor(uiToken, persistId, currentId)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e("AgoraVM", "Generation failed in $callerTag", e)
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // editMessage
    // ════════════════════════════════════════════════════════════════════

    fun editMessage(messageId: String, newText: String) {
        val currentId = currentConversationId.value ?: return
        val modelId = currentActiveModel.value
        val (providerName, activeKey) = requestBuilder.resolveProviderKey(modelId) ?: return

        val stopFinalization = session.stopConversation(currentId, releaseSendGate = false)
        val myUiToken = session.captureUiToken(currentId)
        // Set loading synchronously on the calling thread (like sendMessage/regenerate)
        // so the current conversation's generation gate disables per-message actions immediately.
        isLoading.value = true
        session.markGenerating(myUiToken, currentId)
        val editJob = session.scope.launch(start = CoroutineStart.LAZY) {
            // Same as regenerate: register before the first suspension point so Stop can
            // actually reach this job while it waits.
            session.attachJob(currentId, coroutineContext[kotlinx.coroutines.Job]!!)
            stopFinalization?.join()
            val myPersistId = session.beginPersistenceEpoch(currentId)
            try {
            val messageToEdit = allMessages.value.find { it.id == messageId } ?: return@launch
            val newUserMessageId = UUID.randomUUID().toString()
            convRepo.upsertMessage(MessageEntity(
                id = newUserMessageId, conversationId = currentId, parentId = messageToEdit.parentId,
                text = newText, thoughts = null, status = MessageStatus.SUCCESS, participant = Participant.USER, timestamp = System.currentTimeMillis()
            ))
            val newMap = selectedChildren.value.toMutableMap()
            newMap[messageToEdit.parentId] = newUserMessageId
            val selectedAfterUserEdit = newMap.toMap()
            selectedChildren.value = selectedAfterUserEdit
            onPersistSelectedChildren(currentId, selectedAfterUserEdit)
            val modelMessageId = UUID.randomUUID().toString()
            val startTime = System.currentTimeMillis() + 1
            convRepo.upsertMessage(MessageEntity(
                id = modelMessageId, conversationId = currentId, parentId = newUserMessageId,
                text = "", thoughts = null, status = MessageStatus.SENDING, participant = Participant.MODEL, timestamp = startTime,
                modelName = currentActiveModel.value
            ))
            convRepo.getConversation(currentId)?.let { conv ->
                convRepo.upsertConversation(conv.copy(lastUpdated = System.currentTimeMillis()))
            }
            // Set streamingMessage BEFORE allMessages so the combine never
            // evaluates with stale allMessages data but no streaming overlay.
            val placeholder = ChatMessage(
                id = modelMessageId, parentId = newUserMessageId, text = "", participant = Participant.MODEL,
                status = MessageStatus.SENDING, timestamp = startTime, modelName = currentActiveModel.value
            )
            session.streamUpdate(myUiToken, currentId, placeholder)
            allMessages.update { it.filter { m -> m.id != modelMessageId } + placeholder }
            val editChildren = selectedAfterUserEdit.toMutableMap()
            editChildren[newUserMessageId] = modelMessageId
            val selectedAfterModelEdit = editChildren.toMap()
            selectedChildren.value = selectedAfterModelEdit
            onPersistSelectedChildren(currentId, selectedAfterModelEdit)
            launchGeneration(
                currentId, modelMessageId, startTime,
                isRegenerate = false, replaceMessageId = null,
                providerName, modelId, activeKey, myUiToken, myPersistId,
                callerTag = "editMessage"
            )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                com.newoether.agora.util.CrashReporter.note("editMessage body ${e.javaClass.simpleName}: ${e.message?.take(120)}")
                com.newoether.agora.util.DebugLog.e("AgoraVM", "editMessage body failed", e)
            } finally {
                session.loadingChange(myUiToken, currentId, false)
            }
        }
        session.attachJob(currentId, editJob)
        editJob.start()
    }

    // ════════════════════════════════════════════════════════════════════
    // sendMessage
    // ════════════════════════════════════════════════════════════════════

    fun sendMessage(text: String, images: List<String> = emptyList(), attachments: List<SelectedAttachment> = emptyList()): Boolean {
            // While THIS conversation is already generating, enqueue instead of cancelling it.
            // Other conversations may generate in parallel — do not block them.
            val activeConv = currentConversationId.value
            if (activeConv != null && session.isGenerating(activeConv)) {
                return false // host should call enqueueQueuedMessage instead
            }
            // Per-conversation send gate (new chat uses a temporary token until id is assigned).
            val gateKey = activeConv ?: ("new:" + java.lang.System.identityHashCode(this))
            if (!session.tryAcquireSend(gateKey)) return false
            var committed = false
            var gateConversationId = gateKey
            try {
            val modelId = currentActiveModel.value
            if (modelId.isBlank()) {
                onSnackbar(application.getString(R.string.no_model_selected))
                return false
            }
            val (providerName, activeKey) = requestBuilder.resolveProviderKey(modelId) ?: return false
            if (providerName == Constants.PROVIDER_LOCAL) {
                val localModelId = modelId.substringAfter("${Constants.PROVIDER_LOCAL}:")
                val config = settings.localChatModels.value.find { it.modelId == localModelId }
                if (config == null || !java.io.File(config.localFilePath).exists()) {
                    onSnackbar(application.getString(R.string.local_model_not_found))
                    return false
                }
            }
            // Only stop an in-flight generation for THIS conversation (edit/regenerate path).
            // Fresh send on an idle conversation must NOT cancel other chats.
            val stopFinalization = if (activeConv != null && session.isGenerating(activeConv)) {
                session.stopConversation(activeConv, releaseSendGate = false)
            } else {
                activeConv?.let(session::pendingStopFinalization)
            }
            // Set loading immediately so UI shows sending state during attachment processing
            isLoading.value = true
            val provisionalId = activeConv
            // Pre-attach target for the coroutine below. Only valid when this send goes to an
            // EXISTING conversation: a new chat mints its id inside the coroutine, and attaching
            // to the previously-open conversation would let a Stop pressed there cancel a
            // generation that no longer belongs to it.
            val preAttachId = provisionalId?.takeIf { !isNewChatMode.value }
            var myUiToken = if (provisionalId != null) {
                session.captureUiToken(provisionalId).also { session.markGenerating(it, provisionalId) }
            } else {
                // New chat: token will be re-bound after id creation; mark after id exists.
                0L
            }

            committed = true
            val sendJob = session.scope.launch(start = CoroutineStart.LAZY) {
                            var finishedConversationId: String? = null
                            var currentId: String? = null
                            try {
                            // Register before the first suspension point when the target is known.
                            // buildMessagePayload below can take seconds on a large attachment, and
                            // until this job is in jobsByConversation a Stop cannot reach it — that
                            // window is also what let a "delete all chats" land between here and the
                            // first insert. A new chat re-attaches under its real id further down.
                                                        if (preAttachId != null) {
                                                            session.attachJob(preAttachId, coroutineContext[Job]!!)
                                                        }
                            // Wait for the cancelled generation's authoritative terminal write.
                            // Replacement persistence ownership begins only after that job releases it.
                                                         stopFinalization?.join()
                                                        val (allImages, attachmentMeta) = payloadBuilder.buildMessagePayload(application, images, attachments)
                                                        currentId = currentConversationId.value
                                                        val wasNewChat = isNewChatMode.value
                                                        if (wasNewChat || currentId == null) {
                                                            val newId = UUID.randomUUID().toString()
                                                            // Claim generation ownership BEFORE publishing the conversation id.
                                                            // Otherwise ChatViewModel's collectLatest stuck-cleanup races and
                                                            // stamps the new SENDING placeholder as STOPPED immediately.
                                                            myUiToken = session.captureUiToken(newId)
                                                            session.markGenerating(myUiToken, newId)
                                                            convRepo.upsertConversation(ChatEntity(id = newId, title = appContext.getString(R.string.new_chat), modelId = currentActiveModel.value, systemPromptId = pendingSystemPromptId.value))
                                                            // Bind the real id before publishing it
                                                            // so an immediate Stop cannot observe
                                                            // the id while its job registry is empty.
                                                            session.attachJob(
                                                                newId,
                                                                coroutineContext[Job]!!,
                                                            )
                                                            // Suppress the conversation-open auto-scroll BEFORE the id change triggers it.
                                                            onConversationCreatedBySend()
                                                            currentConversationId.value = newId
                                                            isNewChatMode.value = false
                                                            currentId = newId
                                                        }
                                                        val boundConversationId = currentId
                                                        finishedConversationId = boundConversationId
                                                        gateConversationId = boundConversationId
                                                        // Bind per-conversation ownership now that we have a real conversation id.
                                                        if (myUiToken == 0L || provisionalId != boundConversationId) {
                                                            myUiToken = session.captureUiToken(boundConversationId)
                                                        }
                                                        session.markGenerating(myUiToken, boundConversationId)
                                                        session.attachJob(boundConversationId, coroutineContext[Job]!!)
                                                        val myPersistId = session.beginPersistenceEpoch(boundConversationId)
                                                        // Ensure drawer spinner maps to the real conversation id (new chats get id above).
                                                        session.generatingIdChange(myUiToken, boundConversationId)
                                            // Apply pending per-conversation settings if any (from Advanced dialog in new chat)
                            val pendingSettings = pendingConversationSettings.value
                            if (pendingSettings != null) {
                                settings.setConversationSettings(boundConversationId, pendingSettings)
                                pendingConversationSettings.value = null
                            }
                            // Building the payload above can take seconds on a large attachment,
                            // and this coroutine is not in jobsByConversation yet — so a
                            // "delete all chats" landing in that window leaves us about to insert
                            // a message whose conversation row no longer exists. That is an
                            // immediate FOREIGN KEY violation from Room, which Room rethrows.
                            // Bail out quietly instead; the user already asked for this to be gone.
                            if (convRepo.getConversation(boundConversationId) == null) {
                                com.newoether.agora.util.CrashReporter.note(
                                    "sendMessage aborted: conversation ${boundConversationId.take(8)} vanished"
                                )
                                return@launch
                            }
                            val currentPath = messages.value
                            val lastMessageId = currentPath.lastOrNull()?.id
                            val userMessageId = UUID.randomUUID().toString()
                            convRepo.upsertMessage(MessageEntity(
                                id = userMessageId, conversationId = boundConversationId, parentId = lastMessageId,
                                text = text, images = allImages, thoughts = null, status = MessageStatus.SUCCESS, participant = Participant.USER, timestamp = System.currentTimeMillis(),
                                attachmentMeta = attachmentMeta?.let { kotlinx.serialization.json.Json.encodeToString(it) }
                            ))
                            val modelMessageId = UUID.randomUUID().toString()
                            val startTime = System.currentTimeMillis() + 1
                            convRepo.upsertMessage(MessageEntity(
                                id = modelMessageId, conversationId = boundConversationId, parentId = userMessageId,
                                text = "", thoughts = null, status = MessageStatus.SENDING, participant = Participant.MODEL, timestamp = startTime,
                                modelName = currentActiveModel.value
                            ))
                            convRepo.getConversation(boundConversationId)?.let { conv ->
                                convRepo.upsertConversation(conv.copy(lastUpdated = System.currentTimeMillis()))
                            }
                            // Set streamingMessage BEFORE allMessages, so when the combine
                            // re-evaluates on the allMessages change, streamingMessage is already
                            // visible — eliminating the single-frame gap.
                            val placeholder = ChatMessage(
                                id = modelMessageId, parentId = userMessageId, text = "", participant = Participant.MODEL,
                                status = MessageStatus.SENDING, timestamp = startTime, modelName = currentActiveModel.value
                            )
                            session.streamUpdate(myUiToken, boundConversationId, placeholder)
                            allMessages.update { it.filter { m -> m.id != modelMessageId } + placeholder }
                            val newChildren = selectedChildren.value.toMutableMap()
                            newChildren[userMessageId] = modelMessageId
                            selectedChildren.value = newChildren
                            onScrollToMessage(userMessageId)

                            launchGeneration(
                                boundConversationId, modelMessageId, startTime,
                                isRegenerate = false, replaceMessageId = null,
                                providerName, modelId, activeKey, myUiToken, myPersistId,
                                callerTag = "sendMessage"
                            )

                            val lastMsg = allMessages.value.find { it.id == modelMessageId }
                                                        if (wasNewChat && settings.titleGenerationEnabled.value && coroutineContext[Job]?.isActive == true && lastMsg?.status != MessageStatus.ERROR) {
                                                            generateTitle(boundConversationId)
                                                        }
                                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                                        throw e
                                                    } catch (e: Exception) {
                                                        com.newoether.agora.util.CrashReporter.note("sendMessage body ${e.javaClass.simpleName}: ${e.message?.take(120)}")
                                                        com.newoether.agora.util.DebugLog.e("AgoraVM", "sendMessage body failed", e)
                                                    } finally {
                                                                                                            // Token-gated: only the still-current generation clears the button, so a
                                                                                                            // cancelled/superseded coroutine can't revert the icon mid-generation.
                                                                                                            val clearId = finishedConversationId ?: gateConversationId
                                                                                                            session.loadingChange(myUiToken, clearId, false)
                                                                                                            // Per-conversation send gate must ALWAYS be freed.
                                                                                                            // New-chat starts with a provisional "new:…" key then switches to the
                                                                                                            // real conversation id — free both so the provisional lock cannot stick.
                                                                                                            session.releaseSend(gateConversationId)
                                                                                                            if (gateKey != gateConversationId) session.releaseSend(gateKey)
                                                                                                            // Only drain the queue after a normal completion. Cancellation
                                                                                                            // (user stop / regenerate / self-cancel) must not auto-fire the next item
                                                                                                            // — user stop drains explicitly in ChatViewModel.stopGeneration().
                                                                                                            if (isActive) {
                                                                                                                finishedConversationId?.let { onGenerationFinished(it) }
                                                                                                            }
                                                                                                        }
                                                     } // end launch
                                                     // Register before start so Stop/delete-all can
                                                     // cancel even before payload building or real id creation.
                                                     if (preAttachId != null) {
                                                         session.attachJob(preAttachId, sendJob)
                                                     } else {
                                                         session.attachProvisionalJob(sendJob)
                                                     }
                                                     sendJob.invokeOnCompletion {
                                                         session.releaseSend(gateKey)
                                                         if (gateConversationId != gateKey) {
                                                             session.releaseSend(gateConversationId)
                                                         }
                                                     }
                                                     sendJob.start()
        } finally {
            if (!committed) session.releaseSend(gateConversationId)
        }
            return true
        }

    // ════════════════════════════════════════════════════════════════════
    // generateTitle
    // ════════════════════════════════════════════════════════════════════

    fun generateTitle(conversationId: String) {
        viewModelScope.launch {
            onSnackbarSuspend(appContext.getString(R.string.snackbar_generating_title))
            val conversation = convRepo.getConversation(conversationId) ?: return@launch
            // Resolve the TARGET conversation's own path — not messages.value, which
            // is the currently-open conversation. Otherwise a long-press "regenerate
            // title" on a background conversation would summarize the active one.
            val entities = convRepo.getMessagesForConversationSnapshot(conversationId)
            val path = ConversationUiState.resolvePath(
                allMessages = entities.map {
                    ChatMessage(
                        id = it.id,
                        parentId = it.parentId,
                        text = it.text,
                        participant = it.participant,
                        timestamp = it.timestamp,
                        status = it.status,
                        modelName = it.modelName
                    )
                },
                streamingMsg = null,
                selectedChildren = emptyMap()
            )
            val firstUserMsg = path.firstOrNull { it.participant == Participant.USER } ?: return@launch
            val firstModelMsg = path
                .filter { it.participant == Participant.MODEL && it.text.isNotBlank() }
                .firstOrNull()

            val titleModelId = settings.titleGenerationModel.value
            val modelIdWithPrefix = if (!titleModelId.isNullOrBlank()) titleModelId else (conversation.modelId ?: firstModelMsg?.modelName ?: settings.selectedModel.value)
            val modelId = ModelId.parse(modelIdWithPrefix).modelName
            val (providerName, activeKey) = requestBuilder.resolveProviderKey(modelIdWithPrefix) ?: return@launch

            val summaryText = if (firstModelMsg != null) {
                "User: ${firstUserMsg.text}\nAssistant: ${firstModelMsg.text.take(500)}"
            } else {
                firstUserMsg.text
            }

            val titlePrompt = listOf(
                ChatMessage(
                    text = "Generate a short title (5 words maximum) for this conversation:\n\n$summaryText\n\nRespond with ONLY the title text, no quotes, no punctuation, no explanation.",
                    participant = Participant.USER,
                    status = MessageStatus.SUCCESS
                )
            )

            val provider = providerRegistry.getInstance(providerName)
            val config = ProviderConfig(
                apiKey = activeKey,
                modelId = modelId,
                systemPrompt = settings.titleGenerationPrompt.value.ifBlank { BuiltInPrompts.TITLE_GENERATION_SYSTEM },
                maxContextWindow = 1,
                thinkingEnabled = false,
                baseUrl = providerRegistry.getEffectiveBaseUrl(providerName)
            )

            var title = ""
            try {
                // Serialize with embedding to avoid dual model load OOM
                if (providerName == Constants.PROVIDER_LOCAL) {
                    LlamaEngine.modelMutex.withLock {
                        withContext(Dispatchers.IO) {
                            provider.generateResponse(titlePrompt, config).collect { event ->
                                if (event is StreamEvent.TextChunk) title += event.text
                                else if (event is StreamEvent.Error) DebugLog.e("AgoraVM", "Title generation error: ${event.message}")
                            }
                        }
                        localProvider.releaseEngine()
                    }
                } else {
                    provider.generateResponse(titlePrompt, config).collect { event ->
                        if (event is StreamEvent.TextChunk) title += event.text
                        else if (event is StreamEvent.Error) DebugLog.e("AgoraVM", "Title generation error: ${event.message}")
                    }
                }
            } catch (e: Exception) {
                DebugLog.e("AgoraVM", "Title generation failed for provider=$providerName model=$modelId", e)
                return@launch
            }

            title = title.trim().replace("\n", " ").take(60)
            if (title.isNotBlank()) {
                convRepo.getConversation(conversationId)?.let { existing ->
                    convRepo.upsertConversation(existing.copy(title = title))
                }
                onSnackbarSuspend(appContext.getString(R.string.snackbar_title_generated))
            } else {
                onSnackbarSuspend(appContext.getString(R.string.snackbar_title_error))
            }
        }
    }
}
