package com.newoether.agora.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
    val updateChannel by viewModel.settings.updateChannel.collectAsState()
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var isChecking by remember { mutableStateOf(false) }
    var showChangelog by remember { mutableStateOf(false) }
    var changelogLoading by remember { mutableStateOf(false) }
    var changelog by remember { mutableStateOf<List<com.newoether.agora.util.ReleaseNotes>>(emptyList()) }
    var changelogError by remember { mutableStateOf<String?>(null) }
    var ciStatus by remember { mutableStateOf<com.newoether.agora.util.UpdateChecker.CiRunStatus?>(null) }
    val scope = rememberCoroutineScope()

    // Poll the live CI status. A running build is polled every 15s so the row
    // reflects progress without a manual refresh; once the run settles the poll
    // backs off to 60s (a new push is the only thing that can change it).
    LaunchedEffect(Unit) {
        while (true) {
            val fresh = withContext(Dispatchers.IO) {
                com.newoether.agora.util.UpdateChecker.fetchCiStatus()
            }
            ciStatus = fresh
            kotlinx.coroutines.delay(if (fresh?.isRunning == true) 15_000L else 60_000L)
        }
    }

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
                    leadingContent = { Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                )
            }, {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.about_current_developer)) },
                    supportingContent = { Text(stringResource(R.string.about_current_developer_name)) },
                    leadingContent = { Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                )
            }, {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.about_version)) },
                    supportingContent = { Text("v$versionName ($versionCode)") },
                    leadingContent = { Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
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
                                when (val result = withContext(Dispatchers.IO) { viewModel.checkForUpdates() }) {
                                    is com.newoether.agora.util.UpdateCheckResult.Available ->
                                        viewModel.showUpdateDialog(result.info)
                                    com.newoether.agora.util.UpdateCheckResult.UpToDate ->
                                        updateStatus = context.getString(R.string.about_up_to_date, versionName)
                                    // Surfacing the reason is the whole point: a failed check
                                    // reported as "up to date" hides exactly the case the user
                                    // needs to act on.
                                    is com.newoether.agora.util.UpdateCheckResult.Failed ->
                                        updateStatus = context.getString(R.string.about_check_failed, result.reason)
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
                    // Update source: published releases vs. the newest CI build.
                    var channelMenuOpen by remember { mutableStateOf(false) }
                    val channelLabel = stringResource(
                        if (updateChannel == "ci") R.string.update_channel_ci
                        else R.string.update_channel_stable
                    )
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.update_channel_title)) },
                        supportingContent = {
                            Text(
                                stringResource(
                                    if (updateChannel == "ci") R.string.update_channel_ci_desc
                                    else R.string.update_channel_stable_desc
                                )
                            )
                        },
                        leadingContent = { Icon(Icons.Default.Science, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = {
                            Box {
                                Text(
                                    channelLabel,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                DropdownMenu(
                                    expanded = channelMenuOpen,
                                    onDismissRequest = { channelMenuOpen = false },
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                    tonalElevation = 16.dp,
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    listOf(
                                        "stable" to R.string.update_channel_stable,
                                        "ci" to R.string.update_channel_ci,
                                    ).forEach { (id, labelRes) ->
                                        DropdownMenuItem(
                                            text = { Text(stringResource(labelRes)) },
                                            leadingIcon = {
                                                if (updateChannel == id) {
                                                    Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                                }
                                            },
                                            onClick = {
                                                viewModel.settings.setUpdateChannel(id)
                                                updateStatus = null
                                                channelMenuOpen = false
                                            },
                                        )
                                    }
                                }
                            }
                        },
                        modifier = Modifier.clickable { channelMenuOpen = true },
                    )
                }
                // Live CI build status — only meaningful on the CI channel.
                if (updateChannel == "ci") {
                    add {
                        val s = ciStatus
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.about_ci_status)) },
                            supportingContent = {
                                Text(
                                    when {
                                        s == null -> stringResource(R.string.about_ci_unavailable)
                                        s.status == "queued" -> stringResource(R.string.about_ci_queued, s.run_number)
                                        s.isRunning -> stringResource(R.string.about_ci_running, s.run_number)
                                        s.isSuccess -> stringResource(R.string.about_ci_success, s.run_number)
                                        s.conclusion == "cancelled" -> stringResource(R.string.about_ci_cancelled, s.run_number)
                                        s.isFailure -> stringResource(R.string.about_ci_failed, s.run_number)
                                        else -> stringResource(R.string.about_ci_unavailable)
                                    } + if (s != null && s.title.isNotBlank()) "\n${s.title}" else ""
                                )
                            },
                            leadingContent = {
                                if (s?.isRunning == true) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                } else {
                                    Icon(
                                        when {
                                            s == null -> Icons.Default.CloudOff
                                            s.isSuccess -> Icons.Default.CheckCircle
                                            s.isFailure -> Icons.Default.ErrorOutline
                                            else -> Icons.Default.Pending
                                        },
                                        null,
                                        tint = when {
                                            s == null -> MaterialTheme.colorScheme.onSurfaceVariant
                                            s.isSuccess -> MaterialTheme.colorScheme.primary
                                            s.isFailure -> MaterialTheme.colorScheme.error
                                            else -> MaterialTheme.colorScheme.primary
                                        },
                                    )
                                }
                            },
                            trailingContent = {
                                if (s != null) {
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                                }
                            },
                            modifier = if (s != null && s.html_url.isNotBlank()) {
                                Modifier.clickable { openUrl(s.html_url) }
                            } else Modifier,
                        )
                    }
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
                    leadingContent = { Icon(Icons.Default.Code, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    modifier = Modifier.clickable { openUrl("https://github.com/Mirikawww/Agora") }
                )
            }, {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.about_issue_tracker), modifier = Modifier.padding(vertical = 6.dp)) },
                    leadingContent = { Icon(Icons.Default.BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    modifier = Modifier.clickable { openUrl("https://github.com/Mirikawww/Agora/issues") }
                )
            }, {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.about_contribute), modifier = Modifier.padding(vertical = 6.dp)) },
                    leadingContent = { Icon(Icons.Default.VolunteerActivism, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    modifier = Modifier.clickable { openUrl("https://github.com/Mirikawww/Agora/pulls") }
                )
            }, {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.about_privacy_policy), modifier = Modifier.padding(vertical = 6.dp)) },
                    leadingContent = { Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
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
