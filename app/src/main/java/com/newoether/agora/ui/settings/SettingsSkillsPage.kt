package com.newoether.agora.ui.settings

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newoether.agora.R
import com.newoether.agora.data.SkillEntry
import com.newoether.agora.ui.components.CircularBackButton
import com.newoether.agora.ui.components.CircularIconButton
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSkillsPage(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    onOpenStore: () -> Unit = {},
) {
    val context = LocalContext.current
    val skillsEnabled by viewModel.settings.skillsEnabled.collectAsState()
    val skills by viewModel.skillsManager.skills.collectAsState()
    var pendingUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var namingUri by remember { mutableStateOf<Uri?>(null) }
    var nameDraft by remember { mutableStateOf("") }
    var nameHint by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<SkillEntry?>(null) }

    // Provider-style list ↔ editor transition key.
    var editingSkillId by rememberSaveable { mutableStateOf<String?>(null) }
    val listScrollState = rememberSaveable(saver = androidx.compose.foundation.ScrollState.Saver) {
        androidx.compose.foundation.ScrollState(0)
    }

    fun fileNameOf(uri: Uri): String {
        return try {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) c.getString(idx) else null
                    } else null
                } ?: uri.lastPathSegment ?: "skill.md"
        } catch (_: Exception) {
            uri.lastPathSegment ?: "skill.md"
        }
    }

    fun advanceNamingQueue(queue: List<Uri>) {
        if (queue.isEmpty()) {
            pendingUris = emptyList()
            namingUri = null
            return
        }
        val head = queue.first()
        pendingUris = queue.drop(1)
        namingUri = head
        val fn = fileNameOf(head)
        nameHint = fn.removeSuffix(".md").removeSuffix(".MD")
        nameDraft = ""
    }

    val multiPick = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        advanceNamingQueue(uris)
    }

    fun confirmImport() {
        val uri = namingUri ?: return
        val content = try {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
        } catch (_: Exception) {
            ""
        }
        if (content.isBlank()) {
            advanceNamingQueue(pendingUris)
            return
        }
        val fn = fileNameOf(uri)
        val name = nameDraft.trim().ifBlank { nameHint.ifBlank { fn } }
        val entry = viewModel.skillsManager.importMarkdown(name, content, fn)
        advanceNamingQueue(pendingUris)
        // Jump into the immersive editor for the freshly imported skill.
        editingSkillId = entry.id
    }

    BackHandler {
        if (editingSkillId != null) {
            editingSkillId = null
        } else {
            onBack()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        GuardedAnimatedContent(
            targetState = editingSkillId,
            forward = editingSkillId != null,
        ) { skillId ->
            val skill = skillId?.let { id -> skills.firstOrNull { it.id == id } }
            if (skillId != null && skill != null) {
                SettingsSkillEditorPage(
                    skill = skill,
                    onBack = { editingSkillId = null },
                    onSave = { name, content ->
                        viewModel.skillsManager.updateContent(
                            id = skill.id,
                            content = content,
                            newName = name,
                        )
                    },
                    onDelete = {
                        viewModel.skillsManager.delete(skill.id)
                        editingSkillId = null
                    },
                )
            } else {
                if (skillId != null && skill == null) {
                    // Skill deleted elsewhere — drop back to list.
                    LaunchedEffect(skillId) { editingSkillId = null }
                }
                CollapsingSettingsScaffold(
                    title = stringResource(R.string.settings_skills),
                    onBack = onBack,
                    scrollState = listScrollState,
                ) {
                    SettingsGroupColumn {
                        SettingsGroup(
                            title = stringResource(R.string.settings_skills),
                            items = listOf({
                                SettingsItem(
                                    headlineContent = { Text(stringResource(R.string.skills_enabled)) },
                                    supportingContent = { Text(stringResource(R.string.skills_enabled_desc)) },
                                    trailingContent = {
                                        Switch(
                                            checked = skillsEnabled,
                                            onCheckedChange = { viewModel.settings.setSkillsEnabled(it) },
                                        )
                                    },
                                )
                            }),
                        )
                        SettingsGroup(
                            title = stringResource(R.string.skills_store_section),
                            items = listOf({
                                SettingsItem(
                                    headlineContent = { Text(stringResource(R.string.skills_store_title)) },
                                    supportingContent = { Text(stringResource(R.string.skills_store_entry_desc)) },
                                    leadingContent = {
                                        Icon(
                                            Icons.Default.Storefront,
                                            null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    trailingContent = {
                                        Icon(Icons.Default.ChevronRight, contentDescription = null)
                                    },
                                    modifier = Modifier.clickable { onOpenStore() },
                                )
                            }),
                        )
                        SettingsGroup(
                            title = stringResource(R.string.skills_library),
                            items = buildList {
                                add {
                                    SettingsItem(
                                        headlineContent = { Text(stringResource(R.string.skills_import_md)) },
                                        supportingContent = { Text(stringResource(R.string.skills_import_md_desc)) },
                                        leadingContent = {
                                            Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                                        },
                                        modifier = Modifier.clickable {
                                            multiPick.launch(
                                                arrayOf(
                                                    "text/*",
                                                    "text/markdown",
                                                    "application/octet-stream",
                                                    "*/*",
                                                ),
                                            )
                                        },
                                    )
                                }
                                skills.forEach { entry ->
                                    add {
                                        SettingsItem(
                                            headlineContent = { Text(entry.name) },
                                            supportingContent = {
                                                Text(
                                                    buildString {
                                                        entry.source?.takeIf { it.isNotBlank() }?.let {
                                                            append(it)
                                                            append(" · ")
                                                        }
                                                        append(
                                                            entry.content.lineSequence().firstOrNull()?.take(80)
                                                                ?: entry.fileName,
                                                        )
                                                    },
                                                )
                                            },
                                            leadingContent = {
                                                Icon(
                                                    Icons.Default.Description,
                                                    null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                )
                                            },
                                            trailingContent = {
                                                IconButton(onClick = { pendingDelete = entry }) {
                                                    Icon(
                                                        Icons.Default.Delete,
                                                        contentDescription = stringResource(R.string.delete),
                                                        tint = MaterialTheme.colorScheme.error,
                                                    )
                                                }
                                            },
                                            modifier = Modifier.clickable { editingSkillId = entry.id },
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    namingUri?.let {
        AlertDialog(
            onDismissRequest = { advanceNamingQueue(pendingUris) },
            title = { Text(stringResource(R.string.skills_name_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.skills_name_desc))
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = nameDraft,
                        onValueChange = { nameDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text(nameHint) },
                        label = { Text(stringResource(R.string.skills_name_label)) },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { confirmImport() }) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { advanceNamingQueue(pendingUris) }) {
                    Text(stringResource(R.string.skip))
                }
            },
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.skills_delete_title)) },
            text = { Text(stringResource(R.string.skills_delete_desc, target.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = target.id
                        pendingDelete = null
                        if (editingSkillId == id) editingSkillId = null
                        viewModel.skillsManager.delete(id)
                    },
                ) {
                    Text(
                        stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/**
 * Immersive full-bleed markdown editor for a local skill.
 * Not a settings-card form: top chrome + edge-to-edge body text.
 */
@Composable
private fun SettingsSkillEditorPage(
    skill: SkillEntry,
    onBack: () -> Unit,
    onSave: (name: String, content: String) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(skill.id) { mutableStateOf(skill.name) }
    var content by remember(skill.id) { mutableStateOf(skill.content) }
    var dirty by remember(skill.id) { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val bodyScroll = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    fun persistIfNeeded() {
        if (!dirty) return
        val trimmedName = name.trim().ifBlank { skill.name }
        onSave(trimmedName, content)
        dirty = false
    }

    fun leave() {
        persistIfNeeded()
        onBack()
    }

    BackHandler { leave() }

    // Soft autofocus into the body once after open — feels like opening a note.
    LaunchedEffect(skill.id) {
        delay(280)
        runCatching { focusRequester.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top chrome: back · name · save  (hugs content, no collapsing large title)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 12.dp, top = statusBarTop + 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularBackButton(
                    onClick = { leave() },
                    contentDescription = stringResource(R.string.back),
                )
                Spacer(Modifier.width(12.dp))
                BasicTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        dirty = true
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { inner ->
                        Box {
                            if (name.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.skills_name_label),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            inner()
                        }
                    },
                )
                Spacer(Modifier.width(8.dp))
                CircularIconButton(
                    icon = Icons.Default.Save,
                    onClick = {
                        persistIfNeeded()
                    },
                    contentDescription = stringResource(R.string.skills_save),
                    enabled = dirty,
                )
            }

            // Full-bleed body — no outlined card, no section chrome.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                if (content.isEmpty()) {
                    Text(
                        text = stringResource(R.string.skills_content_hint),
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                        ),
                    )
                }
                BasicTextField(
                    value = content,
                    onValueChange = {
                        content = it
                        dirty = true
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(bodyScroll)
                        .focusRequester(focusRequester),
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                )
            }

            // Bottom danger affordance — quiet, not a settings row dump.
            TextButton(
                onClick = { showDeleteConfirm = true },
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(end = 12.dp, bottom = 8.dp),
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.skills_delete_title)) },
            text = { Text(stringResource(R.string.skills_delete_desc, skill.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        scope.launch {
                            onDelete()
                        }
                    },
                ) {
                    Text(
                        stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
