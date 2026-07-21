# Model Context Protocol (MCP)

Agora can act as an MCP host and client, connecting the assistant to remote MCP servers over **Streamable HTTP**. Legacy HTTP+SSE servers are supported through automatic fallback.

## Add a server

1. Open **Settings → Tools → MCP**.
2. Enable MCP and choose **Add MCP Server**.
3. Enter the server name and full endpoint URL, usually ending in `/mcp`.
4. Leave the transport on **Auto** unless the server explicitly requires legacy SSE.
5. Add a bearer token/custom headers, or enable OAuth 2.1 if the endpoint requires authorization, then choose **Test connection**.

You can also paste a Claude Desktop/Cursor-style `mcpServers` JSON object to import remote HTTP servers in bulk. Unsupported stdio entries are skipped on Android.

The connection test performs the MCP initialization handshake and reports the server implementation plus its tool, resource, and prompt counts.

## Supported capabilities

- Protocol lifecycle and capability negotiation (MCP 2025-11-25)
- Streamable HTTP, including JSON and inline SSE responses, sessions, reconnection, and legacy SSE fallback
- Persistent connection status, exponential-backoff reconnection, and automatic config reconciliation
- Paginated tool, resource, resource-template, and prompt discovery
- Tool calls with structured, text, image, audio, resource-link, and embedded-resource results
- Resource reads and prompt retrieval through the assistant's MCP bridge tools
- Bearer-token, custom-header, and OAuth 2.1 authentication (protected-resource and authorization-server discovery, PKCE, dynamic client registration, and refresh tokens)
- Per-server and per-tool enablement, request timeout, capability exposure, and per-tool confirmation

Tool names are automatically namespaced by server so identically named tools do not collide. Synchronized tool choices persist, and server metadata/schema refreshes preserve enablement and approval overrides. Tool schemas remain complete JSON Schema when forwarded to supported model providers.

## Security

Remote MCP servers can execute actions and return untrusted instructions. Keep **Confirm every remote tool call** enabled unless you trust the server for the current environment. Credentials are encrypted in local settings and omitted from exports unless API-key export is explicitly enabled.

For local development endpoints, Agora accepts `http://` URLs. Use HTTPS for servers reached over untrusted networks.

!!! note
    Android apps cannot provide a portable desktop-style process environment across distribution flavors, so this screen supports remote HTTP transports rather than stdio-launched MCP servers.
