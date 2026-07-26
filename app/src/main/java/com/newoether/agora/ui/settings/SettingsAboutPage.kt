package com.newoether.agora.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAboutPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val packageInfo = remember {
        try { context.packageManager.getPackageInfo(context.packageName, 0) } catch (_: Exception) { null }
    }
    val versionName = packageInfo?.versionName ?: "?"
    val versionCode = packageInfo?.longVersionCode ?: 0

    val autoUpdateCheck by viewModel.settings.autoUpdateCheck.collectAsState()
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var isChecking by remember { mutableStateOf(false) }
    var showChangelog by remember { mutableStateOf(false) }
    var changelogLoading by remember { mutableStateOf(false) }
    var changelog by remember { mutableStateOf<List<com.newoether.agora.util.ReleaseNotes>>(emptyList()) }
    var changelogError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun openUrl(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.about_title),
        onBack = onBack
    ) {
        SettingsGroupColumn {
            // -- App Info --
            SettingsGroup(title = stringResource(R.string.about_info), items = listOf({
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.about_original_developer)) },
                    supportingContent = { Text(stringResource(R.string.about_developer_name)) },
                    leadingContent = { Icon(Icons.Default.Person, contentDescription = null) }
                )
            }, {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.about_current_developer)) },
                    supportingContent = { Text(stringResource(R.string.about_current_developer_name)) },
                    leadingContent = { Icon(Icons.Default.Person, contentDescription = null) }
                )
            }, {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.about_version)) },
                    supportingContent = { Text("v$versionName ($versionCode)") },
                    leadingContent = { Icon(Icons.Default.Info, contentDescription = null) }
                )
            }))

            // -- Updates --
            SettingsGroup(title = stringResource(R.string.about_updates), items = buildList {
                add {
                    SettingsItem(
                        headlineContent = {
                            Text(
                                if (isChecking) stringResource(R.string.about_checking)
                                else updateStatus ?: stringResource(R.string.about_check_updates)
                            )
                        },
                        supportingContent = { Text(stringResource(R.string.about_check_updates_desc)) },
                        leadingContent = { Icon(Icons.Default.Download, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = {
                            if (isChecking) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        },
                        modifier = Modifier.clickable(enabled = !isChecking) {
                            isChecking = true
                            scope.launch {
                                val info = withContext(Dispatchers.IO) { viewModel.checkForUpdates() }
                                if (info != null) {
                                    viewModel.showUpdateDialog(info)
                                } else {
                                    updateStatus = context.getString(R.string.about_up_to_date, versionName)
                                }
                                isChecking = false
                            }
                        }
                    )
                }
                add {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.about_auto_update)) },
                        supportingContent = { Text(stringResource(R.string.about_auto_update_desc)) },
                        leadingContent = { Icon(Icons.Default.Sync, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = {
                            Switch(checked = autoUpdateCheck, onCheckedChange = { viewModel.settings.setAutoUpdateCheck(it) })
                        },
                        modifier = Modifier.clickable { viewModel.settings.setAutoUpdateCheck(!autoUpdateCheck) }
                    )
                }
                add {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.about_view_changelog)) },
                        supportingContent = { Text(stringResource(R.string.about_view_changelog_desc)) },
                        leadingContent = { Icon(Icons.Default.History, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = {
                            if (changelogLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                            }
                        },
                        modifier = Modifier.clickable(enabled = !changelogLoading) {
                            showChangelog = true
                            if (changelog.isEmpty() && changelogError == null) {
                                changelogLoading = true
                                scope.launch {
                                    try {
                                        changelog = withContext(Dispatchers.IO) {
                                            viewModel.fetchAllReleaseNotes()
                                        }
                                        if (changelog.isEmpty()) {
                                            changelogError = context.getString(R.string.about_changelog_empty)
                                        }
                                    } catch (e: Exception) {
                                        changelogError = e.localizedMessage
                                    } finally {
                                        changelogLoading = false
                                    }
                                }
                            }
                        }
                    )
                }
            })

            // -- Links --
            SettingsGroup(title = stringResource(R.string.about_links), items = listOf({
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.about_github), modifier = Modifier.padding(vertical = 6.dp)) },
                    leadingContent = { Icon(Icons.Default.Code, contentDescription = null) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    modifier = Modifier.clickable { openUrl("https://github.com/Mirikawww/Agora") }
                )
            }, {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.about_issue_tracker), modifier = Modifier.padding(vertical = 6.dp)) },
                    leadingContent = { Icon(Icons.Default.BugReport, contentDescription = null) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    modifier = Modifier.clickable { openUrl("https://github.com/Mirikawww/Agora/issues") }
                )
            }, {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.about_contribute), modifier = Modifier.padding(vertical = 6.dp)) },
                    leadingContent = { Icon(Icons.Default.VolunteerActivism, contentDescription = null) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    modifier = Modifier.clickable { openUrl("https://github.com/Mirikawww/Agora/pulls") }
                )
            }, {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.about_privacy_policy), modifier = Modifier.padding(vertical = 6.dp)) },
                    leadingContent = { Icon(Icons.Default.VerifiedUser, contentDescription = null) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    modifier = Modifier.clickable { openUrl("https://github.com/Mirikawww/Agora/blob/main/PRIVACY.md") }
                )
            }))
        }
    }

    if (showChangelog) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showChangelog = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f)
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp)
            ) {
                Text(
                    stringResource(R.string.about_changelog_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = true)
                        .verticalScroll(rememberScrollState())
                ) {
                    when {
                        changelogLoading -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                            }
                        }
                        changelogError != null && changelog.isEmpty() -> {
                            Text(
                                changelogError ?: stringResource(R.string.about_changelog_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        else -> {
                            changelog.forEachIndexed { index, note ->
                                if (index > 0) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 12.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                    )
                                }
                                Text(
                                    stringResource(R.string.about_changelog_version, note.version),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (note.publishedAt.isNotBlank()) {
                                    Text(
                                        note.publishedAt.take(10),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                if (note.body.isBlank()) {
                                    Text(
                                        "—",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    note.body.split("\n").forEach { line ->
                                        when {
                                            line.startsWith("## ") -> Text(
                                                line.removePrefix("## "),
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                                            )
                                            line.startsWith("- ") || line.startsWith("• ") -> Text(
                                                "•  ${line.removePrefix("- ").removePrefix("• ")}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(top = 2.dp)
                                            )
                                            line.isBlank() -> Spacer(modifier = Modifier.height(4.dp))
                                            else -> Text(
                                                line,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(top = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
