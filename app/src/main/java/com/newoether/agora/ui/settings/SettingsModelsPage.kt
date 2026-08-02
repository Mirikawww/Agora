package com.newoether.agora.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.model.apiModelName
import com.newoether.agora.ui.components.clearFocusOnTap
import com.newoether.agora.ui.components.providerIcon
import com.newoether.agora.ui.components.providerIconTint
import com.newoether.agora.ui.theme.LocalIsMonochrome
import com.newoether.agora.util.Constants
import com.newoether.agora.util.noOpBringIntoView
import com.newoether.agora.viewmodel.ChatViewModel

// Explicit green/red for sync change badges under Mono (scheme tertiary/error are grayscale there).
private val MonoBadgeGreen = Color(0xFF2E7D32)
private val MonoBadgeGreenContainer = Color(0xFFC8E6C9)
private val MonoBadgeOnGreenContainer = Color(0xFF1B5E20)
private val MonoBadgeRed = Color(0xFFD32F2F)
private val MonoBadgeRedContainer = Color(0xFFFFCDD2)
private val MonoBadgeOnRedContainer = Color(0xFFB71C1C)

// Shape constants matching SettingsGroup's per-position rounding.
// Each encodes top-corners / bottom-corners for its place in the group.
private val FullRounded   = RoundedCornerShape(24.dp)
private val TopRounded    = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 5.dp, bottomEnd = 5.dp)
private val BottomRounded = RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp, bottomStart = 24.dp, bottomEnd = 24.dp)
private val MidRounded    = RoundedCornerShape(5.dp)
private val FlatShape     = RoundedCornerShape(0.dp)
private val FlatToBottom  = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
private val FiveTop       = RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp)
private val FiveBottom    = RoundedCornerShape(bottomStart = 5.dp, bottomEnd = 5.dp)

private data class ModelSyncChange(
    val added: Set<String>,
    val removed: Set<String>,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsModelsPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val enabledModels by viewModel.settings.enabledModels.collectAsState()
    val availableModels by viewModel.settings.availableModels.collectAsState()
    val modelAliases by viewModel.settings.modelAliases.collectAsState()
    val modelKeyNicknames by viewModel.settings.modelKeyNicknames.collectAsState()
    val selectedModel by viewModel.settings.selectedModel.collectAsState()
    val disabledProviders by viewModel.settings.disabledProviders.collectAsState()
    val isSyncingModels by viewModel.isSyncingModels.collectAsState()
    var showActiveModelDialog by remember { mutableStateOf(false) }
    var showModelAliasDialog by remember { mutableStateOf<String?>(null) }
    val expandedProviders = remember { mutableStateMapOf<String, MutableTransitionState<Boolean>>() }
    val modelBlockHeights = remember { mutableStateMapOf<String, Float>() }
    val syncChanges = remember { mutableStateMapOf<String, ModelSyncChange>() }
    var syncBaseline by remember { mutableStateOf<Map<String, List<String>>?>(null) }
    var observedSyncRunning by remember { mutableStateOf(false) }

    val lastFingerprint by viewModel.settings.lastModelsFetchFingerprint.collectAsState()

    val beginModelSync: () -> Unit = {
        if (!isSyncingModels) {
            syncBaseline = availableModels.mapValues { (_, models) -> models.toList() }
            syncChanges.clear()
            observedSyncRunning = false
            viewModel.fetchAvailableModels()
        }
    }

    LaunchedEffect(isSyncingModels) {
        if (isSyncingModels) {
            observedSyncRunning = true
        } else if (observedSyncRunning) {
            val before = syncBaseline.orEmpty()
            val after = viewModel.settings.getAvailableModels()
            val changes = (before.keys + after.keys).mapNotNull { provider ->
                val previousModels = before[provider].orEmpty().toSet()
                val currentModels = after[provider].orEmpty().toSet()
                val added = currentModels - previousModels
                val removed = previousModels - currentModels
                if (added.isEmpty() && removed.isEmpty()) null
                else provider to ModelSyncChange(added = added, removed = removed)
            }.toMap()
            syncChanges.clear()
            syncChanges.putAll(changes)
            syncBaseline = null
            observedSyncRunning = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            syncChanges.clear()
            syncBaseline = null
        }
    }

    val displayedAvailableModels = remember(availableModels, syncChanges.toMap()) {
        buildMap {
            availableModels.forEach { (provider, models) -> put(provider, models) }
            syncChanges.forEach { (provider, change) ->
                val current = get(provider).orEmpty()
                put(provider, (current + change.removed).distinct())
            }
        }
    }
    val providers = displayedAvailableModels.entries.filter { (name, models) ->
        models.isNotEmpty() && (name == Constants.PROVIDER_LOCAL || name !in disabledProviders)
    }
    val visibleEnabledModels = remember(enabledModels, disabledProviders) {
        enabledModels.filter {
            val p = com.newoether.agora.model.ModelId.parse(it).providerName
            p.equals(Constants.PROVIDER_LOCAL, ignoreCase = true) || p !in disabledProviders
        }
    }

    // Auto-fetch models when entering the page if provider config has changed
    LaunchedEffect(Unit) {
        val current = viewModel.computeProviderFingerprint()
        if (current != lastFingerprint) {
            beginModelSync()
        }
    }

    CollapsingSettingsLazyScaffold(
        title = stringResource(R.string.models_title),
        onBack = onBack,
        contentHorizontalPadding = 0.dp,
    ) {
            // ── Default Model section ──
            item(key = "section_default_title") {
                SectionLabel(
                    text = stringResource(R.string.models_default),
                    firstInPage = true
                )
            }

            item(key = "default_model") {
                val activeAlias = modelAliases[selectedModel]
                val activeParsed = com.newoether.agora.model.ModelId.parse(selectedModel)
                val providerName = activeParsed.providerName
                val activeDisplayName = activeAlias ?: activeParsed.apiModelName
                val activeIconRes = providerIcon(providerName)
                val isActiveLocal = providerName.equals(Constants.PROVIDER_LOCAL, ignoreCase = true)
                val hasEnabledModels = visibleEnabledModels.isNotEmpty()

                CardSurface(shape = FullRounded) {
                    SettingsItem(
                        headlineContent = {
                            Text(
                                if (!hasEnabledModels) stringResource(R.string.models_no_models) else activeDisplayName,
                                color = if (!hasEnabledModels) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                            )
                        },
                        supportingContent = if (hasEnabledModels) {
                            { Text(providerName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) }
                        } else null,
                        leadingContent = {
                            val tint = if (hasEnabledModels) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            val providerTint = if (hasEnabledModels) MaterialTheme.colorScheme.onSurface else tint
                            when {
                                !hasEnabledModels -> Icon(Icons.Default.Chat, null, tint = tint, modifier = Modifier.size(24.dp))
                                isActiveLocal -> Icon(Icons.Default.AutoAwesome, null, tint = tint, modifier = Modifier.size(24.dp))
                                activeIconRes != 0 -> Icon(painterResource(activeIconRes), null, tint = providerIconTint(providerName, providerTint), modifier = Modifier.size(24.dp))
                                else -> Icon(Icons.Default.Chat, null, tint = tint, modifier = Modifier.size(24.dp))
                            }
                        },
                        modifier = Modifier.heightIn(min = 66.dp).clickable(enabled = hasEnabledModels) { showActiveModelDialog = true }
                    )
                }
            }

            // ── Available Models section ──
            item(key = "section_available_title") {
                SectionLabel(
                    text = stringResource(R.string.models_available),
                    firstInPage = false
                )
            }

            // Sync button – always first in the Available card
            val hasProviders = providers.isNotEmpty()
            item(key = "sync") {
                CardSurface(
                    shape = if (hasProviders) TopRounded else FullRounded
                ) {
                    SettingsItem(
                        headlineContent = { Text(stringResource(R.string.models_sync)) },
                        supportingContent = { Text(stringResource(R.string.models_sync_desc)) },
                        leadingContent = { Icon(Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable(enabled = !isSyncingModels) { beginModelSync() }
                    )
                }
            }

            // Providers
            for ((providerIndex, entry) in providers.withIndex()) {
                val (name, models) = entry
                val providerChange = syncChanges[name]
                val providerChanged = providerChange != null
                val transitionState = expandedProviders.getOrPut(name) { MutableTransitionState(false) }
                val isExpanded = transitionState.targetState
                val isLastProvider = providerIndex == providers.lastIndex

                // ── Provider header ──
                item(key = "hdr_$name") {
                    // Bottom corners track model block height:
                    // height >= radius → ratio=0 → flat (merge with content)
                    // height = 0     → ratio=1 → fully rounded
                    // Interpolates linearly in [0, radius].
                    val collapsedRadiusDp = if (isLastProvider) 24f else 5f
                    val currentHeight = modelBlockHeights[name] ?: 0f
                    val ratio = (1f - currentHeight / collapsedRadiusDp).coerceIn(0f, 1f)
                    val bottomRadius = (collapsedRadiusDp * ratio).dp
                    val headerShape = RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp, bottomStart = bottomRadius, bottomEnd = bottomRadius)

                    CardSurface(shape = headerShape, addTopGap = true) {
                        val headerIconRes = providerIcon(name)
                        val isLocalHeader = name.equals(Constants.PROVIDER_LOCAL, ignoreCase = true)
                        SettingsItem(
                            headlineContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(name)
                                    if (providerChanged) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        ModelChangeBadge(
                                            text = stringResource(R.string.models_change_badge),
                                            containerColor = MaterialTheme.colorScheme.primary,
                                            contentColor = MaterialTheme.colorScheme.onPrimary
                                        )
                                    }
                                }
                            },
                            supportingContent = {
                                Text(stringResource(R.string.models_count, availableModels[name].orEmpty().size))
                            },
                            leadingContent = {
                                when {
                                    isLocalHeader -> Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                    headerIconRes != 0 -> Icon(painterResource(headerIconRes), null, tint = providerIconTint(name, MaterialTheme.colorScheme.onSurface), modifier = Modifier.size(24.dp))
                                    else -> Icon(Icons.Default.Cloud, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                }
                            },
                            trailingContent = {
                                Icon(
                                    if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = null
                                )
                            },
                            modifier = Modifier.clickable {
                                transitionState.targetState = !transitionState.targetState
                            }
                        )
                    }
                }

                // ── Model block (one AnimatedVisibility → Column, like the original) ──
                item(key = "models_$name") {
                    val density = LocalDensity.current
                    AnimatedVisibility(
                        visibleState = transitionState,
                        enter = expandVertically(),
                        exit = shrinkVertically(),
                        modifier = Modifier.onGloballyPositioned { coordinates ->
                            modelBlockHeights[name] = coordinates.size.height / density.density
                        }
                    ) {
                        Column {
                            for ((modelIndex, model) in models.withIndex()) {
                                val isLastModel = modelIndex == models.lastIndex
                                // Within the block models touch seamlessly (FlatShape).
                                // The last model closes the group: 24dp if last provider, else 5dp.
                                val modelShape = when {
                                    isLastModel && isLastProvider -> FlatToBottom
                                    isLastModel -> FiveBottom
                                    else -> FlatShape
                                }

                                CardSurface(shape = modelShape, addTopGap = false) {
                                    val isEnabled = enabledModels.contains(model)
                                    val alias = modelAliases[model]
                                    val parsed = com.newoether.agora.model.ModelId.parse(model)
                                    val displayName = alias ?: parsed.apiModelName
                                    val isAdded = model in providerChange?.added.orEmpty()
                                    val isRemoved = model in providerChange?.removed.orEmpty()
                                    val modelTextColor = if (isRemoved) {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }

                                    SettingsItem(
                                        headlineContent = {
                                            val keyNicks = modelKeyNicknames[model].orEmpty()
                                            val isMono = LocalIsMonochrome.current
                                            val nickContainer = if (isMono) {
                                                MaterialTheme.colorScheme.onSurface
                                            } else {
                                                MaterialTheme.colorScheme.secondaryContainer
                                            }
                                            val nickContent = if (isMono) {
                                                MaterialTheme.colorScheme.surface
                                            } else {
                                                MaterialTheme.colorScheme.onSecondaryContainer
                                            }
                                            val addedContainer = if (isMono) MonoBadgeGreenContainer else MaterialTheme.colorScheme.tertiaryContainer
                                            val addedContent = if (isMono) MonoBadgeOnGreenContainer else MaterialTheme.colorScheme.onTertiaryContainer
                                            val removedContainer = if (isMono) MonoBadgeRedContainer else MaterialTheme.colorScheme.errorContainer
                                            val removedContent = if (isMono) MonoBadgeOnRedContainer else MaterialTheme.colorScheme.onErrorContainer
                                            Column(modifier = Modifier.fillMaxWidth()) {
                                                Text(displayName, color = modelTextColor)
                                                // Key nicknames + change badges wrap under the model name.
                                                if (keyNicks.isNotEmpty() || isAdded || isRemoved) {
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    FlowRow(
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                                    ) {
                                                        keyNicks.forEach { nick ->
                                                            ModelChangeBadge(
                                                                text = nick,
                                                                containerColor = nickContainer,
                                                                contentColor = nickContent
                                                            )
                                                        }
                                                        when {
                                                            isAdded -> ModelChangeBadge(
                                                                text = stringResource(R.string.models_added_badge),
                                                                containerColor = addedContainer,
                                                                contentColor = addedContent
                                                            )
                                                            isRemoved -> ModelChangeBadge(
                                                                text = stringResource(R.string.models_removed_badge),
                                                                containerColor = removedContainer,
                                                                contentColor = removedContent
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                        supportingContent = if (alias != null) {
                                            {
                                                Text(
                                                    parsed.apiModelName,
                                                    color = if (isRemoved) modelTextColor else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        } else null,
                                        trailingContent = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                IconButton(
                                                    onClick = { showModelAliasDialog = model },
                                                    enabled = !isRemoved
                                                ) {
                                                    Icon(
                                                        Icons.Default.Edit,
                                                        contentDescription = stringResource(R.string.models_edit),
                                                        tint = if (isRemoved) {
                                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                                        } else {
                                                            MaterialTheme.colorScheme.primary
                                                        },
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                                Checkbox(
                                                    checked = isEnabled,
                                                    enabled = !isRemoved,
                                                    onCheckedChange = {
                                                        viewModel.settings.setEnabledModels(if (it) enabledModels + model else enabledModels - model)
                                                    }
                                                )
                                            }
                                        },
                                        modifier = Modifier.padding(start = 16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
    }

    // ── Active Model Dialog ──
    if (showActiveModelDialog) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showActiveModelDialog = false },
            title = { Text(stringResource(R.string.models_select_default), fontWeight = FontWeight.Bold) },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(visibleEnabledModels) { model ->
                        val alias = modelAliases[model]
                        val parsed = com.newoether.agora.model.ModelId.parse(model)
                        val displayName = alias ?: parsed.apiModelName
                        val providerName = parsed.providerName

                        SettingsItem(
                            headlineContent = {
                                Text(displayName, fontWeight = if (model == selectedModel) FontWeight.Bold else FontWeight.Normal)
                            },
                            supportingContent = {
                                Text(providerName, style = MaterialTheme.typography.bodySmall)
                            },
                            leadingContent = {
                                RadioButton(
                                    selected = model == selectedModel,
                                    onClick = {
                                        viewModel.settings.setSelectedModel(model)
                                        showActiveModelDialog = false
                                    }
                                )
                            },
                            modifier = Modifier.clickable {
                                viewModel.settings.setSelectedModel(model)
                                showActiveModelDialog = false
                            }
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showActiveModelDialog = false }) { Text(stringResource(R.string.provider_close)) } }
        )
    }

    // ── Edit Model Dialog (alias + fast override) ──
    showModelAliasDialog?.let { model ->
        val aliasState = rememberTextFieldState(modelAliases[model] ?: "")
        val modelFastSupport by viewModel.settings.modelFastSupport.collectAsState()
        val fastDetected = modelFastSupport[model]
        var fastEnabled by remember(model, fastDetected) {
            mutableStateOf(fastDetected == true)
        }

        AlertDialog(
            modifier = Modifier.clearFocusOnTap(),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showModelAliasDialog = null },
            title = { Text(stringResource(R.string.models_edit), fontWeight = FontWeight.Bold) },
            text = {
                val parsed = com.newoether.agora.model.ModelId.parse(model)
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.models_edit_current, parsed.apiModelName),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(modifier = Modifier.noOpBringIntoView()) {
                        OutlinedTextField(
                            state = aliasState,
                            label = { Text(stringResource(R.string.models_alias_hint)) },
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(parsed.apiModelName) }
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    // Manual fast-mode override (auto-detect can miss some models).
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.models_fast_override),
                                style = MaterialTheme.typography.bodyLarge,
                                // AlertDialog text slot defaults to onSurfaceVariant; keep title fully opaque.
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                stringResource(R.string.models_fast_override_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = fastEnabled,
                            onCheckedChange = { fastEnabled = it }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.settings.updateModelAlias(model, aliasState.text.toString())
                    viewModel.settings.setModelFastOverride(model, fastEnabled)
                    showModelAliasDialog = null
                }) { Text(stringResource(R.string.provider_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showModelAliasDialog = null }) {
                    Text(stringResource(R.string.provider_cancel))
                }
            }
        )
    }
}


/**
 * Section title matching SettingsGroup's label style.
 * [firstInPage] = true for the first section on the page (no extra top gap);
 * subsequent sections get a 24dp gap above to match SettingsGroup's bottom padding.
 */
@Composable
private fun SectionLabel(text: String, firstInPage: Boolean) {
    val topPadding = if (firstInPage) 12.dp else 36.dp
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 32.dp, end = 16.dp, top = topPadding, bottom = 12.dp)
    )
}

/**
 * A single Surface card matching SettingsGroup's style.
 * [addTopGap] adds a 2dp gap above when true (for items after the first in a group).
 */
@Composable
private fun CardSurface(shape: Shape, addTopGap: Boolean = false, content: @Composable () -> Unit) {
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .then(if (addTopGap) Modifier.padding(top = 2.dp) else Modifier)
    ) {
        content()
    }
}

@Composable
private fun ModelChangeBadge(
    text: String,
    containerColor: Color,
    contentColor: Color,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = containerColor,
        contentColor = contentColor
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
        )
    }
}
