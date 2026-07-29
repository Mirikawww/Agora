package com.newoether.agora.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class ToolCallData(
    val toolName: String,
    val arguments: String,
    val result: String,
    val signature: String? = null,
    val toolCallId: String? = null
)

@Serializable
data class MessageSegment(
    val type: String, // "answer", "thought", "tool", or "transcription"
    val content: String = "",
    val toolName: String? = null,
    val toolArgs: String? = null,
    val toolResult: String? = null,
    val toolCallId: String? = null,
    val signature: String? = null,
    val durationMs: Long? = null
)

/** Local-only, text-free diagnostics for one provider request in a multi-tool generation. */
@Serializable
data class GenerationRoundUsage(
    val roundIndex: Int,
    val tokenCount: Int = 0,
    val promptTokens: Int = 0,
    val cachedPromptTokens: Int = 0,
    val cacheTelemetryAvailable: Boolean = false,
    val providerUsageReported: Boolean = false,
    val promptUsageReported: Boolean = false,
    val completionTokens: Int = 0,
    val durationMs: Long = 0L,
    val toolExecutionMs: Long = 0L,
    val toolNames: List<String> = emptyList(),
    val originalToolResultChars: Int = 0,
    val injectedToolResultChars: Int = 0,
    val brokerActions: List<String> = emptyList(),
    val routeMode: String? = null,
    val inlineSchemaTokens: Int = 0,
    val brokerSchemaTokens: Int = 0,
    /** Full provider-envelope estimate; falls back to component sums for older saved rows. */
    val wireSchemaTokens: Int = 0,
    val wireToolCount: Int = 0,
)

object ToolCallDisplayModes {
    const val TIMELINE = "timeline"
    const val GROUPED_TIMELINE = "grouped_timeline"
    const val COMPACT = "compact"
    const val DEFAULT = GROUPED_TIMELINE

    fun normalize(value: String?): String = when (value) {
        COMPACT -> COMPACT
        GROUPED_TIMELINE -> GROUPED_TIMELINE
        TIMELINE -> TIMELINE
        else -> DEFAULT
    }
}

enum class Participant {
    USER, MODEL, ERROR
}

enum class MessageStatus {
    TRANSCRIBING, SENDING, THINKING, TOOL_CALLING, SUCCESS, STOPPED, ERROR
}

@Immutable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val parentId: String? = null,
    val text: String,
    val images: List<String> = emptyList(),
    val thoughts: String? = null,
    val thoughtTitle: String? = null,
    val tokenCount: Int = 0,
    /** Prompt input tokens for this generation. 0 = not reported. */
    val promptTokens: Int = 0,
    /** Prompt tokens served from a provider cache. Included in [promptTokens]. */
    val cachedPromptTokens: Int = 0,
    /** True only when every prompt-bearing request reported its cache-token split. */
    val cacheTelemetryAvailable: Boolean = false,
    /** Completion output tokens for this generation. 0 = not reported. */
    val completionTokens: Int = 0,
    /** Time-to-first-token in ms (client-side). 0 = not measured. */
    val ttftMs: Long = 0L,
    /** Per-request usage retained locally; contains counters and tool names, never arguments/results. */
    val roundUsage: List<GenerationRoundUsage> = emptyList(),
    val status: MessageStatus = MessageStatus.SUCCESS, // Default to SUCCESS for old messages
    val participant: Participant,
    val timestamp: Long = System.currentTimeMillis(),
    /** Wall-clock time when generation finished (model msgs). Null while streaming / for legacy rows. */
    val completedAt: Long? = null,
    val thoughtTimeMs: Long? = null,
    val modelName: String? = null,
    val toolCall: ToolCallData? = null,
    val segments: List<MessageSegment>? = null,
    val attachmentMeta: AttachmentMeta? = null,
    val retryText: String? = null
)

@Immutable
data class ChatConversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val systemPromptId: String? = null,
    val modelId: String? = null
)
