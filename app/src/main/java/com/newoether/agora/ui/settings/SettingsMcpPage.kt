package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.McpServerConfig
import com.newoether.agora.data.McpOAuthState
import com.newoether.agora.data.McpToolConfig
import com.newoether.agora.mcp.McpStatus
import com.newoether.agora.ui.common.McpServerIcon
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsMcpPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val enabled by viewModel.settings.mcpEnabled.collectAsState()
    val servers by viewModel.settings.mcpServers.collectAsState()
    val statuses by viewModel.mcpStatuses.collectAsState()
    val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()
    var editingId by remember { mutableStateOf<String?>(null) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    var showImport by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    CollapsingSettingsScaffold(
        title = stringResource(R.string.mcp_title),
        onBack = onBack,
        scrollState = scrollState,
        floatingActionButton = { if (showDocFab) DocumentationFab("mcp.md") },
    ) {
        SettingsGroupColumn {
            SettingsGroup(title = stringResource(R.string.mcp_title), items = listOf {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.mcp_enable)) },
                    supportingContent = { Text(stringResource(R.string.mcp_enable_desc)) },
                    leadingContent = { Icon(McpServerIcon, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = {
                        Switch(checked = enabled, onCheckedChange = viewModel.settings::setMcpEnabled)
                    },
                    modifier = Modifier.clickable { viewModel.settings.setMcpEnabled(!enabled) },
                )
            })

            if (enabled) {
                SettingsGroup(title = stringResource(R.string.mcp_servers), items = buildList {
                    if (servers.isEmpty()) {
                        add {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.mcp_no_servers)) },
                                supportingContent = { Text(stringResource(R.string.mcp_no_servers_desc)) },
                            )
                        }
                    }
                    servers.forEach { server ->
                        add {
                            SettingsItem(
                                headlineContent = { Text(server.name.ifBlank { stringResource(R.string.mcp_unnamed_server) }) },
                                supportingContent = {
                                    Column {
                                        Text(if (server.url.isBlank()) stringResource(R.string.mcp_url_missing) else server.url)
                                        Text(
                                            mcpStatusLabel(statuses[server.id] ?: McpStatus.Idle),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (statuses[server.id] is McpStatus.Error ||
                                                statuses[server.id] == McpStatus.NeedsAuthorization
                                            ) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        if (server.tools.isNotEmpty()) {
                                            Text(
                                                stringResource(
                                                    R.string.mcp_tool_count,
                                                    server.tools.count(McpToolConfig::enabled),
                                                    server.tools.size,
                                                ),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                },
                                leadingContent = {
                                    Switch(
                                        checked = server.enabled,
                                        onCheckedChange = { viewModel.settings.updateMcpServer(server.copy(enabled = it)) },
                                    )
                                },
                                trailingContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(onClick = { deleteId = server.id }) {
                                            Icon(Icons.Default.Delete, stringResource(R.string.delete))
                                        }
                                        Icon(Icons.Default.ChevronRight, null)
                                    }
                                },
                                modifier = Modifier.clickable { editingId = server.id },
                                leadingSpacing = 12.dp,
                            )
                        }
                    }
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.mcp_add_server), color = MaterialTheme.colorScheme.primary) },
                            leadingContent = { Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary) },
                            modifier = Modifier.clickable {
                                val server = McpServerConfig(
                                    id = UUID.randomUUID().toString(),
                                    name = "",
                                    url = "",
                                )
                                viewModel.settings.addMcpServer(server)
                                editingId = server.id
                            },
                        )
                    }
                    add {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.mcp_import), color = MaterialTheme.colorScheme.primary) },
                            leadingContent = { Icon(Icons.Default.FileDownload, null, tint = MaterialTheme.colorScheme.primary) },
                            modifier = Modifier.clickable { showImport = true },
                        )
                    }
                })
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    editingId?.let { id ->
        servers.firstOrNull { it.id == id }?.let { server ->
            McpServerDialog(
                server = server,
                viewModel = viewModel,
                onDismiss = { editingId = null },
                onSave = {
                    viewModel.settings.updateMcpServer(it)
                    editingId = null
                },
            )
        }
    }

    deleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text(stringResource(R.string.mcp_delete_title)) },
            text = { Text(stringResource(R.string.mcp_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.settings.removeMcpServer(id)
                    deleteId = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (showImport) {
        McpImportDialog(
            onDismiss = { showImport = false },
            onImport = { imported ->
                val existingNames = servers.map { it.name }.toSet()
                viewModel.settings.addMcpServers(imported.filter { it.name !in existingNames })
                showImport = false
            },
        )
    }
}

@Composable
private fun McpServerDialog(
    server: McpServerConfig,
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
    onSave: (McpServerConfig) -> Unit,
) {
    var name by remember(server.id) { mutableStateOf(server.name) }
    var url by remember(server.id) { mutableStateOf(server.url) }
    var transport by remember(server.id) { mutableStateOf(server.transport) }
    var bearerToken by remember(server.id) { mutableStateOf(server.bearerToken) }
    var headers by remember(server.id) { mutableStateOf(formatHeaders(server.headers)) }
    var timeout by remember(server.id) { mutableStateOf(server.timeoutSeconds.toString()) }
    var confirmTools by remember(server.id) { mutableStateOf(server.confirmToolCalls) }
    var exposeResources by remember(server.id) { mutableStateOf(server.exposeResources) }
    var exposePrompts by remember(server.id) { mutableStateOf(server.exposePrompts) }
    var tools by remember(server.id) { mutableStateOf(server.tools) }
    var oauthEnabled by remember(server.id) { mutableStateOf(server.oauth?.enabled == true) }
    var oauthClientId by remember(server.id) { mutableStateOf(server.oauth?.clientId.orEmpty()) }
    var oauthClientSecret by remember(server.id) { mutableStateOf(server.oauth?.clientSecret.orEmpty()) }
    var oauthScope by remember(server.id) { mutableStateOf(server.oauth?.scope.orEmpty()) }
    var transportMenu by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val statuses by viewModel.mcpStatuses.collectAsState()
    val connectionStatus = statuses[server.id] ?: McpStatus.Idle

    fun draft() = server.copy(
        name = name.trim(),
        url = url.trim(),
        transport = transport,
        bearerToken = bearerToken.trim(),
        headers = parseHeaders(headers),
        timeoutSeconds = timeout.toIntOrNull()?.coerceIn(5, 600) ?: 60,
        confirmToolCalls = confirmTools,
        exposeResources = exposeResources,
        exposePrompts = exposePrompts,
        tools = tools,
        oauth = if (oauthEnabled) {
            (server.oauth ?: McpOAuthState()).copy(
                enabled = true,
                clientId = oauthClientId.trim().takeIf(String::isNotBlank),
                clientSecret = oauthClientSecret.trim().takeIf(String::isNotBlank),
                scope = oauthScope.trim().takeIf(String::isNotBlank),
            )
        } else {
            server.oauth?.copy(enabled = false)
        },
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mcp_server_editor)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.mcp_server_name)) }, singleLine = true)
                OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.mcp_server_url)) }, singleLine = true)
                TextButton(onClick = { transportMenu = true }) {
                    Text(stringResource(R.string.mcp_transport_value, transportLabel(transport)))
                }
                DropdownMenu(expanded = transportMenu, onDismissRequest = { transportMenu = false }) {
                    listOf("auto", "streamable_http", "sse").forEach { value ->
                        DropdownMenuItem(
                            text = { Text(transportLabel(value)) },
                            onClick = { transport = value; transportMenu = false },
                        )
                    }
                }
                OutlinedTextField(
                    bearerToken,
                    { bearerToken = it },
                    label = { Text(stringResource(R.string.mcp_bearer_token)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                OutlinedTextField(
                    headers,
                    { headers = it },
                    label = { Text(stringResource(R.string.mcp_custom_headers)) },
                    supportingText = { Text(stringResource(R.string.mcp_custom_headers_desc)) },
                    minLines = 2,
                )
                OutlinedTextField(timeout, { timeout = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.mcp_timeout)) }, singleLine = true)
                ToggleRow(stringResource(R.string.mcp_confirm_tools), confirmTools) { confirmTools = it }
                ToggleRow(stringResource(R.string.mcp_expose_resources), exposeResources) { exposeResources = it }
                ToggleRow(stringResource(R.string.mcp_expose_prompts), exposePrompts) { exposePrompts = it }
                ToggleRow(stringResource(R.string.mcp_oauth_enable), oauthEnabled) { oauthEnabled = it }
                if (oauthEnabled) {
                    OutlinedTextField(
                        oauthClientId,
                        { oauthClientId = it },
                        label = { Text(stringResource(R.string.mcp_oauth_client_id)) },
                        supportingText = { Text(stringResource(R.string.mcp_oauth_client_id_desc)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        oauthClientSecret,
                        { oauthClientSecret = it },
                        label = { Text(stringResource(R.string.mcp_oauth_client_secret)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        oauthScope,
                        { oauthScope = it },
                        label = { Text(stringResource(R.string.mcp_oauth_scope)) },
                        singleLine = true,
                    )
                    Text(mcpStatusLabel(connectionStatus), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = connectionStatus != McpStatus.Authorizing && url.startsWith("http"),
                            onClick = {
                                val current = draft()
                                viewModel.settings.updateMcpServer(current)
                                viewModel.startMcpAuthorization(current)
                            },
                        ) {
                            Text(
                                if (connectionStatus == McpStatus.Authorizing) {
                                    stringResource(R.string.mcp_oauth_authorizing)
                                } else stringResource(R.string.mcp_oauth_authorize)
                            )
                        }
                        if (connectionStatus == McpStatus.Authorizing) {
                            TextButton(onClick = { viewModel.cancelMcpAuthorization(server.id) }) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                        if (server.oauth?.isAuthorized == true) {
                            TextButton(onClick = { viewModel.clearMcpAuthorization(draft()) }) {
                                Text(stringResource(R.string.mcp_oauth_clear))
                            }
                        }
                    }
                }
                testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Button(
                    enabled = !testing && name.isNotBlank() && url.startsWith("http"),
                    onClick = {
                        testing = true
                        testResult = null
                        scope.launch {
                            testResult = runCatching { viewModel.testMcpServer(draft()) }
                                .fold(
                                    onSuccess = { status ->
                                        tools = status.tools
                                        "${status.implementation} · ${status.toolCount} tools · ${status.resourceCount} resources · ${status.promptCount} prompts"
                                    },
                                    onFailure = { it.localizedMessage ?: "Connection failed" },
                                )
                            testing = false
                        }
                    },
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Text(if (testing) stringResource(R.string.mcp_testing) else stringResource(R.string.mcp_test_connection))
                }
                if (tools.isNotEmpty()) {
                    Text(stringResource(R.string.mcp_tools), style = MaterialTheme.typography.titleMedium)
                    tools.forEach { tool ->
                        McpToolEditor(
                            tool = tool,
                            serverDefaultApproval = confirmTools,
                            onChange = { updated ->
                                tools = tools.map { if (it.name == updated.name) updated else it }
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && (url.startsWith("https://") || url.startsWith("http://")),
                onClick = { onSave(draft()) },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun McpToolEditor(
    tool: McpToolConfig,
    serverDefaultApproval: Boolean,
    onChange: (McpToolConfig) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(tool.name, style = MaterialTheme.typography.labelLarge)
        tool.description?.takeIf(String::isNotBlank)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        (tool.inputSchema["properties"] as? kotlinx.serialization.json.JsonObject)
            ?.keys
            ?.takeIf { it.isNotEmpty() }
            ?.let { names ->
                Text(
                    stringResource(R.string.mcp_tool_parameters, names.joinToString(", ")),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.mcp_tool_enabled), modifier = Modifier.weight(1f))
            Switch(checked = tool.enabled, onCheckedChange = { onChange(tool.copy(enabled = it)) })
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.mcp_tool_confirm), modifier = Modifier.weight(1f))
            Switch(
                checked = tool.confirmToolCall ?: serverDefaultApproval,
                onCheckedChange = { onChange(tool.copy(confirmToolCall = it)) },
            )
        }
    }
}

@Composable
private fun McpImportDialog(
    onDismiss: () -> Unit,
    onImport: (List<McpServerConfig>) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mcp_import)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.mcp_import_desc), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    minLines = 8,
                    maxLines = 16,
                    placeholder = { Text("{ \"mcpServers\": { ... } }") },
                    isError = error != null,
                    supportingText = error?.let { message -> { Text(message) } },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                runCatching { parseMcpServers(text) }
                    .onSuccess { servers ->
                        if (servers.isEmpty()) error = "No supported remote MCP servers found"
                        else onImport(servers)
                    }
                    .onFailure { error = it.message ?: "Invalid JSON" }
            }) { Text(stringResource(R.string.mcp_import)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

internal fun parseMcpServers(value: String): List<McpServerConfig> {
    val root = Json.parseToJsonElement(value).jsonObject
    val servers = root["mcpServers"]?.jsonObject ?: return emptyList()
    return servers.mapNotNull { (name, element) ->
        val config = element.jsonObject
        val url = config["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val transport = when (config["type"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
            "sse" -> "sse"
            "streamable_http", "http" -> "streamable_http"
            else -> "auto"
        }
        val headers = config["headers"]?.jsonObject?.mapValues { (_, headerValue) ->
            headerValue.jsonPrimitive.contentOrNull.orEmpty()
        }.orEmpty()
        McpServerConfig(name = name, url = url, transport = transport, headers = headers)
    }
}

@Composable
private fun mcpStatusLabel(status: McpStatus): String = when (status) {
    McpStatus.Idle -> stringResource(R.string.mcp_status_idle)
    McpStatus.Connecting -> stringResource(R.string.mcp_status_connecting)
    McpStatus.Connected -> stringResource(R.string.mcp_status_connected)
    is McpStatus.Reconnecting -> stringResource(
        R.string.mcp_status_reconnecting,
        status.attempt,
        status.maxAttempts,
    )
    McpStatus.NeedsAuthorization -> stringResource(R.string.mcp_status_needs_authorization)
    McpStatus.Authorizing -> stringResource(R.string.mcp_status_authorizing)
    is McpStatus.Error -> status.message
}

private fun transportLabel(value: String): String = when (value) {
    "streamable_http" -> "Streamable HTTP"
    "sse" -> "Legacy SSE"
    else -> "Auto (HTTP → SSE)"
}

private fun formatHeaders(headers: Map<String, String>): String =
    headers.entries.joinToString("\n") { (name, value) -> "$name: $value" }

internal fun parseHeaders(value: String): Map<String, String> = value.lineSequence()
    .mapNotNull { line ->
        val separator = line.indexOf(':')
        if (separator <= 0) null
        else line.substring(0, separator).trim().takeIf { it.isNotBlank() }
            ?.let { it to line.substring(separator + 1).trim() }
    }
    .toMap()
