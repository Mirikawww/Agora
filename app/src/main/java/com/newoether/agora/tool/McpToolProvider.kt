package com.newoether.agora.tool

import com.newoether.agora.api.DeferPolicy
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.data.McpServerConfig
import com.newoether.agora.mcp.McpClientManager
import com.newoether.agora.mcp.McpResultPager
import com.newoether.agora.mcp.McpServerStatus
import com.newoether.agora.mcp.McpStatus
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Bridges tools/resources/prompts from configured MCP servers into Agora's tool loop. */
class McpToolProvider(
    private val manager: McpClientManager = McpClientManager(),
) : ToolProvider, AutoCloseable {
    var confirm: (suspend (server: String, summary: String) -> Boolean)? = null
    private val resultPager = McpResultPager(MCP_RESULT_PAGE_CHARS)

    suspend fun refresh(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.mcpEnabled) return emptyList()
        val definitions = manager.definitions(ctx.mcpServers)
        return if (definitions.isEmpty()) definitions else definitions + RESULT_PAGE_DEFINITION
    }

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> = emptyList()

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (!ctx.mcpEnabled) return error("MCP is disabled")
        if (name == RESULT_PAGE) {
            val args = runCatching { Json.parseToJsonElement(arguments).jsonObject }
                .getOrElse { return error("Invalid MCP result-page arguments") }
            val cursor = args["cursor"]?.jsonPrimitive?.contentOrNull
                ?.takeIf(String::isNotBlank)
                ?: return error("Missing MCP result cursor")
            val requestId = ctx.capabilityRequestId
                ?.takeIf(String::isNotBlank)
                ?: return error("MCP result cursor is outside an active generation")
            val requestedMaxChars = args["max_chars"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.toIntOrNull()
                ?.coerceIn(MIN_REQUESTED_PAGE_CHARS, MCP_RESULT_PAGE_CHARS)
                ?: MCP_RESULT_PAGE_CHARS
            val maxChars = minOf(
                requestedMaxChars,
                ctx.toolResultMaxChars ?: MCP_RESULT_PAGE_CHARS,
            )
            if (maxChars < McpResultPager.MIN_PAGE_CHARS) {
                return error("MCP result page budget is too small to preserve its cursor")
            }
            return resultPager.page(requestId, cursor, maxChars)
        }
        val resultAllowance = ctx.toolResultMaxChars ?: MCP_RESULT_PAGE_CHARS
        if (resultAllowance < McpResultPager.MIN_PAGE_CHARS) {
            // Block before a mutating remote call. Executing first and discovering that no
            // cursor-safe result fits would invite the model to repeat an already-applied change.
            return error("MCP result budget is too small; no remote tool was executed")
        }
        val server = manager.serverForTool(name, ctx.mcpServers)
        // Built-in connectors never go through the shell-style MCP approval sheet.
        if (server != null &&
            !server.id.startsWith("connector:") &&
            manager.toolNeedsConfirmation(name, ctx.mcpServers)
        ) {
            val approved = confirm?.invoke(server.name, "$name\n$arguments") ?: false
            if (!approved) return error("MCP tool call denied by user")
        }
        val raw = manager.execute(name, arguments, ctx.mcpServers)
        val maxChars = minOf(
            MCP_RESULT_PAGE_CHARS,
            resultAllowance,
        )
        if (raw.length <= maxChars) return raw
        val requestId = ctx.capabilityRequestId
            ?.takeIf(String::isNotBlank)
            ?: return error("Oversized MCP result is outside an active generation")
        return resultPager.budget(requestId, name, raw, maxChars)
    }

    fun clearRequest(requestId: String) {
        resultPager.clearRequest(requestId)
    }

    override fun handles(name: String): Boolean =
        name == RESULT_PAGE || name in McpClientManager.BROKER_NAMES

    override fun handles(name: String, ctx: GenerationContext): Boolean =
        name == RESULT_PAGE ||
            name in McpClientManager.BROKER_NAMES ||
            manager.serverForTool(name, ctx.mcpServers) != null

    suspend fun test(server: McpServerConfig): McpServerStatus = manager.test(server)

    fun observeServers(
        servers: Flow<List<McpServerConfig>>,
        persist: suspend (McpServerConfig) -> Unit,
    ) = manager.observeServers(servers, persist)

    val statuses: StateFlow<Map<String, McpStatus>> get() = manager.statuses

    fun startAuthorization(server: McpServerConfig) = manager.startAuthorization(server)

    fun cancelAuthorization(serverId: String) = manager.cancelAuthorization(serverId)

    suspend fun clearAuthorization(server: McpServerConfig) = manager.clearAuthorization(server)

    override fun close() {
        resultPager.clearAll()
        manager.close()
    }

    private fun error(message: String) = buildJsonObject {
        put("isError", true)
        put("message", message)
    }.toString()

    companion object {
        internal const val RESULT_PAGE = "mcp_result_page"
        private const val MCP_RESULT_PAGE_CHARS = 12_000
        private const val MIN_REQUESTED_PAGE_CHARS = 8_000

        internal val RESULT_PAGE_DEFINITION = ToolDefinition(
            function = ToolFunction(
                name = RESULT_PAGE,
                description = "Continue an oversized MCP result. Pass the opaque next_cursor " +
                    "exactly as returned; it is valid only in the current generation.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "cursor" to ToolProperty(
                            type = "string",
                            description = "Opaque next_cursor from the previous MCP result page.",
                        ),
                        "max_chars" to ToolProperty(
                            type = "integer",
                            description = "Optional page budget from 8000 to 12000 characters.",
                        ),
                    ),
                    required = listOf("cursor"),
                ),
            ),
            defer = DeferPolicy.NEVER,
        )
    }
}
