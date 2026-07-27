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
    /** Prompt (input/upload) tokens for this round. 0 = not reported. */
    val promptTokens: Int = 0,
    /** Completion (output/download) tokens for this round. 0 = not reported. */
    val completionTokens: Int = 0,
    /** Time-to-first-token in ms (client-side). 0 = not measured. */
    val ttftMs: Long = 0L,
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
