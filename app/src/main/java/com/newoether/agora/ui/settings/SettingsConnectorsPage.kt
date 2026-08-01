package com.newoether.agora.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.mcp.McpStatus
import com.newoether.agora.tool.NotionConnector
import com.newoether.agora.tool.TodoistConnector
import com.newoether.agora.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsConnectorsPage(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    onOpenGithub: () -> Unit = {},
    onOpenTodoist: () -> Unit = {},
    onOpenNotion: () -> Unit = {},
) {
    CollapsingSettingsScaffold(
        title = stringResource(R.string.settings_connectors),
        onBack = onBack,
    ) {
        SettingsGroupColumn {
            SettingsGroup(
                title = "",
                items = listOf(
                    {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.connector_github)) },
                            supportingContent = { Text(stringResource(R.string.connector_github_desc)) },
                            leadingContent = {
                                Image(
                                    painter = painterResource(R.drawable.ic_github_mark),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                )
                            },
                            trailingContent = {
                                Icon(Icons.Default.ChevronRight, contentDescription = null)
                            },
                            modifier = Modifier.clickable { onOpenGithub() },
                        )
                    },
                    {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.connector_todoist)) },
                            supportingContent = { Text(stringResource(R.string.connector_todoist_desc)) },
                            leadingContent = {
                                Image(
                                    painter = painterResource(R.drawable.ic_todoist_color),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                )
                            },
                            trailingContent = {
                                Icon(Icons.Default.ChevronRight, contentDescription = null)
                            },
                            modifier = Modifier.clickable { onOpenTodoist() },
                        )
                    },
                    {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.connector_notion)) },
                            supportingContent = { Text(stringResource(R.string.connector_notion_desc)) },
                            leadingContent = {
                                // Single-colour silhouette (see the drawable's comment) —
                                // tinted so it stays visible in both light and dark themes.
                                Icon(
                                    painter = painterResource(R.drawable.ic_notion_mark),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            trailingContent = {
                                Icon(Icons.Default.ChevronRight, contentDescription = null)
                            },
                            modifier = Modifier.clickable { onOpenNotion() },
                        )
                    },
                ),
            )
        }
    }
}

/** Provider-detail style page for the GitHub connector (enable + token). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsGithubConnectorDetailPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val enabled by viewModel.settings.githubConnectorEnabled.collectAsState()
    val token by viewModel.settings.githubToken.collectAsState()
    var tokenDraft by remember(token) { mutableStateOf(token) }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.connector_github),
        onBack = onBack,
    ) {
        SettingsGroupColumn {
            SettingsGroup(
                title = "",
                items = listOf(
                    {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.connector_github)) },
                            supportingContent = { Text(stringResource(R.string.connector_github_desc)) },
                            leadingContent = {
                                Image(
                                    painter = painterResource(R.drawable.ic_github_mark),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = enabled,
                                    onCheckedChange = { viewModel.settings.setGithubConnectorEnabled(it) },
                                )
                            },
                        )
                    },
                    {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(
                                stringResource(R.string.connector_github_token_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = tokenDraft,
                                onValueChange = {
                                    tokenDraft = it
                                    viewModel.settings.setGithubToken(it.trim())
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                                label = { Text(stringResource(R.string.connector_github_token)) },
                                placeholder = { Text("ghp_…") },
                            )
                        }
                    },
                ),
            )
        }
    }
}

/**
 * Todoist connector detail: enable + browser OAuth against the hosted MCP endpoint.
 * Tokens come from the OAuth login callback (no manual API token field).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTodoistConnectorDetailPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val enabled by viewModel.settings.todoistConnectorEnabled.collectAsState()
    val oauth by viewModel.settings.todoistOAuth.collectAsState()
    val statuses by viewModel.mcpStatuses.collectAsState()
    val connectionStatus = statuses[TodoistConnector.SERVER_ID] ?: McpStatus.Idle
    val authorized = oauth?.isAuthorized == true
    val server = remember(oauth) { TodoistConnector.serverConfig(oauth) }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.connector_todoist),
        onBack = onBack,
    ) {
        SettingsGroupColumn {
            SettingsGroup(
                title = "",
                items = listOf(
                    {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.connector_todoist)) },
                            supportingContent = { Text(stringResource(R.string.connector_todoist_desc)) },
                            leadingContent = {
                                Image(
                                    painter = painterResource(R.drawable.ic_todoist_color),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = enabled,
                                    onCheckedChange = { viewModel.settings.setTodoistConnectorEnabled(it) },
                                )
                            },
                        )
                    },
                    {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(
                                text = when {
                                    connectionStatus is McpStatus.Error ->
                                        connectionStatus.message
                                    connectionStatus == McpStatus.Authorizing ->
                                        stringResource(R.string.mcp_status_authorizing)
                                    connectionStatus == McpStatus.Connected && authorized ->
                                        stringResource(R.string.connector_todoist_connected)
                                    authorized ->
                                        stringResource(R.string.connector_todoist_authorized)
                                    connectionStatus == McpStatus.NeedsAuthorization ->
                                        stringResource(R.string.mcp_status_needs_authorization)
                                    else ->
                                        stringResource(R.string.connector_todoist_not_authorized)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = when {
                                    connectionStatus is McpStatus.Error ->
                                        MaterialTheme.colorScheme.error
                                    authorized ->
                                        MaterialTheme.colorScheme.primary
                                    else ->
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    enabled = connectionStatus != McpStatus.Authorizing,
                                    onClick = {
                                        // Ensure connector is on so the synthetic MCP server is observed.
                                        if (!enabled) {
                                            viewModel.settings.setTodoistConnectorEnabled(true)
                                        }
                                        viewModel.startMcpAuthorization(server)
                                    },
                                ) {
                                    Text(
                                        if (connectionStatus == McpStatus.Authorizing) {
                                            stringResource(R.string.mcp_oauth_authorizing)
                                        } else if (authorized) {
                                            stringResource(R.string.connector_todoist_reauthorize)
                                        } else {
                                            stringResource(R.string.connector_todoist_sign_in)
                                        },
                                    )
                                }
                                if (connectionStatus == McpStatus.Authorizing) {
                                    TextButton(onClick = {
                                        viewModel.cancelMcpAuthorization(TodoistConnector.SERVER_ID)
                                    }) {
                                        Text(stringResource(R.string.cancel))
                                    }
                                }
                                if (authorized) {
                                    TextButton(onClick = {
                                        viewModel.clearMcpAuthorization(server)
                                    }) {
                                        Text(stringResource(R.string.connector_todoist_disconnect))
                                    }
                                }
                            }
                        }
                    },
                ),
            )
        }
    }
}

/**
 * Notion connector detail: enable + browser OAuth against `https://mcp.notion.com/mcp`.
 * Notion's MCP refuses bearer tokens outright, so OAuth is the only path — there is
 * deliberately no manual token field here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsNotionConnectorDetailPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val enabled by viewModel.settings.notionConnectorEnabled.collectAsState()
    val oauth by viewModel.settings.notionOAuth.collectAsState()
    val statuses by viewModel.mcpStatuses.collectAsState()
    val connectionStatus = statuses[NotionConnector.SERVER_ID] ?: McpStatus.Idle
    val authorized = oauth?.isAuthorized == true
    val server = remember(oauth) { NotionConnector.serverConfig(oauth) }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.connector_notion),
        onBack = onBack,
    ) {
        SettingsGroupColumn {
            SettingsGroup(
                title = "",
                items = listOf(
                    {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.connector_notion)) },
                            supportingContent = { Text(stringResource(R.string.connector_notion_desc)) },
                            leadingContent = {
                                // Single-colour silhouette (see the drawable's comment) —
                                // tinted so it stays visible in both light and dark themes.
                                Icon(
                                    painter = painterResource(R.drawable.ic_notion_mark),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = enabled,
                                    onCheckedChange = { viewModel.settings.setNotionConnectorEnabled(it) },
                                )
                            },
                        )
                    },
                    {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(
                                text = when {
                                    connectionStatus is McpStatus.Error ->
                                        connectionStatus.message
                                    connectionStatus == McpStatus.Authorizing ->
                                        stringResource(R.string.mcp_status_authorizing)
                                    connectionStatus == McpStatus.Connected && authorized ->
                                        stringResource(R.string.connector_notion_connected)
                                    authorized ->
                                        stringResource(R.string.connector_notion_authorized)
                                    connectionStatus == McpStatus.NeedsAuthorization ->
                                        stringResource(R.string.mcp_status_needs_authorization)
                                    else ->
                                        stringResource(R.string.connector_notion_not_authorized)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = when {
                                    connectionStatus is McpStatus.Error ->
                                        MaterialTheme.colorScheme.error
                                    authorized ->
                                        MaterialTheme.colorScheme.primary
                                    else ->
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    enabled = connectionStatus != McpStatus.Authorizing,
                                    onClick = {
                                        // Ensure connector is on so the synthetic MCP server is observed.
                                        if (!enabled) {
                                            viewModel.settings.setNotionConnectorEnabled(true)
                                        }
                                        viewModel.startMcpAuthorization(server)
                                    },
                                ) {
                                    Text(
                                        if (connectionStatus == McpStatus.Authorizing) {
                                            stringResource(R.string.mcp_oauth_authorizing)
                                        } else if (authorized) {
                                            stringResource(R.string.connector_notion_reauthorize)
                                        } else {
                                            stringResource(R.string.connector_notion_sign_in)
                                        },
                                    )
                                }
                                if (connectionStatus == McpStatus.Authorizing) {
                                    TextButton(onClick = {
                                        viewModel.cancelMcpAuthorization(NotionConnector.SERVER_ID)
                                    }) {
                                        Text(stringResource(R.string.cancel))
                                    }
                                }
                                if (authorized) {
                                    TextButton(onClick = {
                                        viewModel.clearMcpAuthorization(server)
                                    }) {
                                        Text(stringResource(R.string.connector_notion_disconnect))
                                    }
                                }
                            }
                        }
                    },
                ),
            )
        }
    }
}
