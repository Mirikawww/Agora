package com.newoether.agora.tool

import com.newoether.agora.data.McpOAuthState
import com.newoether.agora.data.McpServerConfig

/**
 * Built-in Todoist connector.
 *
 * Hosted MCP (streamable HTTP): `https://ai.todoist.net/mcp`
 * Auth: OAuth 2.1 via the existing MCP OAuth stack (browser login + PKCE).
 * Access/refresh tokens live in [McpOAuthState] on the synthetic server config.
 */
object TodoistConnector {
    const val SERVER_ID = "connector:todoist"
    const val SERVER_NAME = "Todoist"
    const val MCP_URL = "https://ai.todoist.net/mcp"

    fun isAuthorized(oauth: McpOAuthState?): Boolean =
        oauth?.enabled == true && oauth.isAuthorized

    fun isActive(enabled: Boolean, oauth: McpOAuthState?): Boolean =
        enabled && isAuthorized(oauth)

    fun serverConfig(oauth: McpOAuthState? = null): McpServerConfig = McpServerConfig(
        id = SERVER_ID,
        name = SERVER_NAME,
        url = MCP_URL,
        enabled = true,
        transport = "auto",
        bearerToken = "",
        // OAuth owns auth; leave bearer empty so resolvedAuthorization uses oauth.accessToken.
        oauth = oauth?.copy(enabled = true) ?: McpOAuthState(enabled = true),
        // Built-in connector tools are first-class (todoist_*). Don't force a confirm dialog
        // on every call — that made the model fall back to describing shell/commands instead.
        confirmToolCalls = false,
        exposeResources = true,
        exposePrompts = true,
    )

    /** True when [server] is this connector's synthetic row or a hand-added duplicate of it. */
    fun matches(server: McpServerConfig): Boolean =
        server.id == SERVER_ID ||
            server.url.trimEnd('/').equals(MCP_URL, ignoreCase = true)

    /**
     * Drops user-added MCP entries that would duplicate the built-in connector.
     *
     * [connectorEnabled] reflects whether the connector is actually on: when it is off
     * there is no synthetic row to collide with, and stripping the user's own entry
     * would silently leave them with no Todoist MCP at all.
     */
    fun withoutBuiltin(
        servers: List<McpServerConfig>,
        connectorEnabled: Boolean = true
    ): List<McpServerConfig> =
        if (!connectorEnabled) servers else servers.filterNot(::matches)
}
