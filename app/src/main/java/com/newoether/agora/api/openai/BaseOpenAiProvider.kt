package com.newoether.agora.api.openai

import com.newoether.agora.api.*

import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import com.newoether.agora.api.util.StreamingThinkTagParser
import com.newoether.agora.api.util.buildToolCallId
import com.newoether.agora.api.util.convertToOpenAiMessages
import com.newoether.agora.api.util.prepareMessages
import com.newoether.agora.model.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

internal fun ProviderConfig.toOpenAiToolChoice(): OpenAiToolChoice? = when (val directive = toolChoice) {
    is ToolChoiceDirective.ForcedFunction -> directive
        .takeIf { forced -> tools.orEmpty().any { it.function.name == forced.name } }
        ?.let { forced ->
            OpenAiToolChoice(function = OpenAiToolChoiceFunction(forced.name))
        }
    null -> null
}

abstract class BaseOpenAiProvider : LlmProvider {

    protected val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    // -- Override points --

    /**
     * Modify the outgoing request before serialization (e.g. add reasoning_effort, plugins).
     * The default implementation returns the request unchanged.
     */
    protected open fun customizeRequest(request: OpenAiChatRequest, config: ProviderConfig): OpenAiChatRequest = request

    /**
     * Extra HTTP headers to include in the POST to /chat/completions.
     */
    protected open fun getExtraHeaders(config: ProviderConfig): Map<String, String> = emptyMap()

    /**
     * Transform the system prompt before it is sent. Default: pass-through.
     */
    protected open fun transformSystemPrompt(prompt: String?): String? = prompt

    /**
     * Parse the delta from one SSE event and emit TextChunk / ThoughtChunk events.
     * The base class handles tool_calls accumulation, finish_reason emission, and usage
     * emission automatically.
     *
     * The default implementation covers the common OpenAI-compatible shape: a separate
     * `reasoning_content` field (gated on [ProviderConfig.thinkingEnabled]) plus `content`.
     * Providers with a different reasoning representation (e.g. OpenRouter's
     * `reasoning_details`) override this.
     */
    protected open suspend fun parseDeltaContent(
        delta: OpenAiDelta,
        config: ProviderConfig,
        thinkParser: StreamingThinkTagParser,
        emit: suspend (StreamEvent) -> Unit
    ) {
        delta.reasoningContent?.let { reasoning ->
            if (reasoning.isNotEmpty() && config.thinkingEnabled) {
                emit(StreamEvent.ThoughtChunk(reasoning))
            }
        }
        extractTextContent(delta.content)?.let { content ->
            if (content.isNotEmpty()) emit(StreamEvent.TextChunk(content))
        }
    }

    protected open val retryableStatusCodes: Set<Int> = setOf(429, 502, 503, 504)

    protected open val retryMissingV1BaseUrl: Boolean = false

    protected open fun retryDelayMillis(statusCode: Int, attempt: Int): Long = 1000L * attempt

    // -- Template method --

    override fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig
    ): Flow<StreamEvent> = flow {
        val baseUrl = config.baseUrl?.trimEnd('/') ?: defaultBaseUrl
        val endpointUrls = endpointCandidates(baseUrl, "chat/completions")

        val validatedMessages = prepareMessages(messages, config.maxContextWindow)

        val apiMessages = convertToOpenAiMessages(
            messages = validatedMessages,
            systemPrompt = transformSystemPrompt(config.systemPrompt),
            includeImages = config.includeImages
        )

        var request = OpenAiChatRequest(
            model = config.modelId,
            messages = apiMessages,
            stream = true,
            streamOptions = OpenAiStreamOptions(includeUsage = true),
            tools = config.tools,
            toolChoice = config.toOpenAiToolChoice(),
            temperature = config.temperature,
            maxTokens = config.maxTokens,
            topP = config.topP,
            frequencyPenalty = config.frequencyPenalty,
            presencePenalty = config.presencePenalty,
            serviceTier = "priority".takeIf { config.fastEnabled }
        )
        request = customizeRequest(request, config)

        val thinkParser = StreamingThinkTagParser()

        try {
            val requestBodyJson = json.encodeToString(OpenAiChatRequest.serializer(), request)
            DebugLog.d("AgoraAPI", "[$name] REQ -> ${endpointUrls.first()} | model=${config.modelId} | msgs=${apiMessages.size} | tools=${config.tools?.size ?: 0}")

            val headers = mutableMapOf("Content-Type" to "application/json")
            // Single bound key only — auth failures are not rotated across sibling keys.
            val apiKey = config.apiKey.trim()
            fun applyAuthHeader() {
                headers.remove("Authorization")
                if (apiKey.isNotBlank()) headers["Authorization"] = "Bearer $apiKey"
            }
            applyAuthHeader()
            for ((key, value) in getExtraHeaders(config)) headers[key] = value

            val maxAttempts = 3
            var attempt = 0
            var finished = false

            while (attempt < maxAttempts && !finished) {
                attempt++
                var endpointIndex = 0
                var retryScheduled = false

                while (endpointIndex < endpointUrls.size && !finished && !retryScheduled) {
                    val endpointUrl = endpointUrls[endpointIndex]
                    applyAuthHeader()
                    val tStreamPost = System.currentTimeMillis()
                    val handle = HttpClient.streamPost(endpointUrl, requestBodyJson, headers, config.streamTag)
                    DebugLog.d("AgoraTiming", "[$name] streamPost took ${System.currentTimeMillis() - tStreamPost}ms code=${handle.code}")
                    try {
                        if (handle.code == 200) {
                            consumeSuccessfulStream(handle, config, thinkParser) { emit(it) }
                            finished = true
                        } else {
                            val errorRaw = handle.errorBody ?: "Unknown error"
                            val hasV1Fallback = endpointIndex + 1 < endpointUrls.size
                            if (hasV1Fallback) {
                                DebugLog.w("AgoraAPI", "[$name] ${handle.code} at $endpointUrl, retrying with ${endpointUrls[endpointIndex + 1]}")
                                endpointIndex++
                                continue
                            }

                            DebugLog.e("AgoraAPI", "[$name] ERR ${handle.code} at $endpointUrl: $errorRaw")

                            // Auth/key errors: fail immediately (user/config issue, not software failover).
                            if (handle.code in retryableStatusCodes && attempt < maxAttempts) {
                                val retryDelayMs = retryDelayMillis(handle.code, attempt)
                                DebugLog.w("AgoraAPI", "[$name] Transient error ${handle.code} on attempt $attempt/$maxAttempts, retrying in ${retryDelayMs}ms...")
                                emit(StreamEvent.Retrying(attempt, maxAttempts))
                                delay(retryDelayMs)
                                retryScheduled = true
                            } else {
                                emit(StreamEvent.Error(buildGenerationError(handle.code, errorRaw, endpointUrls)))
                                finished = true
                            }
                        }
                    } finally {
                        handle.close()
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            emit(StreamEvent.Error(GenerationError.Timeout))
        } catch (e: ConnectException) {
            emit(StreamEvent.Error(GenerationError.Network(statusCode = 0, message = e.localizedMessage ?: "Connection refused")))
        } catch (e: UnknownHostException) {
            emit(StreamEvent.Error(GenerationError.Network(statusCode = 0, message = e.localizedMessage ?: "Unknown host")))
        } catch (e: Exception) {
            if (currentCoroutineContext().isActive) {
                emit(StreamEvent.Error(GenerationError.Unknown(e)))
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Idle budgets, aggregated from [HttpClient.STREAM_TICK_SECONDS] socket ticks. A reasoning
     * model can think for a long while before the first visible token, so the pre-content
     * budget is the more generous one.
     */
    protected open val firstTokenTimeoutMs: Long = 120_000L
    protected open val idleTimeoutMs: Long = 90_000L

    private suspend fun consumeSuccessfulStream(
        handle: HttpClient.StreamHandle,
        config: ProviderConfig,
        thinkParser: StreamingThinkTagParser,
        emit: suspend (StreamEvent) -> Unit
    ) {
        val pendingToolCalls = mutableMapOf<Int, PendingToolCall>()
        var sawToolFinishReason = false

        val startedMs = System.currentTimeMillis()
        var lastActivityMs = startedMs
        var producedContent = false
        var totalLines = 0
        var dataLines = 0
        var parseFailures = 0
        var lastFinishReason: String? = null
        var sampleUnrecognised: String? = null

        // Everything goes out through here so the empty-stream diagnosis below knows whether
        // anything ever reached the user, and so the stall watchdog has a liveness timestamp.
        suspend fun send(event: StreamEvent) {
            when (event) {
                is StreamEvent.TextChunk, is StreamEvent.ThoughtChunk,
                is StreamEvent.ToolCallRequest, is StreamEvent.ToolCallsRequest -> producedContent = true
                else -> Unit
            }
            lastActivityMs = System.currentTimeMillis()
            emit(event)
        }

        suspend fun emitPendingToolCalls() {
            if (pendingToolCalls.isEmpty()) return
            val calls = pendingToolCalls.values
                .filter { it.name.isNotBlank() }
                .map { pending ->
                    val args = pending.args.toString()
                    val id = pending.id.takeIf { it.isNotBlank() }
                        ?: buildToolCallId(pending.name, args)
                    StreamEvent.ToolCallRequest(id, pending.name, args)
                }
            pendingToolCalls.clear()
            when {
                calls.size == 1 -> send(calls.first())
                calls.size > 1 -> send(StreamEvent.ToolCallsRequest(calls))
            }
        }

        while (currentCoroutineContext().isActive) {
            val line = try {
                handle.readLine()
            } catch (e: SocketTimeoutException) {
                if (!currentCoroutineContext().isActive) break
                // A socket tick, not a deadline — decide whether the stream is merely quiet
                // or actually dead. Previously this was a bare `continue`, so a stalled
                // upstream burned the full 5-minute read timeout and then surfaced as
                // "Unknown error" instead of a timeout.
                val idleMs = System.currentTimeMillis() - lastActivityMs
                val limit = if (producedContent) idleTimeoutMs else firstTokenTimeoutMs
                if (idleMs >= limit) {
                    DebugLog.w("AgoraAPI", "[$name] stream stalled ${idleMs}ms content=$producedContent")
                    send(StreamEvent.Error(GenerationError.StreamStalled(idleMs, producedContent)))
                    return
                }
                continue
            } catch (e: java.io.IOException) {
                // call.cancel() from Stop/regenerate lands here as IOException("stream was
                // reset: CANCEL") before the coroutine cancel signal reaches isActive. Simply
                // break — the caller will see STOPPED, not an error.
                break
            } ?: break

            totalLines++
            // SSE framing: a blank line separates events, ':' opens a comment/heartbeat.
            if (line.isEmpty() || line.startsWith(":")) continue

            if (!line.startsWith("data:")) {
                // Relays that answer a stream request with an ordinary JSON body would
                // otherwise have every line skipped and end the turn as "no response".
                rescueNonSseBody(line)?.let { rescued ->
                    DebugLog.e("AgoraAPI", "[$name] non-SSE body on a 200 stream: ${line.take(200)}")
                    send(StreamEvent.Error(rescued))
                    return
                }
                if (sampleUnrecognised == null && line.isNotBlank()) sampleUnrecognised = line
                continue
            }

            // The space after "data:" is OPTIONAL in the SSE grammar. Requiring it dropped
            // every frame from relays that emit the compact `data:{...}` form.
            val jsonStr = line.removePrefix("data:").trim()
            if (jsonStr == "[DONE]") break
            if (jsonStr.isEmpty()) continue
            dataLines++

            try {
                val response = json.decodeFromString<OpenAiStreamResponse>(jsonStr)
                val choice = response.choices?.firstOrNull()

                choice?.delta?.let { delta ->
                    parseDeltaContent(delta, config, thinkParser) { send(it) }

                    delta.toolCalls?.forEach { tc ->
                        accumulateToolCall(pendingToolCalls, tc)
                    }
                }

                // Proxies that put the full assistant payload under choice.message (stream mode).
                choice?.message?.let { msg ->
                    msg.reasoningContent?.let { reasoning ->
                        if (reasoning.isNotEmpty() && config.thinkingEnabled) {
                            send(StreamEvent.ThoughtChunk(reasoning))
                        }
                    }
                    extractTextContent(msg.content)?.let { content ->
                        if (content.isNotEmpty()) send(StreamEvent.TextChunk(content))
                    }
                    msg.toolCalls?.forEach { tc ->
                        // Prefer complete message payload over partial deltas of the same call.
                        replaceOrAccumulateToolCall(pendingToolCalls, tc)
                    }
                }

                // Some OpenAI-compatible proxies emit "function_call", "tool-calls", or even
                // "stop" while still carrying complete tool_calls. Emit as soon as the stream
                // signals completion with pending tools — don't require exact "tool_calls".
                val finish = choice?.finishReason?.trim()?.lowercase().orEmpty()
                // Remember it unconditionally. This used to be read ONLY when a tool call was
                // pending, so an upstream that stopped for a stated reason (content_filter,
                // length, or a relay's own non-standard value) told us why and we threw it away.
                if (finish.isNotEmpty()) lastFinishReason = finish
                if (finish.isNotEmpty() && pendingToolCalls.isNotEmpty()) {
                    val looksLikeToolFinish = finish == "tool_calls" ||
                        finish == "function_call" ||
                        finish == "tool-calls" ||
                        finish == "tool_call" ||
                        // Proxies that finish with stop/null-ish after streaming tool args.
                        (finish == "stop" && pendingToolCalls.values.any { it.name.isNotBlank() })
                    if (looksLikeToolFinish) {
                        sawToolFinishReason = true
                        emitPendingToolCalls()
                    }
                }

                response.usage?.let { usage ->
                    send(usage.toUsageUpdate())
                }
            } catch (e: Exception) {
                parseFailures++
                if (sampleUnrecognised == null) sampleUnrecognised = line
                DebugLog.e("AgoraAPI", "Parse error: ${e.message}", e)
            }
        }

        thinkParser.flush(
            onText = { send(StreamEvent.TextChunk(it)) },
            onThought = { send(StreamEvent.ThoughtChunk(it)) }
        )

        // Stream ended without a recognized tool finish_reason (common with some proxies
        // that only send tool_calls deltas then [DONE]). Flush any accumulated tool calls so
        // GenerationManager can still run the multi-tool loop and continue the reply.
        if (pendingToolCalls.isNotEmpty()) {
            if (!sawToolFinishReason) {
                DebugLog.w(
                    "AgoraAPI",
                    "[$name] Stream ended with ${pendingToolCalls.size} pending tool call(s) and no tool finish_reason; flushing"
                )
            }
            emitPendingToolCalls()
        }

        if (!currentCoroutineContext().isActive) {
            throw CancellationException("Stream cancelled")
        }

        // HTTP 200, stream closed, nothing to show. Report what the reader actually saw
        // rather than letting GenerationManager fall back to a generic "no response" string
        // with no error attached — that made every upstream failure look identical.
        if (!producedContent) {
            send(
                StreamEvent.Error(
                    GenerationError.EmptyStream(
                        bytesRead = handle.bytesRead,
                        totalLines = totalLines,
                        dataLines = dataLines,
                        parseFailures = parseFailures,
                        finishReason = lastFinishReason,
                        elapsedMs = System.currentTimeMillis() - startedMs,
                        sampleLine = sampleUnrecognised,
                    )
                )
            )
        }
    }

    /**
     * Some relays answer `stream: true` with an ordinary JSON body — usually an error object —
     * while still returning HTTP 200. Without this the SSE reader skips every line and the turn
     * ends as "no response at all", hiding a message the upstream actually sent.
     */
    private fun rescueNonSseBody(line: String): GenerationError? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("{")) return null
        val obj = try {
            json.parseToJsonElement(trimmed) as? JsonObject ?: return null
        } catch (_: Exception) {
            return null
        }
        val text = { key: String, from: JsonObject -> (from[key] as? JsonPrimitive)?.content }
        return when (val error = obj["error"]) {
            is JsonObject -> GenerationError.Api(
                code = text("code", error),
                type = text("type", error),
                message = text("message", error) ?: error.toString(),
            )
            is JsonPrimitive -> GenerationError.Api(code = null, type = null, message = error.content)
            else -> null
        }
    }

    private fun accumulateToolCall(
        pendingToolCalls: MutableMap<Int, PendingToolCall>,
        tc: OpenAiToolCall,
    ) {
        val existing = if (tc.id != null) pendingToolCalls.values.firstOrNull { it.id == tc.id } else null
        val pending = if (existing != null) existing else {
            val idx = tc.index ?: pendingToolCalls.size
            pendingToolCalls.getOrPut(idx) { PendingToolCall() }
        }
        if (tc.id != null) pending.id = tc.id
        tc.function?.name?.let { if (it.isNotEmpty()) pending.name = it }
        tc.function?.arguments?.let {
            pending.args.append(if (it is JsonPrimitive) it.content else it.toString())
        }
    }

    /**
     * For complete `choice.message.tool_calls` payloads: overwrite args for that id/index
     * instead of appending on top of partial deltas (avoids duplicated JSON args).
     */
    private fun replaceOrAccumulateToolCall(
        pendingToolCalls: MutableMap<Int, PendingToolCall>,
        tc: OpenAiToolCall,
    ) {
        val existing = if (tc.id != null) pendingToolCalls.values.firstOrNull { it.id == tc.id } else null
        val pending = if (existing != null) existing else {
            val idx = tc.index ?: pendingToolCalls.size
            pendingToolCalls.getOrPut(idx) { PendingToolCall() }
        }
        if (tc.id != null) pending.id = tc.id
        tc.function?.name?.let { if (it.isNotEmpty()) pending.name = it }
        tc.function?.arguments?.let {
            val raw = if (it is JsonPrimitive) it.content else it.toString()
            if (raw.isNotEmpty()) {
                pending.args.clear()
                pending.args.append(raw)
            }
        }
    }

    /**
     * Normalize OpenAI-compatible stream content fields.
     * Accepts plain strings and multi-part content arrays; returns null for non-text.
     */
    protected fun extractTextContent(content: JsonElement?): String? {
        if (content == null) return null
        return when (content) {
            is JsonPrimitive -> content.content
            is JsonArray -> content.mapNotNull { part ->
                when (part) {
                    is JsonPrimitive -> part.content
                    is JsonObject -> (part["text"] as? JsonPrimitive)?.content
                        ?: (part["content"] as? JsonPrimitive)?.content
                    else -> null
                }
            }.joinToString("").ifEmpty { null }
            is JsonObject -> (content["text"] as? JsonPrimitive)?.content
                ?: (content["content"] as? JsonPrimitive)?.content
            else -> null
        }
    }

    private fun endpointCandidates(baseUrl: String, path: String): List<String> {
        val normalizedBaseUrl = baseUrl.trimEnd('/')
        val cleanPath = path.trimStart('/')
        val primary = "$normalizedBaseUrl/$cleanPath"
        if (!retryMissingV1BaseUrl || normalizedBaseUrl.isBlank() ||
            BaseUrlResolver.hasVersionSegment(normalizedBaseUrl)
        ) {
            return listOf(primary)
        }
        return listOf(primary, "$normalizedBaseUrl/v1/$cleanPath")
    }

    private fun buildGenerationError(
        statusCode: Int,
        errorRaw: String,
        endpointUrls: List<String>
    ): GenerationError {
        val endpointHint = if (statusCode == 404 && endpointUrls.size > 1) {
            "\nTried ${endpointUrls.joinToString(" and ")}. OpenAI-compatible servers often require a /v1 Base URL."
        } else {
            ""
        }
        return try {
            val errorJson = json.decodeFromString<OpenAiErrorResponse>(errorRaw)
            GenerationError.Api(
                code = errorJson.error.code ?: statusCode.toString(),
                type = errorJson.error.type,
                message = errorJson.error.message + endpointHint
            )
        } catch (_: Exception) {
            GenerationError.Network(statusCode = statusCode, message = errorRaw + endpointHint)
        }
    }

    private fun authHeaders(apiKey: String): Map<String, String> =
        if (apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $apiKey")

    override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> =
        fetchModelCatalog(apiKey, baseUrl).map { it.id }

    override suspend fun fetchModelCatalog(apiKey: String, baseUrl: String?): List<FetchedModel> = withContext(Dispatchers.IO) {
        try {
            val effectiveBaseUrl = baseUrl?.trimEnd('/') ?: defaultBaseUrl
            val endpointUrls = endpointCandidates(effectiveBaseUrl, "models")
            val headers = authHeaders(apiKey)
            var lastParseError: Exception? = null

            for ((index, endpointUrl) in endpointUrls.withIndex()) {
                val responseText = HttpClient.fetchModelsCancellable(
                    endpointUrl,
                    headers,
                    timeoutMs = Constants.MODEL_FETCH_TIMEOUT_MS,
                )
                if (responseText == null) {
                    if (index < endpointUrls.lastIndex) {
                        DebugLog.w("AgoraAPI", "Failed to fetch $name models from $endpointUrl; retrying ${endpointUrls[index + 1]}")
                    }
                    continue
                }

                try {
                    val root = json.parseToJsonElement(responseText) as? JsonObject
                        ?: error("Model catalog root is not an object")
                    val data = root["data"] as? JsonArray
                        ?: error("Model catalog has no data array")
                    return@withContext data.mapNotNull { element ->
                        val model = element as? JsonObject ?: return@mapNotNull null
                        val id = (model["id"] as? JsonPrimitive)?.content
                            ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        FetchedModel(id = id, fast = explicitFastSupport(model))
                    }.sortedBy { it.id }
                } catch (e: Exception) {
                    lastParseError = e
                    if (index < endpointUrls.lastIndex) {
                        DebugLog.w("AgoraAPI", "Failed to parse $name models from $endpointUrl; retrying ${endpointUrls[index + 1]}")
                    }
                }
            }

            if (lastParseError != null) {
                DebugLog.e("AgoraAPI", "Failed to parse $name models", lastParseError)
            } else {
                DebugLog.e("AgoraAPI", "Failed to fetch $name models: empty response")
            }
            emptyList()
        } catch (e: Exception) {
            DebugLog.e("AgoraAPI", "Failed to fetch $name models", e)
            emptyList()
        }
    }
}
