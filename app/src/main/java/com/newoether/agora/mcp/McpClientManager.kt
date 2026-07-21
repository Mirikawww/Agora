package com.newoether.agora.mcp

import android.content.Context
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.data.McpToolConfig
import com.newoether.agora.data.McpServerConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequest
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListPromptsRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListResourceTemplatesRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListResourcesRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.PaginatedRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequest
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.Closeable
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/** Summary returned by the settings page's connection test. */
data class McpServerStatus(
    val serverName: String,
    val implementation: String,
    val protocolVersion: String,
    val toolCount: Int,
    val resourceCount: Int,
    val promptCount: Int,
    val tools: List<McpToolConfig> = emptyList(),
)

/**
 * App-lifetime MCP connection pool backed by the official Kotlin SDK.
 *
 * Connections are keyed by server id and transparently recreated when a configuration changes
 * or a stateful HTTP session expires. Tool names are namespaced before being sent to an LLM so
 * two servers can safely expose identically named tools.
 */
class McpClientManager(
    private val appContext: Context? = null,
) : Closeable {
    private data class Connection(
        val fingerprint: McpConnectionKey,
        val config: McpServerConfig,
        val client: Client,
        val tools: List<Tool>,
        val exposedTools: Map<String, Tool>,
    )

    private val httpClient = HttpClient(OkHttp) { install(SSE) }
    private val oauthHttpClient = okhttp3.OkHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connections = ConcurrentHashMap<String, Connection>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val reconnectJobs = ConcurrentHashMap<String, Job>()
    private val reconnectAttempts = ConcurrentHashMap<String, Int>()
    private val desiredServers = ConcurrentHashMap<String, McpServerConfig>()
    private val _statuses = MutableStateFlow<Map<String, McpStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, McpStatus>> = _statuses.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private var observeJob: Job? = null
    private var persistServer: suspend (McpServerConfig) -> Unit = {}
    private val oauthCoordinator = McpOAuthCoordinator(
        scope = scope,
        oauthClient = McpOAuthClient(oauthHttpClient),
        currentConfig = desiredServers::get,
        persistConfig = ::persistUpdatedServer,
        updateStatus = ::updateStatus,
    )

    /** Bind the pool to the settings source so connections live beyond an individual generation. */
    fun observeServers(
        servers: Flow<List<McpServerConfig>>,
        persist: suspend (McpServerConfig) -> Unit,
    ) {
        persistServer = persist
        observeJob?.cancel()
        observeJob = scope.launch {
            servers.distinctUntilChanged().collect(::reconcile)
        }
    }

    fun status(serverId: String): McpStatus = statuses.value[serverId] ?: McpStatus.Idle

    fun startAuthorization(server: McpServerConfig) {
        val context = appContext ?: error("OAuth requires an Android context")
        desiredServers[server.id] = server
        oauthCoordinator.startAuthorization(server, context)
    }

    fun cancelAuthorization(serverId: String) = oauthCoordinator.cancelAuthorization(serverId)

    suspend fun clearAuthorization(server: McpServerConfig) = oauthCoordinator.clearAuthorization(server)

    suspend fun definitions(servers: List<McpServerConfig>): List<ToolDefinition> = coroutineScope {
        val active = servers.filter { it.enabled && it.url.isNotBlank() }
        active.forEach { desiredServers[it.id] = it }
        closeRemoved(active.mapTo(mutableSetOf()) { it.id })
        val remote = active.map { server ->
            async {
                runCatching { refreshTools(connection(server)).let(::remoteDefinitions) }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten()
        remote + brokerDefinitions(active)
    }

    suspend fun test(server: McpServerConfig): McpServerStatus {
        var client: Client? = null
        try {
            client = connect(server, reconnectOnClose = false)
            val tools = if (client.serverCapabilities?.tools != null) {
                withTimeout(timeoutMillis(server)) { listAllTools(client) }
            } else emptyList()
            val resources = if (client.serverCapabilities?.resources != null) {
                withTimeout(timeoutMillis(server)) { listAllResources(client).size }
            } else 0
            val prompts = if (client.serverCapabilities?.prompts != null) {
                withTimeout(timeoutMillis(server)) { listAllPrompts(client).size }
            } else 0
            val info = client.serverVersion
            return McpServerStatus(
                serverName = server.name,
                implementation = listOfNotNull(info?.name, info?.version).joinToString(" ").ifBlank { "Unknown" },
                protocolVersion = "2025-11-25",
                toolCount = tools.size,
                resourceCount = resources,
                promptCount = prompts,
                tools = mergeTools(server.tools, tools),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw error
        } finally {
            client?.let { runCatching { it.close() } }
        }
    }

    suspend fun execute(exposedName: String, arguments: String, servers: List<McpServerConfig>): String {
        val args = parseArguments(arguments)
        return when (exposedName) {
            LIST_SERVERS -> listServers(servers)
            LIST_RESOURCES -> listResources(args, servers)
            READ_RESOURCE -> readResource(args, servers)
            LIST_PROMPTS -> listPrompts(args, servers)
            GET_PROMPT -> getPrompt(args, servers)
            else -> callRemoteTool(exposedName, args, servers)
        }
    }

    fun serverForTool(exposedName: String, servers: List<McpServerConfig>): McpServerConfig? {
        if (exposedName in BROKER_NAMES) return null
        return connections.values.firstOrNull { exposedName in it.exposedTools }?.config
            ?: servers.firstOrNull { exposedName.startsWith(serverPrefix(it)) }
    }

    fun toolNeedsConfirmation(exposedName: String, servers: List<McpServerConfig>): Boolean {
        val connection = connections.values.firstOrNull { exposedName in it.exposedTools } ?: return false
        val liveServer = servers.firstOrNull { it.id == connection.config.id } ?: connection.config
        val originalName = connection.exposedTools[exposedName]?.name ?: return liveServer.confirmToolCalls
        return liveServer.tools.firstOrNull { it.name == originalName }?.confirmToolCall
            ?: liveServer.confirmToolCalls
    }

    suspend fun invalidate(serverId: String) {
        reconnectJobs.remove(serverId)?.cancel()
        connections.remove(serverId)?.client?.close()
    }

    override fun close() {
        observeJob?.cancel()
        reconnectJobs.values.forEach(Job::cancel)
        reconnectJobs.clear()
        connections.values.forEach { connection ->
            kotlinx.coroutines.runBlocking { runCatching { connection.client.close() } }
        }
        connections.clear()
        httpClient.close()
        oauthHttpClient.dispatcher.executorService.shutdown()
        oauthHttpClient.connectionPool.evictAll()
        scope.coroutineContext[Job]?.cancel()
    }

    private suspend fun connection(config: McpServerConfig): Connection {
        val freshConfig = oauthCoordinator.ensureFreshToken(config)
        desiredServers[freshConfig.id] = freshConfig
        val fingerprint = connectionFingerprint(freshConfig)
        connections[config.id]?.takeIf { it.fingerprint == fingerprint }?.let { return it }
        return locks.getOrPut(config.id) { Mutex() }.withLock {
            connections[config.id]?.takeIf { it.fingerprint == fingerprint }?.let { return@withLock it }
            connections.remove(config.id)?.client?.close()
            updateStatus(config.id, McpStatus.Connecting)
            try {
                val client = connect(freshConfig)
                val tools = if (client.serverCapabilities?.tools != null) {
                    withTimeout(timeoutMillis(freshConfig)) { listAllTools(client) }
                } else emptyList()
                val latestConfig = desiredServers[config.id] ?: run {
                    runCatching { client.close() }
                    throw CancellationException("MCP server was removed while connecting")
                }
                if (connectionFingerprint(latestConfig) != fingerprint) {
                    runCatching { client.close() }
                    throw CancellationException("MCP configuration changed while connecting")
                }
                val mergedConfig = latestConfig.copy(tools = mergeTools(latestConfig.tools, tools))
                val exposed = namespaceTools(mergedConfig, enabledTools(mergedConfig, tools))
                Connection(fingerprint, mergedConfig, client, tools, exposed).also { connected ->
                    connections[config.id] = connected
                    reconnectAttempts.remove(config.id)
                    updateStatus(config.id, McpStatus.Connected)
                    if (mergedConfig.tools != latestConfig.tools) persistUpdatedServer(mergedConfig)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (oauthCoordinator.needsAuthorization(freshConfig, error)) {
                    updateStatus(config.id, McpStatus.NeedsAuthorization)
                } else {
                    updateStatus(config.id, McpStatus.Error.from(error))
                }
                throw error
            }
        }
    }

    /** Polling here complements list-changed notifications and keeps definitions fresh per generation. */
    private suspend fun refreshTools(connection: Connection): Connection {
        if (connection.client.serverCapabilities?.tools == null) return connection
        val tools = withTimeout(timeoutMillis(connection.config)) { listAllTools(connection.client) }
        val latestConfig = desiredServers[connection.config.id] ?: connection.config
        val mergedConfig = latestConfig.copy(tools = mergeTools(latestConfig.tools, tools))
        if (tools == connection.tools && mergedConfig == connection.config) return connection
        return connection.copy(
            config = mergedConfig,
            tools = tools,
            exposedTools = namespaceTools(mergedConfig, enabledTools(mergedConfig, tools)),
        ).also {
            connections[connection.config.id] = it
            if (mergedConfig.tools != latestConfig.tools) persistUpdatedServer(mergedConfig)
        }
    }

    private fun namespaceTools(config: McpServerConfig, tools: List<Tool>): Map<String, Tool> = buildMap {
        tools.forEach { tool ->
            var candidate = exposedToolName(config, tool.name)
            var suffix = 2
            while (candidate in this) {
                candidate = exposedToolName(config, "${tool.name}_$suffix")
                suffix++
            }
            put(candidate, tool)
        }
    }

    private suspend fun connect(config: McpServerConfig, reconnectOnClose: Boolean = true): Client {
        val timeoutMs = timeoutMillis(config)
        suspend fun connectStreamable(): Client {
            val client = newClient()
            val transport = StreamableHttpClientTransport(httpClient, config.url) { addHeaders(config) }
            if (reconnectOnClose) installTransportCallbacks(config, client, transport)
            return try {
                withTimeout(timeoutMs) { client.connect(transport) }
                client
            } catch (error: Throwable) {
                runCatching { client.close() }
                throw error
            }
        }
        suspend fun connectSse(): Client {
            val client = newClient()
            val transport = SseClientTransport(httpClient, config.url, requestBuilder = { addHeaders(config) })
            if (reconnectOnClose) installTransportCallbacks(config, client, transport)
            return try {
                withTimeout(timeoutMs) { client.connect(transport) }
                client
            } catch (error: Throwable) {
                runCatching { client.close() }
                throw error
            }
        }
        return when (config.transport.lowercase()) {
            "sse" -> connectSse()
            "streamable_http" -> connectStreamable()
            else -> runCatching { connectStreamable() }.getOrElse { streamError ->
                runCatching { connectSse() }.getOrElse { sseError ->
                    throw IllegalStateException(
                        "Streamable HTTP failed: ${streamError.message}; SSE fallback failed: ${sseError.message}",
                        sseError,
                    )
                }
            }
        }
    }

    private fun newClient(): Client = Client(Implementation(name = "agora-android", version = "1.3.7"))

    private fun io.ktor.client.request.HttpRequestBuilder.addHeaders(config: McpServerConfig) {
        val authorization = config.resolvedAuthorization()
        if (authorization != null) header(HttpHeaders.Authorization, authorization)
        config.headers.forEach { (name, value) ->
            if (name.lowercase() !in RESERVED_HEADERS && name.isNotBlank()) header(name, value)
        }
    }

    private fun installTransportCallbacks(
        config: McpServerConfig,
        client: Client,
        transport: AbstractTransport,
    ) {
        transport.onClose { requestReconnect(config.id, client) }
        transport.onError { error ->
            if (!error.message.orEmpty().contains("Maximum reconnection attempts exceeded", ignoreCase = true)) {
                requestReconnect(config.id, client)
            }
        }
    }

    private fun requestReconnect(serverId: String, sourceClient: Client?) {
        scope.launch {
            locks.getOrPut(serverId) { Mutex() }.withLock reconnectLock@{
                val current = connections[serverId]
                if (sourceClient != null && current?.client !== sourceClient) return@reconnectLock
                if (reconnectJobs[serverId]?.isActive == true) return@reconnectLock
                val attempt = (reconnectAttempts[serverId] ?: 0) + 1
                if (attempt > MAX_RECONNECT_ATTEMPTS) {
                    connections.remove(serverId)?.client?.let { runCatching { it.close() } }
                    updateStatus(serverId, McpStatus.Error("Connection closed after $MAX_RECONNECT_ATTEMPTS retries"))
                    return@reconnectLock
                }
                reconnectAttempts[serverId] = attempt
                updateStatus(serverId, McpStatus.Reconnecting(attempt, MAX_RECONNECT_ATTEMPTS))
                val job = scope.launch {
                    var retry = false
                    try {
                        delay(reconnectDelay(attempt))
                        val latest = desiredServers[serverId]
                            ?.takeIf { it.enabled && it.url.isNotBlank() }
                            ?: return@launch
                        connections.remove(serverId)?.client?.let { runCatching { it.close() } }
                        retry = runCatching { connection(latest) }.isFailure
                    } finally {
                        reconnectJobs.remove(serverId, currentCoroutineContext().job)
                    }
                    if (retry) requestReconnect(serverId, sourceClient = null)
                }
                reconnectJobs[serverId] = job
            }
        }
    }

    private suspend fun reconcile(servers: List<McpServerConfig>) {
        val active = servers.filter { it.enabled && it.url.isNotBlank() }.associateBy { it.id }
        desiredServers.putAll(active)
        (desiredServers.keys - active.keys).forEach(desiredServers::remove)
        (connections.keys - active.keys).forEach { serverId ->
            oauthCoordinator.forget(serverId)
            invalidate(serverId)
            _statuses.update { it - serverId }
        }
        active.values.forEach { server ->
            val existing = connections[server.id]
            if (existing == null || existing.fingerprint != connectionFingerprint(server)) {
                scope.launch { runCatching { connection(server) } }
            } else if (existing.config.tools != server.tools) {
                val refreshed = existing.copy(
                    config = server,
                    exposedTools = namespaceTools(server, enabledTools(server, existing.tools)),
                )
                connections[server.id] = refreshed
            }
        }
    }

    private suspend fun persistUpdatedServer(server: McpServerConfig) {
        desiredServers[server.id] = server
        persistServer(server)
    }

    private fun updateStatus(serverId: String, status: McpStatus) {
        _statuses.update { it + (serverId to status) }
    }

    private fun connectionFingerprint(config: McpServerConfig): McpConnectionKey = config.connectionKey()

    private fun enabledTools(config: McpServerConfig, tools: List<Tool>): List<Tool> {
        val settings = config.tools.associateBy { it.name }
        return tools.filter { settings[it.name]?.enabled != false }
    }

    private fun mergeTools(stored: List<McpToolConfig>, remote: List<Tool>): List<McpToolConfig> {
        val previous = stored.associateBy { it.name }
        return remote.map { tool ->
            val schema = McpJson.encodeToJsonElement(ToolSchema.serializer(), tool.inputSchema).jsonObject
            previous[tool.name]?.copy(description = tool.description, inputSchema = schema)
                ?: McpToolConfig(name = tool.name, description = tool.description, inputSchema = schema)
        }
    }

    private fun reconnectDelay(attempt: Int): Long =
        (BASE_RECONNECT_DELAY_MS * (1L shl (attempt - 1).coerceAtMost(10))).coerceAtMost(MAX_RECONNECT_DELAY_MS)

    private fun timeoutMillis(config: McpServerConfig): Long =
        config.timeoutSeconds.coerceIn(5, 600) * 1_000L

    private suspend fun listAllTools(client: Client): List<Tool> {
        val result = mutableListOf<Tool>()
        var cursor: String? = null
        val seen = mutableSetOf<String>()
        do {
            val page = client.listTools(ListToolsRequest(cursor.params()))
            result += page.tools
            cursor = page.nextCursor
        } while (cursor != null && seen.add(cursor) && seen.size < MAX_PAGES)
        return result
    }

    private suspend fun listAllResources(client: Client): List<io.modelcontextprotocol.kotlin.sdk.types.Resource> {
        val result = mutableListOf<io.modelcontextprotocol.kotlin.sdk.types.Resource>()
        var cursor: String? = null
        val seen = mutableSetOf<String>()
        do {
            val page = client.listResources(ListResourcesRequest(cursor.params()))
            result += page.resources
            cursor = page.nextCursor
        } while (cursor != null && seen.add(cursor) && seen.size < MAX_PAGES)
        return result
    }

    private suspend fun listAllResourceTemplates(
        client: Client,
    ): List<io.modelcontextprotocol.kotlin.sdk.types.ResourceTemplate> {
        val result = mutableListOf<io.modelcontextprotocol.kotlin.sdk.types.ResourceTemplate>()
        var cursor: String? = null
        val seen = mutableSetOf<String>()
        do {
            val page = client.listResourceTemplates(ListResourceTemplatesRequest(cursor.params()))
            result += page.resourceTemplates
            cursor = page.nextCursor
        } while (cursor != null && seen.add(cursor) && seen.size < MAX_PAGES)
        return result
    }

    private suspend fun listAllPrompts(client: Client): List<io.modelcontextprotocol.kotlin.sdk.types.Prompt> {
        val result = mutableListOf<io.modelcontextprotocol.kotlin.sdk.types.Prompt>()
        var cursor: String? = null
        val seen = mutableSetOf<String>()
        do {
            val page = client.listPrompts(ListPromptsRequest(cursor.params()))
            result += page.prompts
            cursor = page.nextCursor
        } while (cursor != null && seen.add(cursor) && seen.size < MAX_PAGES)
        return result
    }

    private fun String?.params(): PaginatedRequestParams? = this?.let(::PaginatedRequestParams)

    private fun remoteDefinitions(connection: Connection): List<ToolDefinition> =
        connection.exposedTools.map { (exposedName, tool) ->
            val schema = McpJson.encodeToJsonElement(ToolSchema.serializer(), tool.inputSchema).jsonObject
            ToolDefinition(
                function = ToolFunction(
                    name = exposedName,
                    description = "[MCP: ${connection.config.name}] ${tool.description.orEmpty()}".trim(),
                    parameters = ToolParameters(properties = emptyMap(), rawSchema = schema),
                )
            )
        }

    private fun brokerDefinitions(servers: List<McpServerConfig>): List<ToolDefinition> {
        if (servers.isEmpty()) return emptyList()
        val result = mutableListOf(
            brokerTool(LIST_SERVERS, "List connected MCP servers and their capabilities.", emptyMap()),
        )
        if (servers.any { it.exposeResources }) {
            result += brokerTool(
                LIST_RESOURCES,
                "List resources and resource templates exposed by MCP servers.",
                mapOf("server" to stringSchema("Optional MCP server name or id.")),
            )
            result += brokerTool(
                READ_RESOURCE,
                "Read a resource from an MCP server by URI.",
                mapOf(
                    "server" to stringSchema("MCP server name or id."),
                    "uri" to stringSchema("Resource URI."),
                ),
                listOf("server", "uri"),
            )
        }
        if (servers.any { it.exposePrompts }) {
            result += brokerTool(
                LIST_PROMPTS,
                "List reusable prompt templates exposed by MCP servers.",
                mapOf("server" to stringSchema("Optional MCP server name or id.")),
            )
            result += brokerTool(
                GET_PROMPT,
                "Get a prompt template from an MCP server with optional string arguments.",
                mapOf(
                    "server" to stringSchema("MCP server name or id."),
                    "name" to stringSchema("Prompt name."),
                    "arguments" to buildJsonObject {
                        put("type", "object")
                        put("description", "Prompt argument values as strings.")
                        put("additionalProperties", buildJsonObject { put("type", "string") })
                    },
                ),
                listOf("server", "name"),
            )
        }
        return result
    }

    private fun brokerTool(
        name: String,
        description: String,
        properties: Map<String, kotlinx.serialization.json.JsonElement>,
        required: List<String> = emptyList(),
    ) = ToolDefinition(
        function = ToolFunction(
            name = name,
            description = description,
            parameters = ToolParameters(
                properties = emptyMap(),
                rawSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", JsonObject(properties))
                    if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
                },
            ),
        )
    )

    private fun stringSchema(description: String) = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    private suspend fun callRemoteTool(
        exposedName: String,
        args: JsonObject,
        servers: List<McpServerConfig>,
    ): String {
        for (server in servers.filter { it.enabled }) {
            val connection = runCatching { connection(server) }.getOrNull() ?: continue
            val tool = connection.exposedTools[exposedName] ?: continue
            return withSessionRecovery(server) { active ->
                val activeTool = active.exposedTools[exposedName] ?: tool
                val request = CallToolRequest(CallToolRequestParams(activeTool.name, args))
                McpJson.encodeToString(
                    io.modelcontextprotocol.kotlin.sdk.types.CallToolResult.serializer(),
                    active.client.callTool(
                        request,
                        RequestOptions(timeout = server.timeoutSeconds.coerceIn(5, 600).seconds),
                    ),
                )
            }
        }
        return errorJson("Unknown or unavailable MCP tool: $exposedName")
    }

    private fun listServers(servers: List<McpServerConfig>): String = buildJsonObject {
        putJsonArray("servers") {
            servers.filter { it.enabled }.forEach { server ->
                val connection = connections[server.id]
                add(buildJsonObject {
                    put("id", server.id)
                    put("name", server.name)
                    put("url", server.url)
                    put("connected", connection != null)
                    connection?.let {
                        put("implementation", it.client.serverVersion?.name.orEmpty())
                        put("tools", it.tools.size)
                        put("resources", it.client.serverCapabilities?.resources != null)
                        put("prompts", it.client.serverCapabilities?.prompts != null)
                    }
                })
            }
        }
    }.toString()

    private suspend fun listResources(args: JsonObject, servers: List<McpServerConfig>): String {
        val selected = selectServers(args.string("server"), servers).filter { it.exposeResources }
        val serverResults = selected.map { server ->
            val connection = runCatching { connection(server) }.getOrNull()
            buildJsonObject {
                put("server", server.name)
                if (connection == null) {
                    put("error", "Connection failed")
                } else {
                    val resources = if (connection.client.serverCapabilities?.resources != null) {
                        runCatching {
                            withTimeout(timeoutMillis(server)) { listAllResources(connection.client) }
                        }.getOrDefault(emptyList())
                    } else emptyList()
                    put(
                        "resources",
                        McpJson.encodeToJsonElement(
                            ListSerializer(io.modelcontextprotocol.kotlin.sdk.types.Resource.serializer()),
                            resources,
                        ),
                    )
                    val templates = if (connection.client.serverCapabilities?.resources != null) {
                        runCatching {
                            withTimeout(timeoutMillis(server)) { listAllResourceTemplates(connection.client) }
                        }.getOrDefault(emptyList())
                    } else emptyList()
                    put(
                        "resourceTemplates",
                        McpJson.encodeToJsonElement(
                            ListSerializer(io.modelcontextprotocol.kotlin.sdk.types.ResourceTemplate.serializer()),
                            templates,
                        ),
                    )
                }
            }
        }
        return buildJsonObject {
            putJsonArray("servers") { serverResults.forEach(::add) }
        }.toString()
    }

    private suspend fun readResource(args: JsonObject, servers: List<McpServerConfig>): String {
        val server = selectServer(args.string("server"), servers) ?: return errorJson("MCP server not found")
        if (!server.exposeResources) return errorJson("Resources are disabled for ${server.name}")
        val uri = args.string("uri") ?: return errorJson("Missing resource URI")
        return withSessionRecovery(server) { active ->
            val result = active.client.readResource(
                ReadResourceRequest(ReadResourceRequestParams(uri)),
                RequestOptions(timeout = server.timeoutSeconds.coerceIn(5, 600).seconds),
            )
            McpJson.encodeToString(io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult.serializer(), result)
        }
    }

    private suspend fun listPrompts(args: JsonObject, servers: List<McpServerConfig>): String {
        val selected = selectServers(args.string("server"), servers).filter { it.exposePrompts }
        val serverResults = selected.map { server ->
            val prompts = runCatching {
                withTimeout(timeoutMillis(server)) { listAllPrompts(connection(server).client) }
            }.getOrDefault(emptyList())
            buildJsonObject {
                put("server", server.name)
                put(
                    "prompts",
                    McpJson.encodeToJsonElement(
                        ListSerializer(io.modelcontextprotocol.kotlin.sdk.types.Prompt.serializer()),
                        prompts,
                    ),
                )
            }
        }
        return buildJsonObject {
            putJsonArray("servers") { serverResults.forEach(::add) }
        }.toString()
    }

    private suspend fun getPrompt(args: JsonObject, servers: List<McpServerConfig>): String {
        val server = selectServer(args.string("server"), servers) ?: return errorJson("MCP server not found")
        if (!server.exposePrompts) return errorJson("Prompts are disabled for ${server.name}")
        val name = args.string("name") ?: return errorJson("Missing prompt name")
        val promptArgs = (args["arguments"] as? JsonObject)?.mapValues { (_, value) ->
            (value as? JsonPrimitive)?.content ?: value.toString()
        }
        return withSessionRecovery(server) { active ->
            val result = active.client.getPrompt(
                GetPromptRequest(GetPromptRequestParams(name, promptArgs)),
                RequestOptions(timeout = server.timeoutSeconds.coerceIn(5, 600).seconds),
            )
            McpJson.encodeToString(io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult.serializer(), result)
        }
    }

    private suspend fun <T> withSessionRecovery(
        server: McpServerConfig,
        block: suspend (Connection) -> T,
    ): T {
        val current = connection(server)
        return try {
            block(current)
        } catch (error: StreamableHttpError) {
            if (error.code == 404) {
                invalidate(server.id)
                block(connection(server))
            } else {
                if (oauthCoordinator.needsAuthorization(server, error)) {
                    updateStatus(server.id, McpStatus.NeedsAuthorization)
                }
                throw error
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (oauthCoordinator.needsAuthorization(server, error)) {
                updateStatus(server.id, McpStatus.NeedsAuthorization)
            }
            throw error
        }
    }

    private fun parseArguments(arguments: String): JsonObject = runCatching {
        json.parseToJsonElement(arguments.ifBlank { "{}" }).jsonObject
    }.getOrDefault(JsonObject(emptyMap()))

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    private fun selectServer(selector: String?, servers: List<McpServerConfig>): McpServerConfig? {
        val active = servers.filter { it.enabled }
        if (selector == null && active.size == 1) return active.single()
        return active.firstOrNull { it.id == selector || it.name.equals(selector, ignoreCase = true) }
    }

    private fun selectServers(selector: String?, servers: List<McpServerConfig>): List<McpServerConfig> =
        selector?.let { value -> listOfNotNull(selectServer(value, servers)) } ?: servers.filter { it.enabled }

    private suspend fun closeRemoved(activeIds: Set<String>) {
        connections.keys.filter { it !in activeIds }.forEach { invalidate(it) }
    }

    private fun errorJson(message: String) = buildJsonObject {
        put("isError", true)
        put("message", message)
    }.toString()

    companion object {
        const val LIST_SERVERS = "mcp_list_servers"
        const val LIST_RESOURCES = "mcp_list_resources"
        const val READ_RESOURCE = "mcp_read_resource"
        const val LIST_PROMPTS = "mcp_list_prompts"
        const val GET_PROMPT = "mcp_get_prompt"
        val BROKER_NAMES = setOf(LIST_SERVERS, LIST_RESOURCES, READ_RESOURCE, LIST_PROMPTS, GET_PROMPT)
        private val RESERVED_HEADERS = setOf(
            "host", "content-length", "content-type", "accept", "authorization",
            "mcp-session-id", "mcp-protocol-version",
        )
        private const val MAX_PAGES = 100
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val BASE_RECONNECT_DELAY_MS = 1_000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L

        fun serverPrefix(server: McpServerConfig): String =
            "mcp_${sanitize(server.name).take(18)}_${sanitize(server.id).take(6)}_"

        fun exposedToolName(server: McpServerConfig, toolName: String): String {
            val prefix = serverPrefix(server)
            val clean = sanitize(toolName)
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(toolName.toByteArray())
                .take(3)
                .joinToString("") { "%02x".format(it) }
            return (prefix + clean.take((63 - prefix.length - 7).coerceAtLeast(1)) + "_" + hash).take(64)
        }

        private fun sanitize(value: String): String = value.lowercase()
            .replace(Regex("[^a-z0-9_-]+"), "_")
            .trim('_')
            .ifBlank { "server" }
    }
}

/** Only fields that alter the wire connection belong here; tool/UI metadata must not reconnect. */
internal data class McpConnectionKey(
    val name: String,
    val url: String,
    val transport: String,
    val authorization: String?,
    val headers: Map<String, String>,
    val timeoutSeconds: Int,
)

internal fun McpServerConfig.connectionKey(): McpConnectionKey = McpConnectionKey(
    name = name,
    url = url,
    transport = transport,
    authorization = resolvedAuthorization(),
    headers = headers.filterKeys { !it.equals(HttpHeaders.Authorization, ignoreCase = true) },
    timeoutSeconds = timeoutSeconds,
)

private fun McpServerConfig.resolvedAuthorization(): String? {
    val manual = headers.entries
        .firstOrNull { it.key.equals(HttpHeaders.Authorization, ignoreCase = true) }
        ?.value
        ?.takeIf(String::isNotBlank)
    return when {
        manual != null -> manual
        bearerToken.isNotBlank() -> "Bearer $bearerToken"
        oauth?.enabled == true && !oauth.accessToken.isNullOrBlank() -> "Bearer ${oauth.accessToken}"
        else -> null
    }
}
