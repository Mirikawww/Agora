package com.newoether.agora.api.gemini

import com.newoether.agora.api.*

import com.newoether.agora.util.DebugLog
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.ThinkingLevels
import com.newoether.agora.api.util.prepareMessages
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.UUID

@Serializable
internal data class ApiGenerateContentRequest(
    val contents: List<ApiRequestContent>,
    @SerialName("system_instruction") val systemInstruction: ApiRequestContent? = null,
    val tools: List<ApiTool>? = null,
    @SerialName("toolConfig") val toolConfig: ApiToolConfig? = null,
    @SerialName("generationConfig") val generationConfig: ApiGenerationConfig? = null,
    @SerialName("service_tier") val serviceTier: String? = null,
)

@Serializable
internal data class ApiToolConfig(
    @SerialName("includeServerSideToolInvocations") val includeServerSideToolInvocations: Boolean = false
)

@Serializable
internal data class ApiGenerationConfig(
    @SerialName("thinkingConfig") val thinkingConfig: ApiThinkingConfig? = null,
    val temperature: Float? = null,
    @SerialName("maxOutputTokens") val maxOutputTokens: Int? = null,
    @SerialName("topP") val topP: Float? = null,
    @SerialName("frequencyPenalty") val frequencyPenalty: Float? = null,
    @SerialName("presencePenalty") val presencePenalty: Float? = null
)

@Serializable
internal data class ApiThinkingConfig(
    @SerialName("includeThoughts") val includeThoughts: Boolean,
    @SerialName("thinkingLevel") val thinkingLevel: String? = null,
    @SerialName("thinkingBudget") val thinkingBudget: Int? = null
)

@Serializable
internal data class ApiTool(
    @SerialName("code_execution") val codeExecution: JsonObject? = null,
    @SerialName("google_search") val googleSearch: JsonObject? = null,
    @SerialName("function_declarations") val functionDeclarations: List<GeminiFunctionDeclaration>? = null
)

@Serializable
internal data class GeminiFunctionDeclaration(
    val name: String,
    val description: String,
    val parameters: JsonObject? = null,
    @SerialName("parametersJsonSchema") val parametersJsonSchema: JsonObject? = null,
)

/**
 * Gemini's `parameters` field accepts its OpenAPI Schema subset. Exact MCP schemas can contain
 * JSON-Schema-only constructs such as $defs/$ref/oneOf and must use the mutually exclusive
 * `parametersJsonSchema` field or the API may reject an otherwise valid connector tool.
 */
internal fun ToolDefinition.toGeminiFunctionDeclaration(): GeminiFunctionDeclaration {
    val schema = function.parameters.asJsonObject()
    val isExactJsonSchema = function.parameters.rawSchema != null
    return GeminiFunctionDeclaration(
        name = function.name,
        description = function.description,
        parameters = schema.takeUnless { isExactJsonSchema },
        parametersJsonSchema = schema.takeIf { isExactJsonSchema },
    )
}

@Serializable
internal data class ApiRequestContent(val role: String? = null, val parts: List<ApiRequestPart>)

@Serializable
internal data class ApiInlineData(val mimeType: String, val data: String)

@Serializable
internal data class ApiRequestPart(
    val text: String? = null,
    val inlineData: ApiInlineData? = null,
    val thought: String? = null,
    @SerialName("thoughtSignature") val thoughtSignature: String? = null,
    @SerialName("functionCall") val functionCall: GeminiFunctionCall? = null,
    @SerialName("functionResponse") val functionResponse: GeminiFunctionResponse? = null
)

@Serializable
internal data class GeminiFunctionResponse(
    val name: String,
    val response: JsonObject
)

@Serializable
internal data class ApiResponseContent(val role: String? = null, val parts: List<ApiResponsePart>)

@Serializable
internal data class ApiResponsePart(
    val text: String? = null,
    val thought: JsonElement? = null,
    @SerialName("thoughtSignature") val thoughtSignature: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("executable_code") val executableCode: ApiExecutableCode? = null,
    @SerialName("code_execution_result") val codeExecutionResult: ApiCodeExecutionResult? = null,
    @SerialName("functionCall") val functionCall: GeminiFunctionCall? = null
)

@Serializable
internal data class GeminiFunctionCall(
    val name: String,
    val args: JsonObject? = null,
    @SerialName("thought_signature") val thoughtSignature: String? = null
)

@Serializable
internal data class ApiExecutableCode(val language: String, val code: String)

@Serializable
internal data class ApiCodeExecutionResult(val outcome: String, val output: String)

@Serializable
internal data class ApiStreamResponse(
    val candidates: List<ApiCandidate>? = null,
    @SerialName("usageMetadata") val usageMetadata: ApiUsageMetadata? = null
)

@Serializable
internal data class ApiCandidate(val content: ApiResponseContent? = null)

@Serializable
internal data class ApiUsageMetadata(
    val totalTokenCount: Int? = null,
    val promptTokenCount: Int? = null,
    val candidatesTokenCount: Int? = null,
    val cachedContentTokenCount: Int? = null,
    val toolUsePromptTokenCount: Int? = null,
    val thoughtsTokenCount: Int? = null
)

internal fun ApiUsageMetadata.toUsageUpdate(): StreamEvent.UsageUpdate {
    val prompt = (promptTokenCount ?: 0) + (toolUsePromptTokenCount ?: 0)
    val thoughts = thoughtsTokenCount ?: 0
    val completion = (candidatesTokenCount ?: 0) + thoughts
    return StreamEvent.UsageUpdate(
        tokenCount = totalTokenCount ?: (prompt + completion),
        thoughtsTokenCount = thoughts,
        promptTokens = prompt,
        cachedPromptTokens = cachedContentTokenCount ?: 0,
        cacheTelemetryAvailable = cachedContentTokenCount != null,
        completionTokens = completion,
    )
}

@Serializable
internal data class ApiErrorResponse(val error: ApiError)

@Serializable
internal data class ApiError(val code: Int? = null, val message: String? = null, val status: String? = null)

@Serializable
internal data class ModelListResponse(val models: List<ModelInfo>)

@Serializable
internal data class ModelInfo(val name: String, val displayName: String, val supportedGenerationMethods: List<String>)

class GeminiProvider : LlmProvider {
    override val name: String = Constants.PROVIDER_GOOGLE
    override val defaultBaseUrl: String = "https://generativelanguage.googleapis.com/v1beta"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    override fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig
    ): Flow<StreamEvent> = flow {
        val baseUrl = config.baseUrl?.trimEnd('/') ?: defaultBaseUrl
        val cleanModelName = config.modelId.removePrefix("models/")
        
        // Context windowing
        val validatedPath = prepareMessages(messages, config.maxContextWindow)

        val apiContents = validatedPath.flatMap { msg ->
            val entries = mutableListOf<ApiRequestContent>()

            // tool_ messages: model turn with functionCall(s)
            // Note: Gemini 3 requires thought to be boolean in requests, so we omit thought strings
            // and only include thoughtSignature on the functionCall part
            if (msg.id.startsWith(Constants.TOOL_MSG_PREFIX)) {
                val toolSegs = msg.segments?.filter { it.type == "tool" }
                val sig = msg.segments?.lastOrNull { !it.signature.isNullOrBlank() }?.signature
                if (!toolSegs.isNullOrEmpty()) {
                    val parts = toolSegs.map { seg ->
                        val args = try {
                            json.parseToJsonElement(seg.toolArgs ?: "{}") as? JsonObject
                        } catch (_: Exception) { JsonObject(emptyMap()) }
                        ApiRequestPart(
                            functionCall = GeminiFunctionCall(
                                name = seg.toolName ?: "", args = args ?: JsonObject(emptyMap())
                            ),
                            thoughtSignature = sig
                        )
                    }
                    entries.add(ApiRequestContent(role = "model", parts = parts))
                } else if (msg.toolCall != null) {
                    val args = try {
                        json.parseToJsonElement(msg.toolCall!!.arguments) as? JsonObject
                    } catch (_: Exception) { JsonObject(emptyMap()) }
                    entries.add(ApiRequestContent(
                        role = "model",
                        parts = listOf(ApiRequestPart(
                            functionCall = GeminiFunctionCall(
                                name = msg.toolCall!!.toolName, args = args ?: JsonObject(emptyMap())
                            ),
                            thoughtSignature = sig
                        ))
                    ))
                }
                return@flatMap entries
            }

            // result_ messages carry the function response(s)
            if (msg.id.startsWith(Constants.RESULT_MSG_PREFIX)) {
                val toolSegs = msg.segments?.filter { it.type == "tool" }
                if (!toolSegs.isNullOrEmpty()) {
                    val parts = toolSegs.map { seg ->
                        val response = buildGeminiFunctionResponse(seg.toolResult ?: "{}")
                        ApiRequestPart(functionResponse = GeminiFunctionResponse(
                            name = seg.toolName ?: "",
                            response = response
                        ))
                    }
                    entries.add(ApiRequestContent(role = "user", parts = parts))
                } else if (msg.toolCall != null) {
                    val response = buildGeminiFunctionResponse(msg.toolCall!!.result)
                    entries.add(ApiRequestContent(
                        role = "user",
                        parts = listOf(ApiRequestPart(functionResponse = GeminiFunctionResponse(
                            name = msg.toolCall!!.toolName,
                            response = response
                        )))
                    ))
                }
                return@flatMap entries
            }

            // Normal message: text + images only
            val parts = mutableListOf<ApiRequestPart>()
            if (msg.text.isNotEmpty()) {
                parts.add(ApiRequestPart(text = msg.text))
            }
            if (config.includeImages && msg.participant == Participant.USER) for (imagePath in msg.images) {
                try {
                    val file = File(imagePath)
                    if (file.exists()) {
                        val bytes = file.readBytes()
                        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                        val mimeType = if (imagePath.endsWith(".png", true)) "image/png" else "image/jpeg"
                        parts.add(ApiRequestPart(inlineData = ApiInlineData(mimeType = mimeType, data = base64)))
                    }
                } catch (e: Exception) {
                    DebugLog.e("AgoraAPI", "Failed to encode image: $imagePath", e)
                }
            }
            if (parts.isEmpty()) parts.add(ApiRequestPart(text = ""))
            entries.add(ApiRequestContent(
                role = if (msg.participant == Participant.USER) "user" else "model",
                parts = parts
            ))

            entries
        }

        val systemInstruction = if (!config.systemPrompt.isNullOrBlank()) {
            ApiRequestContent(parts = listOf(ApiRequestPart(text = config.systemPrompt)))
        } else null

        val tools = mutableListOf<ApiTool>()
        if (config.codeExecutionEnabled) tools.add(ApiTool(codeExecution = JsonObject(emptyMap())))
        if (config.googleSearchEnabled) tools.add(ApiTool(googleSearch = JsonObject(emptyMap())))

        // Add memory function declarations as a separate tool entry
        val functionDeclarations = config.tools?.map(ToolDefinition::toGeminiFunctionDeclaration)
        if (!functionDeclarations.isNullOrEmpty()) {
            tools.add(ApiTool(functionDeclarations = functionDeclarations))
        }

        val thinkingConfig = if (!config.thinkingEnabled) {
            null
        } else when {
            cleanModelName.contains("gemini-3", ignoreCase = true) || cleanModelName.contains("gemini-3.5", ignoreCase = true) -> {
                ApiThinkingConfig(includeThoughts = true, thinkingLevel = ThinkingLevels.geminiLevel(config.thinkingLevel))
            }
            cleanModelName.contains("gemini-2.5", ignoreCase = true) -> {
                ApiThinkingConfig(
                    includeThoughts = true,
                    thinkingBudget = config.thinkingBudgetTokens.takeIf { config.thinkingBudgetEnabled }
                )
            }
            cleanModelName.contains("thinking-exp", ignoreCase = true) ->
                ApiThinkingConfig(includeThoughts = true)
            else -> null
        }

        val hasBuiltInTools = tools.any { it.codeExecution != null || it.googleSearch != null }
        val hasFunctionDeclarations = tools.any { it.functionDeclarations != null }
        val toolConfig = if (hasBuiltInTools && hasFunctionDeclarations) {
            ApiToolConfig(includeServerSideToolInvocations = true)
        } else null

        val hasGenParams = config.temperature != null || config.maxTokens != null || config.topP != null
                || config.frequencyPenalty != null || config.presencePenalty != null
        val genConfig = if (thinkingConfig != null || hasGenParams) ApiGenerationConfig(
            thinkingConfig = thinkingConfig,
            temperature = config.temperature,
            maxOutputTokens = config.maxTokens,
            topP = config.topP,
            frequencyPenalty = config.frequencyPenalty,
            presencePenalty = config.presencePenalty
        ) else null

        val requestBody = ApiGenerateContentRequest(
            contents = apiContents,
            systemInstruction = systemInstruction,
            tools = if (tools.isNotEmpty()) tools else null,
            toolConfig = toolConfig,
            generationConfig = genConfig,
            serviceTier = "priority".takeIf { config.fastEnabled },
        )

        try {
            // Determine if baseUrl already includes versioning
            val finalUrlString = if (baseUrl.contains("/v1") || baseUrl.contains("/v1beta")) {
                "$baseUrl/models/$cleanModelName:streamGenerateContent?alt=sse"
            } else {
                "$baseUrl/v1beta/models/$cleanModelName:streamGenerateContent?alt=sse"
            }

            val headers = mutableMapOf(
                            "Content-Type" to "application/json",
                        )
                        val apiKey = config.apiKey.trim()
                        fun applyAuthHeader() {
                            headers["x-goog-api-key"] = apiKey
                        }
                        applyAuthHeader()
                        val requestJson = json.encodeToString(ApiGenerateContentRequest.serializer(), requestBody)
                        DebugLog.d("AgoraAPI", "[Gemini] REQ → $finalUrlString | model=$cleanModelName | msgs=${apiContents.size} | thinking=${config.thinkingEnabled} | tools=${tools.size}")
                        DebugLog.d("AgoraAPI", "[Gemini] BODY: ${requestJson.take(4000)}")
                        val maxAttempts = 3
                        // 401 is NOT retryable: bound key failed → surface error to user.
                        val retryableCodes = setOf(429, 502, 503, 504)
                        var attempt = 0
                        var done = false

                        while (attempt < maxAttempts && !done) {
                            attempt++
                            applyAuthHeader()
                            val handle = HttpClient.streamPost(finalUrlString, requestJson, headers, config.streamTag)
                            try {
                            if (handle.code == 200) {
                                done = true
                                var line: String? = null
                                var currentThoughtSignature: String? = null
                    var inThoughtBlock = false
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
                        // The space after "data:" is OPTIONAL in the SSE grammar — matching on
                        // the 6-char literal dropped every frame from a server emitting the
                        // compact `data:{...}` form, leaving the turn with no content at all.
                        if (line.startsWith("data:")) {
                            val jsonStr = line.removePrefix("data:").trim()
                            if (jsonStr != "[DONE]") {
                                try {
                                    val response = json.decodeFromString<ApiStreamResponse>(jsonStr)

                                    inThoughtBlock = false
                                    response.candidates?.firstOrNull()?.content?.parts?.forEach { part ->
                                        var isPartOfThought = false

                                        part.thought?.let { thoughtElement ->
                                            if (thoughtElement is JsonPrimitive) {
                                                if (thoughtElement.isString) {
                                                    val content = thoughtElement.content
                                                    val title = Regex("\\*\\*(.*?)\\*\\*").find(content)?.groupValues?.get(1)
                                                              ?: Regex("(?m)^#+\\s*(.*)$").find(content)?.groupValues?.get(1)
                                                    emit(StreamEvent.ThoughtChunk(content, title, currentThoughtSignature))
                                                    isPartOfThought = true
                                                    inThoughtBlock = true
                                                } else if (thoughtElement.content == "true") {
                                                    isPartOfThought = true
                                                    inThoughtBlock = true
                                                }
                                            }
                                        }

                                        part.reasoningContent?.let {
                                            val title = Regex("\\*\\*(.*?)\\*\\*").find(it)?.groupValues?.get(1)
                                                      ?: Regex("(?m)^#+\\s*(.*)$").find(it)?.groupValues?.get(1)
                                            emit(StreamEvent.ThoughtChunk(it, title, currentThoughtSignature))
                                            isPartOfThought = true
                                            inThoughtBlock = true
                                        }

                                        part.thoughtSignature?.let { sig ->
                                            currentThoughtSignature = sig
                                            isPartOfThought = true
                                            inThoughtBlock = true
                                        }

                                        part.text?.let {
                                            // isPartOfThought: explicit marker on this part (thought, thoughtSignature, reasoningContent)
                                            // inThoughtBlock: carry-over from a preceding boolean {thought: true} part in an earlier event
                                            val inThought = isPartOfThought || inThoughtBlock
                                            if (inThought) {
                                                val title = Regex("\\*\\*(.*?)\\*\\*").find(it)?.groupValues?.get(1)
                                                          ?: Regex("(?m)^#+\\s*(.*)$").find(it)?.groupValues?.get(1)
                                                emit(StreamEvent.ThoughtChunk(it, title, currentThoughtSignature))
                                                inThoughtBlock = false
                                            } else {
                                                emit(StreamEvent.TextChunk(it))
                                            }
                                        }

                                        part.executableCode?.let {
                                            emit(StreamEvent.TextChunk("\n```${it.language}\n${it.code}\n```\n"))
                                        }

                                        part.codeExecutionResult?.let {
                                            emit(StreamEvent.TextChunk("\n> Output: ${it.output}\n"))
                                        }

                                        part.functionCall?.let { fc ->
                                            val argsJson = fc.args?.let { Json.encodeToString(JsonObject.serializer(), it) } ?: "{}"
                                            val sig = fc.thoughtSignature ?: currentThoughtSignature
                                            emit(StreamEvent.ToolCallRequest("call_${UUID.randomUUID()}", fc.name, argsJson, sig))
                                            inThoughtBlock = false
                                        }
                                    }
                                    response.usageMetadata?.let { metadata ->
                                        emit(metadata.toUsageUpdate())
                                    }
                                } catch (e: Exception) {
                                    DebugLog.e("AgoraAPI", "Parse error: ${e.message}", e)
                                }
                            }
                        }
                    }
                    if (!currentCoroutineContext().isActive) {
                        throw kotlinx.coroutines.CancellationException("Stream cancelled")
                    }
                } else {
                                    val errorRaw = handle.errorBody ?: "Unknown error (Code: ${handle.code})"
                                    DebugLog.e("AgoraAPI", "[Gemini] ERR ${handle.code}: $errorRaw")

                                    // Auth/key errors fail hard — no sibling-key rotation.
                                    if (handle.code in retryableCodes && attempt < maxAttempts) {
                                        DebugLog.w("AgoraAPI", "[Gemini] Transient error ${handle.code} on attempt $attempt/$maxAttempts, retrying in ${1000 * attempt}ms...")
                                        emit(StreamEvent.Retrying(attempt, maxAttempts))
                                        delay(1000L * attempt)
                                    } else {
                                        val genError = try {
                                            val errorJson = json.decodeFromString<ApiErrorResponse>(errorRaw)
                                            GenerationError.Api(
                                                code = (errorJson.error.code ?: handle.code).toString(),
                                                type = errorJson.error.status,
                                                message = errorJson.error.message ?: "No error message provided"
                                            )
                                        } catch (e: Exception) {
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

    override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> =
        fetchModelCatalog(apiKey, baseUrl).map { it.id }

    override suspend fun fetchModelCatalog(apiKey: String, baseUrl: String?): List<FetchedModel> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            val effectiveBaseUrl = baseUrl?.trimEnd('/') ?: defaultBaseUrl
            val finalUrlString = if (effectiveBaseUrl.contains("/v1") || effectiveBaseUrl.contains("/v1beta")) {
                "$effectiveBaseUrl/models"
            } else {
                "$effectiveBaseUrl/v1beta/models"
            }

            val responseText = HttpClient.fetchModels(
                finalUrlString,
                mapOf("x-goog-api-key" to apiKey)
            ) ?: run {
                DebugLog.e("AgoraAPI", "Failed to fetch Gemini models: empty response")
                return@withContext emptyList()
            }
            val json = Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(responseText) as? JsonObject
                ?: return@withContext emptyList()
            val models = root["models"] as? JsonArray ?: return@withContext emptyList()
            models.mapNotNull { element ->
                val model = element as? JsonObject ?: return@mapNotNull null
                val methods = (model["supportedGenerationMethods"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content }
                    .orEmpty()
                if ("generateContent" !in methods) return@mapNotNull null
                val id = (model["name"] as? JsonPrimitive)?.content
                    ?.removePrefix("models/")
                    ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                FetchedModel(id = id, fast = explicitFastSupport(model))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun buildGeminiFunctionResponse(result: String): JsonObject {
        return try {
            val parsed = json.parseToJsonElement(result)
            when (parsed) {
                is JsonObject -> parsed
                else -> JsonObject(mapOf("result" to parsed))
            }
        } catch (_: Exception) {
            JsonObject(mapOf("result" to JsonPrimitive(result)))
        }
    }
}
