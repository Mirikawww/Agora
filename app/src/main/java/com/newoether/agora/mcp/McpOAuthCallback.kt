package com.newoether.agora.mcp

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableSharedFlow

const val MCP_OAUTH_REDIRECT_URI = "agora://mcp-oauth-callback"

data class McpOAuthCallback(
    val state: String?,
    val code: String?,
    val error: String?,
)

/** Process-local bridge from the exported callback Activity to the active MCP authorization job. */
object McpOAuthCallbackBus {
    val events = MutableSharedFlow<McpOAuthCallback>(extraBufferCapacity = 1)
}

fun launchMcpAuthorization(context: Context, authorizationUrl: String) {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(authorizationUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
