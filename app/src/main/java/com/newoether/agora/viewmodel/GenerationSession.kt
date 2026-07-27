package com.newoether.agora.viewmodel

import android.app.Application
import com.newoether.agora.api.HttpClient
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-conversation generation lifecycle.
 *
 * Conversations may generate **in parallel**. Each conversation owns:
 *  - its [Job]
 *  - its send gate
 *  - its UI token / persist id
 *
 * Shared UI ([isLoading], [streamingMessage]) only reflects the **currently viewed**
 * conversation. Background generations keep writing to Room; switching back picks them up.
 */
class GenerationSession(
    private val app: Application,
    private val convRepo: ConversationRepository,
    private val settings: SettingsRepository,
    private val isLoading: MutableStateFlow<Boolean>,
    private val streamingMessage: MutableStateFlow<ChatMessage?>,
    /** Conversation ids that currently have an in-flight generation (waiting counts). */
    private val generatingConversationIds: MutableStateFlow<Set<String>>,
    /** @deprecated Prefer [generatingConversationIds]; kept for one-call sites during migration. */
    private val generatingInConversationId: MutableStateFlow<String?>,
    private val allMessages: MutableStateFlow<List<ChatMessage>>,
    private val currentConversationId: StateFlow<String?>,
    private val onIndexMessageForRag: (messageId: String, text: String) -> Unit,
    private val onCacheMessages: (modelId: String) -> Unit,
) {
    /**
     * Root scope for every generation coroutine.
     *
     * `SupervisorJob` keeps one failing conversation from cancelling its siblings, but it does
     * **not** stop an uncaught exception from reaching the thread's default handler — which here
     * is [com.newoether.agora.util.CrashReporter], i.e. process death. The launch bodies in
     * [MessageGenerationController] carry `finally` but no `catch`, so any Room / DataStore /
     * file failure on a Stop or Retry path (a `SQLiteConstraintException` from a conversation
     * deleted mid-flight being the realistic one) killed the app outright.
     *
     * This handler is the safety net: cancellation still propagates normally, everything else is
     * recorded and the process stays alive. It is a backstop, not a licence to skip local
     * handling — a crash that lands here is still a bug worth fixing at its source.
     */
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            if (e is kotlinx.coroutines.CancellationException) return@CoroutineExceptionHandler
            com.newoether.agora.util.CrashReporter.note(
                "generation scope caught ${e.javaClass.simpleName}: ${e.message?.take(120)}"
            )
            com.newoether.agora.util.DebugLog.e("AgoraVM", "Uncaught exception in generation scope", e)
        }
    )

    /** Last-started job (legacy single-slot accessors still used by a few call sites). */
    @Volatile
    var generationJob: Job? = null

    /** Legacy global gate — prefer [tryAcquireSend]/[releaseSend] per conversation. */
    val sendGate = AtomicBoolean(false)

    private val jobsByConversation = ConcurrentHashMap<String, Job>()
    private val sendGates = ConcurrentHashMap<String, AtomicBoolean>()
    private val uiTokens = ConcurrentHashMap<String, AtomicLong>()
    private val persistIds = ConcurrentHashMap<String, AtomicLong>()

    @Volatile private var stopFinalizationJob: Job? = null
    private val genLock = Any()

    private data class StopFinalizationState(
        val conversationId: String?,
        val messages: List<ChatMessage>
    )

    private fun uiTokenCounter(conversationId: String): AtomicLong =
        uiTokens.getOrPut(conversationId) { AtomicLong(0L) }

    private fun persistCounter(conversationId: String): AtomicLong =
        persistIds.getOrPut(conversationId) { AtomicLong(0L) }

    private fun sendGateFor(conversationId: String): AtomicBoolean =
        sendGates.getOrPut(conversationId) { AtomicBoolean(false) }

    fun tryAcquireSend(conversationId: String): Boolean {
        val ok = sendGateFor(conversationId).compareAndSet(false, true)
        if (ok) sendGate.set(true)
        return ok
    }

    fun releaseSend(conversationId: String) {
        sendGateFor(conversationId).set(false)
        // Global gate free if no conversation holds a gate.
        if (sendGates.values.none { it.get() }) sendGate.set(false)
    }

    fun isGenerating(conversationId: String?): Boolean {
        if (conversationId == null) return false
        return conversationId in generatingConversationIds.value
    }

    fun captureUiToken(conversationId: String): Long =
        synchronized(genLock) { uiTokenCounter(conversationId).get() }

    fun nextPersistId(conversationId: String): Long =
        persistCounter(conversationId).incrementAndGet()

    fun isLatestPersist(conversationId: String, id: Long): Boolean =
        persistCounter(conversationId).get() == id

    fun callbacksFor(uiToken: Long, persistId: Long, conversationId: String) = GenerationCallbacks(
            onStreamUpdate = { streamUpdate(uiToken, conversationId, it) },
            // Ignore onLoadingChange(false) here: GenerationManager clears loading at the end of
            // generate(), but the outer send/regenerate/edit finally also clears it. Accepting the
            // early false while a replacement generation already holds the same conversation would
            // flip isGenerating off mid-flight (queue drain / stuck cleanup races).
            onLoadingChange = { value -> if (value) loadingChange(uiToken, conversationId, true) },
            onGeneratingIdChange = { id ->
                if (id != null) markGenerating(uiToken, id)
                // Do not clear generating from GenerationManager terminal callback — outer finally owns that.
            },
            onStreamClear = { streamClear(uiToken, conversationId) },
            isLatestPersist = { isLatestPersist(conversationId, persistId) },
        )

    /** Legacy overload used by older call sites that still pass only tokens. */
    fun callbacksFor(uiToken: Long, persistId: Long) = callbacksFor(
        uiToken = uiToken,
        persistId = persistId,
        conversationId = currentConversationId.value.orEmpty(),
    )

    /**
     * Sever this conversation's in-flight HTTP streams.
     *
     * This used to drain `streamHandlesByConversation`, a map that nothing ever wrote to —
     * `registerStreamHandle` / `unregisterStreamHandle` had zero call sites, so every invocation
     * here was a no-op that merely *looked* like Stop cancelled the connection. In reality the
     * socket lived until the read tick expired, with the upstream still generating and billing.
     * [HttpClient] now tags each stream with its conversation id, so the cancel is real.
     */
    private fun cancelStreamHandles(conversationId: String) {
        HttpClient.cancelStreamsForTag(conversationId)
    }

    fun attachJob(conversationId: String, job: Job) {
            val previous = jobsByConversation[conversationId]
            // Never cancel the job we are (re)attaching — double attach of the same Job used to
            // cancel the active generation mid-stream, which then drained the queue early.
            if (previous != null && previous !== job && previous.isActive) {
                previous.cancel()
            }
            jobsByConversation[conversationId] = job
            generationJob = job
            job.invokeOnCompletion {
                jobsByConversation.remove(conversationId, job)
                if (generationJob === job) generationJob = null
                // Only drop stream handles when this job still owns the conversation slot.
                if (jobsByConversation[conversationId] == null) {
                    cancelStreamHandles(conversationId)
                }
            }
        }

    fun streamUpdate(uiToken: Long, conversationId: String, msg: ChatMessage) {
        synchronized(genLock) {
            if (uiTokenCounter(conversationId).get() != uiToken) return
            // Only the currently viewed conversation owns the streaming overlay / in-memory list.
            if (currentConversationId.value != conversationId) return
            streamingMessage.value = msg
            allMessages.update { list ->
                val idx = list.indexOfFirst { it.id == msg.id }
                if (idx >= 0) list.toMutableList().also { it[idx] = msg }
                else list + msg
            }
        }
    }

    fun loadingChange(uiToken: Long, conversationId: String, value: Boolean) {
        synchronized(genLock) {
            if (uiTokenCounter(conversationId).get() != uiToken) return
            if (value) markGeneratingLocked(conversationId) else clearGeneratingLocked(conversationId)
            refreshLoadingUiLocked()
        }
    }

    fun generatingIdChange(uiToken: Long, id: String?) {
        val conv = id ?: return
        markGenerating(uiToken, conv)
    }

    fun markGenerating(uiToken: Long, conversationId: String) {
        synchronized(genLock) {
            if (uiTokenCounter(conversationId).get() != uiToken) return
            markGeneratingLocked(conversationId)
            refreshLoadingUiLocked()
        }
    }

    fun clearGenerating(uiToken: Long, conversationId: String) {
        synchronized(genLock) {
            if (uiTokenCounter(conversationId).get() != uiToken) return
            clearGeneratingLocked(conversationId)
            refreshLoadingUiLocked()
        }
    }

    private fun markGeneratingLocked(conversationId: String) {
        if (conversationId.isBlank()) return
        generatingConversationIds.value = generatingConversationIds.value + conversationId
        // Keep single-id flow pointing at "any" active generation for drawer fallbacks.
        generatingInConversationId.value = conversationId
    }

    private fun clearGeneratingLocked(conversationId: String) {
        val next = generatingConversationIds.value - conversationId
        generatingConversationIds.value = next
        generatingInConversationId.value = next.firstOrNull()
    }

    private fun refreshLoadingUiLocked() {
        val current = currentConversationId.value
        isLoading.value = current != null && current in generatingConversationIds.value
    }

    /** Call when the user switches conversations so the stop/send button matches the new focus. */
    fun onConversationFocused(conversationId: String?) {
        synchronized(genLock) {
            isLoading.value = conversationId != null && conversationId in generatingConversationIds.value
            if (conversationId == null || conversationId !in generatingConversationIds.value) {
                // Leaving a generating chat: drop the overlay so it doesn't bleed into the next chat.
                streamingMessage.value = null
            }
        }
    }

    fun streamClear(uiToken: Long, conversationId: String) {
        synchronized(genLock) {
            if (uiTokenCounter(conversationId).get() != uiToken) return
            if (currentConversationId.value != conversationId) return
            val msg = streamingMessage.value
            if (msg?.status != MessageStatus.STOPPED) {
                if (msg != null) {
                    allMessages.update { it.map { m -> if (m.id == msg.id) msg else m } }
                }
                streamingMessage.value = null
            }
        }
        val id = settings.activeEmbeddingModelId.value
        if (id.isNotEmpty()) onCacheMessages(id)
    }

    fun stop() {
        val id = currentConversationId.value
        if (id != null) stopConversation(id, releaseSendGate = true)
        else stopInternalLegacy(releaseSendGate = true)
    }

    fun stopForReplacement(): Job? {
        val id = currentConversationId.value ?: return stopInternalLegacy(releaseSendGate = false)
        return stopConversation(id, releaseSendGate = false)
    }

    fun stopConversation(conversationId: String, releaseSendGate: Boolean = true): Job? {
        val previousJob = jobsByConversation[conversationId]
        cancelStreamHandles(conversationId)
        previousJob?.cancel()

        var stoppedConversationId: String? = null
        val stoppedMsg = synchronized(genLock) {
            uiTokenCounter(conversationId).incrementAndGet()
            stoppedConversationId = conversationId
            clearGeneratingLocked(conversationId)
            refreshLoadingUiLocked()
            if (currentConversationId.value == conversationId) {
                val s = streamingMessage.value?.copy(status = MessageStatus.STOPPED)
                streamingMessage.value = s
                s
            } else null
        }

        val fallbackStoppedMessages = mutableListOf<ChatMessage>()
        if (currentConversationId.value == conversationId) {
            if (stoppedMsg != null) {
                allMessages.update { it.map { m -> if (m.id == stoppedMsg.id) stoppedMsg else m } }
            } else {
                allMessages.update { list ->
                    list.map { m ->
                        if (m.participant == Participant.MODEL &&
                            (m.status == MessageStatus.SENDING || m.status == MessageStatus.THINKING ||
                                m.status == MessageStatus.TOOL_CALLING || m.status == MessageStatus.TRANSCRIBING)
                        ) {
                            val stopped = m.copy(status = MessageStatus.STOPPED)
                            fallbackStoppedMessages.add(stopped)
                            stopped
                        } else m
                    }
                }
            }
        }
        val stoppedMessages = stoppedMsg?.let { listOf(it) } ?: fallbackStoppedMessages
        val finalizationJob = launchStopFinalization(StopFinalizationState(stoppedConversationId, stoppedMessages))
        if (releaseSendGate) releaseSend(conversationId)
        // FGS teardown is owned by GenerationManager.generate() finally so every start
        // has exactly one stop, including user cancellation and regeneration.
        return finalizationJob ?: currentStopFinalizationJob()
    }

    private fun stopInternalLegacy(releaseSendGate: Boolean): Job? {
        // No focused conversation: cancel everything.
        val ids = generatingConversationIds.value.toList()
        var last: Job? = null
        if (ids.isEmpty()) {
            generationJob?.cancel()
            HttpClient.cancelAllActiveStreams()
            synchronized(genLock) {
                isLoading.value = false
                streamingMessage.value = null
                generatingInConversationId.value = null
                generatingConversationIds.value = emptySet()
            }
            if (releaseSendGate) sendGate.set(false)
            return null
        }
        ids.forEach { last = stopConversation(it, releaseSendGate) }
        return last
    }

    private fun currentStopFinalizationJob(): Job? {
        return synchronized(genLock) { stopFinalizationJob?.takeUnless { it.isCompleted } }
    }

    private fun launchStopFinalization(state: StopFinalizationState): Job? {
        val conversationId = state.conversationId ?: return currentStopFinalizationJob()
        val messages = state.messages.distinctBy { it.id }
        if (messages.isEmpty()) return currentStopFinalizationJob()

        val job = scope.launch {
            try {
                val conversationExists = convRepo.getConversation(conversationId) != null
                if (!conversationExists) return@launch
                for (message in messages) {
                    convRepo.upsertMessage(message.toStoppedEntity(conversationId))
                    if (message.text.isNotBlank() && settings.autoCacheEnabled.value &&
                        (settings.modelSearchMethod.value == Constants.SEARCH_METHOD_RAG ||
                            settings.manualSearchMethod.value == Constants.SEARCH_METHOD_RAG)
                    ) {
                        onIndexMessageForRag(message.id, message.text)
                    }
                }
            } catch (e: Exception) {
                DebugLog.e("AgoraVM", "Failed to persist stopped generation", e)
            }
        }
        synchronized(genLock) { stopFinalizationJob = job }
        return job
    }

    private fun ChatMessage.toStoppedEntity(conversationId: String): MessageEntity {
        val toolJson = segments?.let { Json.encodeToString(it) } ?: toolCall?.let {
            Json.encodeToString(
                listOf(
                    MessageSegment(
                        type = "tool",
                        toolName = it.toolName,
                        toolArgs = it.arguments,
                        toolResult = it.result,
                        signature = it.signature,
                        toolCallId = it.toolCallId
                    )
                )
            )
        }
        return MessageEntity(
            id = id,
            conversationId = conversationId,
            parentId = parentId,
            text = text,
            images = images,
            thoughts = thoughts,
            thoughtTitle = thoughtTitle,
            tokenCount = tokenCount,
            status = MessageStatus.STOPPED,
            participant = participant,
            timestamp = timestamp,
            thoughtTimeMs = thoughtTimeMs,
            modelName = modelName,
            toolCallJson = toolJson,
            attachmentMeta = attachmentMeta?.let { Json.encodeToString(it) }
        )
    }

    fun cancelScope() {
        scope.coroutineContext[Job]?.cancel()
        HttpClient.cancelAllActiveStreams()
    }
}
