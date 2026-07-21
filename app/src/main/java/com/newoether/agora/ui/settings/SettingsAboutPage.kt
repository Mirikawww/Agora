package com.newoether.agora.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
                    headlineContent = { Text(stringResource(R.string.about_developer)) },
                    supportingContent = { Text(stringResource(R.string.about_developer_name)) },
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
            })

            // -- Documentation --
            val showDocFab by viewModel.settings.showDocumentationFab.collectAsState()
            SettingsGroup(title = stringResource(R.string.documentation), items = listOf({
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.show_documentation_links)) },
                    supportingContent = { Text(stringResource(R.string.show_documentation_links_desc)) },
                    leadingContent = { Icon(Icons.Default.MenuBook, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = {
                        Switch(checked = showDocFab, onCheckedChange = { viewModel.settings.setShowDocumentationFab(it) })
                    },
                    modifier = Modifier.clickable { viewModel.settings.setShowDocumentationFab(!showDocFab) }
                )
            }))

            // -- Links --
            SettingsGroup(title = stringResource(R.string.about_links), items = listOf({
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.about_github), modifier = Modifier.padding(vertical = 6.dp)) },
                    leadingContent = { Icon(Icons.Default.Code, contentDescription = null) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    modifier = Modifier.clickable { openUrl("https://github.com/newo-ether/Agora") }
                )
            }, {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.about_issue_tracker), modifier = Modifier.padding(vertical = 6.dp)) },
                    leadingContent = { Icon(Icons.Default.BugReport, contentDescription = null) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    modifier = Modifier.clickable { openUrl("https://github.com/newo-ether/Agora/issues") }
                )
            }, {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.about_contribute), modifier = Modifier.padding(vertical = 6.dp)) },
                    leadingContent = { Icon(Icons.Default.VolunteerActivism, contentDescription = null) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    modifier = Modifier.clickable { openUrl("https://github.com/newo-ether/Agora/pulls") }
                )
            }, {
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.about_privacy_policy), modifier = Modifier.padding(vertical = 6.dp)) },
                    leadingContent = { Icon(Icons.Default.VerifiedUser, contentDescription = null) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    modifier = Modifier.clickable { openUrl("https://github.com/newo-ether/Agora/blob/master/PRIVACY.md") }
                )
            }))
        }
    }
}
