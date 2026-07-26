package com.newoether.agora.tool

import com.newoether.agora.data.McpOAuthState
import com.newoether.agora.data.McpServerConfig

/**
 * Built-in Notion connector.
 *
 * Hosted MCP (streamable HTTP): `https://mcp.notion.com/mcp`
 * (`/sse` is the legacy transport and is not used here.)
 * Auth: OAuth 2.1 only — Notion's MCP explicitly rejects bearer tokens — so this
 * rides the same browser-login + PKCE stack as [TodoistConnector], with the
 * access/refresh pair kept in [McpOAuthState] on the synthetic server config.
 */
object NotionConnector {
    const val SERVER_ID = "connector:notion"
    const val SERVER_NAME = "Notion"
    const val MCP_URL = "https://mcp.notion.com/mcp"

    /** Legacy SSE endpoint — matched on import so a hand-added row isn't duplicated. */
    private const val SSE_URL = "https://mcp.notion.com/sse"

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
        // OAuth owns auth; leave bearer empty so resolvedAuthorization uses oauth.accessToken.
        bearerToken = "",
        oauth = oauth?.copy(enabled = true) ?: McpOAuthState(enabled = true),
        // First-class connector tools (notion_*) — no per-call approval sheet.
        confirmToolCalls = false,
        exposeResources = true,
        exposePrompts = true,
    )

    /** True when [server] is this connector's synthetic row or a hand-added duplicate of it. */
    fun matches(server: McpServerConfig): Boolean {
        val url = server.url.trimEnd('/')
        return server.id == SERVER_ID ||
            url.equals(MCP_URL, ignoreCase = true) ||
            url.equals(SSE_URL, ignoreCase = true)
    }

    /**
     * Drops user-added MCP entries that would duplicate the built-in connector.
     *
     * [connectorEnabled] must reflect whether the connector is actually on: when it is
     * off there is no synthetic row to collide with, and stripping the user's own entry
     * would silently leave them with no Notion MCP at all.
     */
    fun withoutBuiltin(
        servers: List<McpServerConfig>,
        connectorEnabled: Boolean = true
    ): List<McpServerConfig> =
        if (!connectorEnabled) servers else servers.filterNot(::matches)
}
