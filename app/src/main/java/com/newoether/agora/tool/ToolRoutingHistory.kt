package com.newoether.agora.tool

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Derives deterministic routing heat from the visible conversation branch.
 *
 * There is no process-local cache or clock: regenerate, app restart, and a byte-identical path all
 * produce the same names. Broker search/inspect calls are ignored; a successful broker invoke
 * contributes the concrete capability name instead of the broker's meta-tool name.
 */
object ToolRoutingHistory {
    fun recentSuccessfulToolNames(
        messages: List<ChatMessage>,
        maxTools: Int = DEFAULT_RECENT_TOOLS,
    ): Set<String> {
        if (maxTools <= 0) return emptySet()
        val names = linkedSetOf<String>()
        for (message in messages.asReversed()) {
            val segments = message.segments.orEmpty().asReversed().ifEmpty {
                message.toolCall?.let {
                    listOf(
                        MessageSegment(
                            type = "tool",
                            toolName = it.toolName,
                            toolArgs = it.arguments,
                            toolResult = it.result,
                        ),
                    )
                }.orEmpty()
            }
            for (segment in segments) {
                val result = segment.toolResult ?: continue
                if (!isSuccessful(result)) continue
                val exposedName = segment.toolName.orEmpty()
                // Pagination is transport plumbing, not user intent. Counting it would evict a
                // genuinely useful recent capability from the small deterministic hotset.
                if (exposedName == McpToolProvider.RESULT_PAGE) continue
                val concreteName = if (exposedName in McpDeferredToolProvider.META_TOOL_NAMES) {
                    brokerInvokeTarget(segment.toolArgs)
                } else {
                    exposedName.takeIf(String::isNotBlank)
                } ?: continue
                names += concreteName
                if (names.size >= maxTools) return names
            }
        }
        return names
    }

    private fun isSuccessful(result: String): Boolean {
        val normalized = result.trim()
        return !normalized.startsWith("Error executing tool", ignoreCase = true) &&
            !normalized.contains("tool call denied", ignoreCase = true) &&
            // Match both compact ("isError":true) and spaced ("isError": true) JSON serializers.
            !normalized.contains("\"isError\":true", ignoreCase = true) &&
            !normalized.contains("\"isError\": true", ignoreCase = true) &&
            // MCP-standard top-level error object.
            !normalized.startsWith("{\"error\":", ignoreCase = true) &&
            !normalized.startsWith("{ \"error\":", ignoreCase = true)
    }

    private fun brokerInvokeTarget(arguments: String?): String? {
        val args = runCatching {
            Json.parseToJsonElement(arguments.orEmpty()) as? JsonObject
        }.getOrNull() ?: return null
        val action = (args["action"] as? JsonPrimitive)?.contentOrNull
        if (!action.equals(McpDeferredToolProvider.ACTION_INVOKE, ignoreCase = true)) return null
        return (args["name"] as? JsonPrimitive)
            ?.contentOrNull
            ?.trim()
            ?.takeIf(String::isNotBlank)
    }

    private const val DEFAULT_RECENT_TOOLS = 5
}
