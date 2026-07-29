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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
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
    private val provisionalJobs = ConcurrentHashMap.newKeySet<Job>()
    private val sendGates = ConcurrentHashMap<String, AtomicBoolean>()
    private val uiTokens = ConcurrentHashMap<String, AtomicLong>()
    private val persistIds = ConcurrentHashMap<String, AtomicLong>()
    private val persistenceLocks = ConcurrentHashMap<String, Mutex>()
    private val stopFinalizationJobs = ConcurrentHashMap<String, Job>()

    private val genLock = Any()

    private data class StopFinalizationState(
        val conversationId: String?,
        val messages: List<ChatMessage>,
        val expectedPersistId: Long,
    )

    private fun uiTokenCounter(conversationId: String): AtomicLong =
        uiTokens.getOrPut(conversationId) { AtomicLong(0L) }

    private fun persistCounter(conversationId: String): AtomicLong =
        persistIds.getOrPut(conversationId) { AtomicLong(0L) }

    private fun sendGateFor(conversationId: String): AtomicBoolean =
        sendGates.getOrPut(conversationId) { AtomicBoolean(false) }

    private fun persistenceLockFor(conversationId: String): Mutex =
        persistenceLocks.getOrPut(conversationId) { Mutex() }

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

    /**
     * Starts a new generation persistence epoch while holding the same lock used by writers.
     * A stale writer therefore either finishes before this returns or observes the new epoch.
     */
    suspend fun beginPersistenceEpoch(conversationId: String): Long {
        val lock = persistenceLockFor(conversationId)
        lock.lock()
        return try {
            persistCounter(conversationId).incrementAndGet()
        } finally {
            lock.unlock()
        }
    }

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
            persistMessagesIfLatest = { messages ->
                persistMessagesIfLatest(conversationId, persistId, messages)
            },
        )

    private suspend fun persistMessagesIfLatest(
        conversationId: String,
        persistId: Long,
        messages: List<MessageEntity>,
    ): Boolean {
        if (messages.isEmpty()) return true
        val lock = persistenceLockFor(conversationId)
        lock.lock()
        return try {
            if (!isLatestPersist(conversationId, persistId)) return false
            if (convRepo.getConversation(conversationId) == null) return false
            convRepo.upsertMessages(messages)
            true
        } finally {
            lock.unlock()
        }
    }

    /**
     * Invalidates every older generation before a destructive/replacement mutation and keeps the
     * mutation serialized with any terminal generation write already in progress.
     */
    suspend fun <T> withInvalidatedPersistence(
        conversationId: String,
        block: suspend () -> T,
    ): T {
        val lock = persistenceLockFor(conversationId)
        lock.lock()
        return try {
            persistCounter(conversationId).incrementAndGet()
            block()
        } finally {
            lock.unlock()
        }
    }

    suspend fun <T> withAllInvalidatedPersistence(
        conversationIds: Collection<String>,
        block: suspend () -> T,
    ): T {
        val ids = conversationIds.filter(String::isNotBlank).distinct().sorted()
        val acquired = mutableListOf<Mutex>()
        try {
            for (id in ids) {
                val lock = persistenceLockFor(id)
                lock.lock()
                acquired += lock
            }
            ids.forEach { persistCounter(it).incrementAndGet() }
            return block()
        } finally {
            acquired.asReversed().forEach { it.unlock() }
        }
    }

    fun pendingStopFinalization(conversationId: String): Job? =
        stopFinalizationJobs[conversationId]?.takeUnless { it.isCompleted }

    fun stopAllConversations(releaseSendGate: Boolean = true): List<Job> {
        val activeIds = generatingConversationIds.value.toSet()
        val pendingIds = stopFinalizationJobs.keys.toSet()
        val provisional = provisionalJobs.toList()
        val jobs = buildList {
            provisional.forEach { job ->
                job.cancel()
                add(job)
            }
            activeIds.forEach { id ->
                stopConversation(id, releaseSendGate)?.let(::add)
            }
            (pendingIds - activeIds).forEach { id ->
                pendingStopFinalization(id)?.let(::add)
            }
        }.distinct()
        if (releaseSendGate) {
            sendGates.values.forEach { it.set(false) }
            sendGate.set(false)
        }
        return jobs
    }

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
        // Publish the real owner before removing provisional ownership. A Stop/stopAll racing this
        // migration therefore sees the job in at least one registry (brief double registration is
        // intentional).
        val previous = jobsByConversation.put(conversationId, job)
        jobsByConversation.entries
            .filter { (id, registered) -> id != conversationId && registered === job }
            .forEach { (id, _) -> jobsByConversation.remove(id, job) }
        provisionalJobs.remove(job)
        // Never cancel the job we are (re)attaching — double attach of the same Job used to
        // cancel the active generation mid-stream, which then drained the queue early.
        if (previous != null && previous !== job && previous.isActive) {
            previous.cancel()
        }
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

    fun attachProvisionalJob(job: Job) {
        provisionalJobs += job
        generationJob = job
        job.invokeOnCompletion {
            provisionalJobs.remove(job)
            if (generationJob === job) generationJob = null
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
        // Covers the short new-chat phase before a real conversation id is published.
        provisionalJobs.toList().forEach { it.cancel() }
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
        // Cancel the coroutine FIRST so isActive flips to false before OkHttp's
        // call.cancel() unblocks readLine() with IOException("stream was reset: CANCEL").
        // Reversed order caused the IOException to escape the read loop while isActive
        // was still true, turning a normal Stop into a visible error message.
        previousJob?.cancel()
        cancelStreamHandles(conversationId)

        var stoppedConversationId: String? = null
        val stoppedMsg = synchronized(genLock) {
            uiTokenCounter(conversationId).incrementAndGet()
            stoppedConversationId = conversationId
            clearGeneratingLocked(conversationId)
            refreshLoadingUiLocked()
            if (currentConversationId.value == conversationId) {
                val live = streamingMessage.value
                if (live?.status?.isTransientGenerationStatus() == true) {
                    live.copy(status = MessageStatus.STOPPED).also {
                        streamingMessage.value = it
                    }
                } else {
                    // The authoritative generation may already have reached SUCCESS/ERROR while
                    // the loading overlay is still clearing. Preserve that terminal state in UI;
                    // the stop finalizer likewise preserves the terminal DB row.
                    if (live != null) {
                        allMessages.update { list ->
                            list.map { message -> if (message.id == live.id) live else message }
                        }
                    }
                    streamingMessage.value = null
                    null
                }
            } else null
        }

        val fallbackStoppedMessages = mutableListOf<ChatMessage>()
        if (currentConversationId.value == conversationId) {
            if (stoppedMsg != null) {
                allMessages.update { it.map { m -> if (m.id == stoppedMsg.id) stoppedMsg else m } }
            } else {
                allMessages.update { list ->
                    list.map { m ->
                        if (
                            m.participant == Participant.MODEL &&
                            m.status.isTransientGenerationStatus()
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
        val finalizationJob = launchStopFinalization(
            state = StopFinalizationState(
                conversationId = stoppedConversationId,
                messages = stoppedMessages,
                expectedPersistId = persistCounter(conversationId).get(),
            ),
            cancelledGeneration = previousJob,
        )
        if (releaseSendGate) releaseSend(conversationId)
        // FGS teardown is owned by GenerationManager.generate() finally so every start
        // has exactly one stop, including user cancellation and regeneration.
        return finalizationJob ?: currentStopFinalizationJob(conversationId)
    }

    private fun stopInternalLegacy(releaseSendGate: Boolean): Job? {
        // No focused conversation: cancel everything.
        val ids = generatingConversationIds.value.toList()
        provisionalJobs.toList().forEach { it.cancel() }
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

    private fun currentStopFinalizationJob(conversationId: String): Job? =
        stopFinalizationJobs[conversationId]?.takeUnless { it.isCompleted }

    private fun launchStopFinalization(
        state: StopFinalizationState,
        cancelledGeneration: Job? = null,
    ): Job? {
        val conversationId = state.conversationId ?: return null
        val messages = state.messages.distinctBy { it.id }
        val priorFinalization = currentStopFinalizationJob(conversationId)
        if (messages.isEmpty() && cancelledGeneration == null) return priorFinalization

        val job = scope.launch {
            try {
                // GenerationManager normally owns the authoritative terminal write and settles the
                // in-flight usage round first. A native provider/tool that ignores cancellation
                // must not freeze regenerate/edit/delete forever, so the wait is bounded. All DB
                // writes below share a per-conversation lock+epoch; a late old generation therefore
                // cannot resurrect or overwrite a replacement after this barrier releases.
                val completedWithinBarrier = withTimeoutOrNull(STOP_FINALIZATION_WAIT_MS) {
                    priorFinalization?.join()
                    cancelledGeneration?.join()
                    true
                } == true
                val timedOut =
                    !completedWithinBarrier &&
                        (priorFinalization != null || cancelledGeneration != null)

                val lock = persistenceLockFor(conversationId)
                lock.lock()
                var retireTimedOutEpoch = false
                try {
                    if (persistCounter(conversationId).get() != state.expectedPersistId) {
                        return@launch
                    }
                    retireTimedOutEpoch = timedOut
                    val conversationExists = convRepo.getConversation(conversationId) != null
                    if (conversationExists && messages.isNotEmpty()) {
                        val terminalRows = convRepo
                            .getMessagesForConversationSnapshot(conversationId)
                            .associateBy { it.id }
                        val finishedAt = System.currentTimeMillis()
                        for (message in messages) {
                            val terminal = terminalRows[message.id]
                            if (terminal?.completedAt == null) {
                                // Fallback for cancellation before GenerationManager acquired
                                // persistence ownership (or if its terminal write failed).
                                convRepo.upsertMessage(
                                    message.toStoppedEntity(conversationId, finishedAt),
                                )
                            }
                        }
                    }
                } finally {
                    if (
                        retireTimedOutEpoch &&
                        persistCounter(conversationId).get() == state.expectedPersistId
                    ) {
                        // Retire the cancelled generation even if fallback persistence itself
                        // failed. A provider that ignores cancellation can never write after the
                        // bounded barrier releases.
                        persistCounter(conversationId).incrementAndGet()
                    }
                    lock.unlock()
                }
            } catch (e: Exception) {
                DebugLog.e("AgoraVM", "Failed to persist stopped generation", e)
            }
        }
        stopFinalizationJobs[conversationId] = job
        job.invokeOnCompletion {
            stopFinalizationJobs.remove(conversationId, job)
        }
        return job
    }

    private fun ChatMessage.toStoppedEntity(
        conversationId: String,
        finishedAt: Long,
    ): MessageEntity {
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
            promptTokens = promptTokens,
            cachedPromptTokens = cachedPromptTokens,
            cacheTelemetryAvailable = cacheTelemetryAvailable,
            completionTokens = completionTokens,
            ttftMs = ttftMs,
            roundUsageJson = roundUsage
                .takeIf { it.isNotEmpty() }
                ?.let { Json.encodeToString(it) },
            status = MessageStatus.STOPPED,
            participant = participant,
            timestamp = timestamp,
            completedAt = completedAt ?: finishedAt,
            thoughtTimeMs = thoughtTimeMs,
            modelName = modelName,
            toolCallJson = toolJson,
            attachmentMeta = attachmentMeta?.let { Json.encodeToString(it) }
        )
    }

    private fun MessageStatus.isTransientGenerationStatus(): Boolean =
        this == MessageStatus.TRANSCRIBING ||
            this == MessageStatus.SENDING ||
            this == MessageStatus.THINKING ||
            this == MessageStatus.TOOL_CALLING

    fun cancelScope() {
        scope.coroutineContext[Job]?.cancel()
        HttpClient.cancelAllActiveStreams()
    }

    private companion object {
        const val STOP_FINALIZATION_WAIT_MS = 3_000L
    }
}
