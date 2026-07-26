package com.newoether.agora.api.util

import com.newoether.agora.util.DebugLog
import com.newoether.agora.api.OpenAiContentPart
import com.newoether.agora.api.OpenAiImageUrl
import com.newoether.agora.api.OpenAiMessage
import com.newoether.agora.api.OpenAiRequestFunction
import com.newoether.agora.api.OpenAiRequestToolCall
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants
import java.io.File
import java.security.MessageDigest

fun buildToolCallId(toolName: String, arguments: String, prefix: String = Constants.TOOL_CALL_ID_PREFIX): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val input = "$toolName:$arguments"
    val hash = digest.digest(input.toByteArray())
    val shortHash = hash.take(8).joinToString("") { "%02x".format(it) }
    return "$prefix${toolName}_$shortHash"
}

/**
 * Some model streams / proxies emit concatenated JSON tool args
 * (`{"a":1}{"a":1}`). Keep the first complete top-level object/array so
 * local tool execution and provider re-serialization stay valid.
 */
fun normalizeToolArguments(raw: String?): String {
    val s = raw?.trim().orEmpty()
    if (s.isEmpty()) return "{}"
    val start = s.indexOfFirst { it == '{' || it == '[' }
    if (start < 0) return s
    var depth = 0
    var inString = false
    var escape = false
    for (i in start until s.length) {
        val c = s[i]
        if (inString) {
            when {
                escape -> escape = false
                c.code == 92 -> escape = true
                c == '"' -> inString = false
            }
            continue
        }
        when (c) {
            '"' -> inString = true
            '{', '[' -> depth++
            '}', ']' -> {
                depth--
                if (depth == 0) return s.substring(start, i + 1)
            }
        }
    }
    return s.substring(start)
}


fun encodeImageToBase64(imagePath: String): Pair<String, String>? {
    return try {
        val file = File(imagePath)
        if (!file.exists()) return null
        val bytes = file.readBytes()
        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val mimeType = if (imagePath.endsWith(".png", ignoreCase = true)) "image/png" else "image/jpeg"
        mimeType to base64
    } catch (e: Exception) {
        DebugLog.e("AgoraAPI", "Failed to encode image: $imagePath", e)
        null
    }
}

fun convertToOpenAiMessages(
    messages: List<ChatMessage>,
    systemPrompt: String? = null,
    includeImages: Boolean = true
): List<OpenAiMessage> {
    val apiMessages = mutableListOf<OpenAiMessage>()

    if (!systemPrompt.isNullOrBlank()) {
        apiMessages.add(
            OpenAiMessage(
                role = "system",
                content = listOf(OpenAiContentPart(type = "text", text = systemPrompt))
            )
        )
    }

    apiMessages.addAll(messages.flatMap { msg ->
            val entries = mutableListOf<OpenAiMessage>()

            // tool_ messages: assistant turn with tool_calls only
                    // (tool results come from the following result_ messages)
                    if (msg.id.startsWith(Constants.TOOL_MSG_PREFIX)) {
                        val toolSegs = msg.segments?.filter { it.type == "tool" }
                        val thoughtContent = msg.segments?.lastOrNull { it.type == "thought" }?.content
                        if (!toolSegs.isNullOrEmpty()) {
                            val toolCalls = toolSegs.map { seg ->
                                val args = normalizeToolArguments(seg.toolArgs)
                                val tid = seg.toolCallId?.takeIf { it.isNotBlank() }
                                    ?: buildToolCallId(seg.toolName ?: "", args)
                                OpenAiRequestToolCall(
                                    id = tid,
                                    function = OpenAiRequestFunction(name = seg.toolName ?: "", arguments = args)
                                )
                            }
                            // Prefer real pre-tool assistant text when present; otherwise omit content.
                            // A synthetic " " confuses some proxies into finishing without a follow-up.
                            val preToolText = msg.text.takeIf { it.isNotBlank() }
                                ?: msg.segments?.filter { it.type == "answer" }
                                    ?.joinToString("") { it.content }
                                    ?.takeIf { it.isNotBlank() }
                            entries.add(OpenAiMessage(
                                role = "assistant",
                                content = if (preToolText != null) {
                                    listOf(OpenAiContentPart(type = "text", text = preToolText))
                                } else {
                                    // Keep a non-null content list for serializers that require it, but
                                    // use empty string rather than a space token that models echo.
                                    listOf(OpenAiContentPart(type = "text", text = ""))
                                },
                                toolCalls = toolCalls,
                                reasoningContent = thoughtContent?.ifEmpty { null }
                            ))
                        } else if (msg.toolCall != null) {
                            val tc = msg.toolCall!!
                            val args = normalizeToolArguments(tc.arguments)
                            val toolId = tc.toolCallId?.takeIf { it.isNotBlank() }
                                ?: buildToolCallId(tc.toolName, args)
                            entries.add(OpenAiMessage(
                                role = "assistant",
                                content = listOf(OpenAiContentPart(type = "text", text = "")),
                                toolCalls = listOf(OpenAiRequestToolCall(
                                    id = toolId,
                                    function = OpenAiRequestFunction(name = tc.toolName, arguments = args)
                                )),
                                reasoningContent = thoughtContent?.ifEmpty { null }
                            ))
                        }
                        return@flatMap entries
                    }

            // result_ messages carry the tool result(s)
            if (msg.id.startsWith(Constants.RESULT_MSG_PREFIX)) {
                val toolSegs = msg.segments?.filter { it.type == "tool" }
                if (!toolSegs.isNullOrEmpty()) {
                    for (seg in toolSegs) {
                        val args = normalizeToolArguments(seg.toolArgs)
                        val toolId = seg.toolCallId?.takeIf { it.isNotBlank() }
                            ?: buildToolCallId(seg.toolName ?: "", args)
                        entries.add(OpenAiMessage(
                            role = "tool",
                            content = listOf(OpenAiContentPart(type = "text", text = seg.toolResult ?: "")),
                            toolCallId = toolId
                        ))
                    }
                } else if (msg.toolCall != null) {
                    val tc = msg.toolCall!!
                    val args = normalizeToolArguments(tc.arguments)
                    val toolId = tc.toolCallId?.takeIf { it.isNotBlank() }
                        ?: buildToolCallId(tc.toolName, args)
                    entries.add(OpenAiMessage(
                        role = "tool",
                        content = listOf(OpenAiContentPart(type = "text", text = tc.result)),
                        toolCallId = toolId
                    ))
                }
                return@flatMap entries
            }

            // Normal message: text + images
            val parts = mutableListOf<OpenAiContentPart>()
            if (msg.text.isNotEmpty()) {
                parts.add(OpenAiContentPart(type = "text", text = msg.text))
            }

            if (includeImages && msg.participant == Participant.USER) {
                for (imagePath in msg.images) {
                    val encoded = encodeImageToBase64(imagePath)
                    if (encoded != null) {
                        val (mimeType, base64) = encoded
                        parts.add(
                            OpenAiContentPart(
                                type = "image_url",
                                imageUrl = OpenAiImageUrl(url = "data:$mimeType;base64,$base64")
                            )
                        )
                    }
                }
            }

            if (parts.isEmpty()) {
                parts.add(OpenAiContentPart(type = "text", text = " "))
            }

            entries.add(OpenAiMessage(
                role = if (msg.participant == Participant.USER) "user" else "assistant",
                content = parts
            ))
            entries
        })

        return apiMessages
    }

fun limitContext(messages: List<ChatMessage>, maxUserMessages: Int): List<ChatMessage> {
    val result = mutableListOf<ChatMessage>()
    var userCount = 0
    for (msg in messages.reversed()) {
        result.add(0, msg)
        val isTool = msg.id.startsWith(Constants.TOOL_MSG_PREFIX) || msg.id.startsWith(Constants.RESULT_MSG_PREFIX)
        if (!isTool) userCount++
        if (userCount >= maxUserMessages) break
    }
    return result
}
