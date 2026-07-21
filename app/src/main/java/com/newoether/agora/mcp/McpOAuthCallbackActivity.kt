package com.newoether.agora.mcp

import android.os.Bundle
import androidx.activity.ComponentActivity

/** Receives the OAuth authorization-code redirect and forwards it to the waiting coordinator. */
class McpOAuthCallbackActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let { uri ->
            McpOAuthCallbackBus.events.tryEmit(
                McpOAuthCallback(
                    state = uri.getQueryParameter("state"),
                    code = uri.getQueryParameter("code"),
                    error = uri.getQueryParameter("error_description") ?: uri.getQueryParameter("error"),
                )
            )
        }
        finish()
    }
}
