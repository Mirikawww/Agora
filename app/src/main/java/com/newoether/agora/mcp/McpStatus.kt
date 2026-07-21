package com.newoether.agora.mcp

/** Observable lifecycle state for a configured MCP server. */
sealed interface McpStatus {
    data object Idle : McpStatus
    data object Connecting : McpStatus
    data object Connected : McpStatus
    data class Reconnecting(val attempt: Int, val maxAttempts: Int) : McpStatus
    data object NeedsAuthorization : McpStatus
    data object Authorizing : McpStatus
    data class Error(val message: String, val detail: String? = null) : McpStatus {
        companion object {
            fun from(error: Throwable, fallback: String? = null): Error = Error(
                message = error.message?.takeIf(String::isNotBlank)
                    ?: fallback
                    ?: error.javaClass.simpleName,
                detail = error.stackTraceToString(),
            )
        }
    }
}
