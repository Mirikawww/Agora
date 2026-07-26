package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.model.ModelId
import com.newoether.agora.model.ThinkingLevels
import com.newoether.agora.model.apiModelName
import com.newoether.agora.ui.chat.GroupedModelMenuContent
import com.newoether.agora.ui.theme.ChatType
import com.newoether.agora.util.noOpBringIntoView
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single holistic memory settings page.
 *
 * One overall memory blob (active memory file) that can be edited manually or
 * generated in the same card via a compact AI strip. Generation model menu matches
 * the home picker (grouped models + thinking + fast only).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsMemoryPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val accessActiveMemory by viewModel.settings.accessActiveMemory.collectAsState()
    val accessSavedMemories by viewModel.settings.accessSavedMemories.collectAsState()
    val enabledModels by viewModel.settings.enabledModels.collectAsState()
    val disabledProviders by viewModel.settings.disabledProviders.collectAsState()
    val modelAliases by viewModel.settings.modelAliases.collectAsState()
    val modelKeyNicknames by viewModel.settings.modelKeyNicknames.collectAsState()
    val selectedChatModel by viewModel.settings.selectedModel.collectAsState()
    val globalThinkingEnabled by viewModel.settings.thinkingEnabled.collectAsState()
    val globalThinkingLevel by viewModel.settings.thinkingLevel.collectAsState()
    val globalFastEnabled by viewModel.settings.fastEnabled.collectAsState()

    var memoryText by remember { mutableStateOf("") }
    var dirty by remember { mutableStateOf(false) }
    var generating by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var promptText by remember { mutableStateOf("") }
    var generateModel by remember { mutableStateOf("") }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    // Page-local generation controls (do not mutate chat/app defaults).
    var genThinkingEnabled by remember { mutableStateOf(false) }
    var genThinkingLevel by remember { mutableStateOf("medium") }
    var genFastEnabled by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val selectableModels = remember(enabledModels, disabledProviders) {
        enabledModels
            .filter { model ->
                val provider = ModelId.parse(model).providerName
                provider !in disabledProviders
            }
            .toSet()
    }

    val selectedCaps = remember(generateModel, viewModel) {
        if (generateModel.isBlank()) null else viewModel.modelCapabilitiesFor(generateModel)
    }
    val reasoningSupported = selectedCaps?.reasoning == true
    val fastSupported = selectedCaps?.fast == true

    LaunchedEffect(Unit) {
        memoryText = withContext(Dispatchers.IO) { viewModel.memoryManager.getActiveMemory() }
        dirty = false
        dirty = false
        genThinkingEnabled = globalThinkingEnabled
        genThinkingLevel = ThinkingLevels.normalize(globalThinkingLevel)
        genFastEnabled = globalFastEnabled
    }

    // Keep a local generation-model selection; default to chat selected model when available.
    LaunchedEffect(selectableModels, selectedChatModel) {
        if (generateModel.isBlank() || generateModel !in selectableModels) {
            generateModel = when {
                selectedChatModel in selectableModels -> selectedChatModel
                selectableModels.isNotEmpty() -> selectableModels.first()
                else -> selectedChatModel
            }
        }
    }

    // If model loses capability, clear local toggles that no longer apply.
    LaunchedEffect(reasoningSupported, fastSupported) {
        if (!reasoningSupported) genThinkingEnabled = false
        if (!fastSupported) genFastEnabled = false
    }

    // Auto-save memory while typing — no extra Save tap required.
    LaunchedEffect(memoryText) {
        if (!dirty) return@LaunchedEffect
        delay(450)
        if (!dirty) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            viewModel.memoryManager.updateActiveMemory(memoryText, mode = "replace")
        }
        dirty = false
    }

    fun saveMemory() {
        scope.launch(Dispatchers.IO) {
            viewModel.memoryManager.updateActiveMemory(memoryText, mode = "replace")
            withContext(Dispatchers.Main) {
                dirty = false
                status = null
            }
        }
    }

    fun generateMemory(userInstruction: String) {
        if (userInstruction.isBlank() || generating) return
        generating = true
        status = null
        scope.launch {
            try {
                val result = viewModel.generateHolisticMemory(
                    userInstruction = userInstruction,
                    modelIdWithPrefix = generateModel.ifBlank { null },
                    thinkingEnabled = genThinkingEnabled,
                    thinkingLevel = genThinkingLevel,
                    fastEnabled = genFastEnabled,
                )
                if (result != null) {
                    memoryText = result
                    dirty = true
                    promptText = ""
                } else {
                    status = "fail"
                }
            } catch (_: Exception) {
                status = "fail"
            } finally {
                generating = false
            }
        }
    }

    fun displayName(model: String): String {
        val alias = modelAliases[model]
        if (!alias.isNullOrBlank()) return alias
        return ModelId.parse(model).apiModelName
    }

    val thinkingSummary = when {
        !reasoningSupported -> null
        !genThinkingEnabled -> stringResource(R.string.thinking_control_off)
        else -> genThinkingLevel
    }
    val modelSubtitle = buildString {
        append(if (generateModel.isNotBlank()) displayName(generateModel) else stringResource(R.string.select_model))
        thinkingSummary?.let {
            append(" · ")
            append(it)
        }
        if (fastSupported && genFastEnabled) {
            append(" · ")
            append(stringResource(R.string.thinking_control_quick))
        }
    }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.memory_holistic_title),
        onBack = onBack,
    ) {
        SettingsGroupColumn {
            SettingsGroup(
                title = stringResource(R.string.memory_access_title),
                items = listOf(
                    {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.memory_access_active)) },
                            supportingContent = { Text(stringResource(R.string.memory_access_active_desc)) },
                            leadingContent = {
                                Icon(Icons.Default.Memory, null, tint = MaterialTheme.colorScheme.primary)
                            },
                            trailingContent = {
                                Switch(
                                    checked = accessActiveMemory,
                                    onCheckedChange = { viewModel.settings.setAccessActiveMemory(it) }
                                )
                            },
                            modifier = Modifier.clickable {
                                viewModel.settings.setAccessActiveMemory(!accessActiveMemory)
                            }
                        )
                    },
                    {
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.memory_access_saved)) },
                            supportingContent = { Text(stringResource(R.string.memory_access_saved_desc)) },
                            leadingContent = {
                                Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                            },
                            trailingContent = {
                                Switch(
                                    checked = accessSavedMemories,
                                    onCheckedChange = { viewModel.settings.setAccessSavedMemories(it) }
                                )
                            },
                            modifier = Modifier.clickable {
                                viewModel.settings.setAccessSavedMemories(!accessSavedMemories)
                            }
                        )
                    }
                )
            )

            Text(
                text = stringResource(R.string.memory_holistic_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )

            // Merged editor + AI generate in one card.
            SettingsGroup(
                title = stringResource(R.string.memory_holistic_editor),
                items = listOf({
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        OutlinedTextField(
                            value = memoryText,
                            onValueChange = {
                                memoryText = it
                                dirty = true
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 220.dp)
                                .noOpBringIntoView(),
                            shape = RoundedCornerShape(16.dp),
                            minLines = 10,
                            textStyle = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        if (status == "fail") {
                            Text(
                                stringResource(R.string.memory_generate_failed),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Model / thinking / fast picker (home menu subset).
                        Box {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = selectableModels.isNotEmpty() && !generating) {
                                        modelMenuExpanded = true
                                    }
                                    .padding(bottom = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        stringResource(R.string.memory_generate_model),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = modelSubtitle,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            DropdownMenu(
                                expanded = modelMenuExpanded,
                                onDismissRequest = { modelMenuExpanded = false },
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ) {
                                GroupedModelMenuContent(
                                    enabledModels = selectableModels,
                                    selectedModel = generateModel,
                                    modelAliases = modelAliases,
                                    modelKeyNicknames = modelKeyNicknames,
                                    thinkingEnabled = genThinkingEnabled,
                                    thinkingLevel = genThinkingLevel,
                                    reasoningSupported = reasoningSupported,
                                    fastSupported = fastSupported,
                                    fastEnabled = genFastEnabled,
                                    builtInSearchEnabled = false,
                                    externalSearchEnabled = false,
                                    onModelSelect = { model ->
                                        generateModel = model
                                        modelMenuExpanded = false
                                    },
                                    onModelLongPress = { model ->
                                        // Page-local only — do not set app default model.
                                        generateModel = model
                                        modelMenuExpanded = false
                                    },
                                    onThinkingSelect = { effort ->
                                        if (effort == null) {
                                            genThinkingEnabled = false
                                        } else {
                                            genThinkingEnabled = true
                                            genThinkingLevel = ThinkingLevels.normalize(effort)
                                        }
                                        modelMenuExpanded = false
                                    },
                                    onFastToggle = { enabled ->
                                        genFastEnabled = enabled
                                        // Keep menu open for switch feel? Home dismisses via row click path;
                                        // toggle leaves menu open until outside dismiss — match home by not forcing close.
                                    },
                                    onDismissAll = { modelMenuExpanded = false },
                                    showSearchControls = false,
                                    showAdvanced = false,
                                    showSettingsLinks = false,
                                )
                            }
                        }

                        if (generating) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(bottom = 8.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    stringResource(R.string.memory_generating),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(22.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            tonalElevation = 0.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(vertical = 8.dp)
                                        .noOpBringIntoView()
                                ) {
                                    if (promptText.isEmpty()) {
                                        Text(
                                            stringResource(R.string.memory_generate_hint),
                                            style = ChatType.input,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        )
                                    }
                                    BasicTextField(
                                        value = promptText,
                                        onValueChange = { promptText = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        textStyle = ChatType.input.copy(color = MaterialTheme.colorScheme.onSurface),
                                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                        maxLines = 4,
                                    )
                                }
                                val canSend = promptText.isNotBlank() && !generating && generateModel.isNotBlank()
                                Surface(
                                    onClick = {
                                        if (canSend) generateMemory(promptText)
                                    },
                                    enabled = canSend,
                                    shape = CircleShape,
                                    color = if (canSend) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = if (canSend) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.Send,
                                            contentDescription = stringResource(R.string.memory_generate),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                })
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
