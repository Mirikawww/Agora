package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.data.McpServerConfig
import com.newoether.agora.mcp.McpClientManager
import com.newoether.agora.mcp.McpServerStatus
import com.newoether.agora.mcp.McpStatus
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Bridges tools/resources/prompts from configured MCP servers into Agora's tool loop. */
class McpToolProvider(
    private val manager: McpClientManager = McpClientManager(),
) : ToolProvider, AutoCloseable {
    @Volatile
    private var cachedDefinitions: List<ToolDefinition> = emptyList()

    var confirm: (suspend (server: String, summary: String) -> Boolean)? = null

    suspend fun refresh(ctx: GenerationContext): List<ToolDefinition> {
        cachedDefinitions = if (ctx.mcpEnabled) manager.definitions(ctx.mcpServers) else emptyList()
        return cachedDefinitions
    }

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> =
        if (ctx.mcpEnabled) cachedDefinitions else emptyList()

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (!ctx.mcpEnabled) return error("MCP is disabled")
        val server = manager.serverForTool(name, ctx.mcpServers)
        if (server != null && manager.toolNeedsConfirmation(name, ctx.mcpServers)) {
            val approved = confirm?.invoke(server.name, "$name\n$arguments") ?: false
            if (!approved) return error("MCP tool call denied by user")
        }
        return manager.execute(name, arguments, ctx.mcpServers)
    }

    override fun handles(name: String): Boolean =
        name in McpClientManager.BROKER_NAMES || cachedDefinitions.any { it.function.name == name }

    suspend fun test(server: McpServerConfig): McpServerStatus = manager.test(server)

    fun observeServers(
        servers: Flow<List<McpServerConfig>>,
        persist: suspend (McpServerConfig) -> Unit,
    ) = manager.observeServers(servers, persist)

    val statuses: StateFlow<Map<String, McpStatus>> get() = manager.statuses

    fun startAuthorization(server: McpServerConfig) = manager.startAuthorization(server)

    fun cancelAuthorization(serverId: String) = manager.cancelAuthorization(serverId)

    suspend fun clearAuthorization(server: McpServerConfig) = manager.clearAuthorization(server)

    override fun close() = manager.close()

    private fun error(message: String) = buildJsonObject {
        put("isError", true)
        put("message", message)
    }.toString()
}
