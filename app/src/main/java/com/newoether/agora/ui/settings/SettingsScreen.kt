package com.newoether.agora.ui.settings

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip

import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.StringRes
import com.newoether.agora.R
import com.newoether.agora.ui.common.McpServerIcon
import com.newoether.agora.ui.settings.datacontrol.SettingsDataControlPage
import com.newoether.agora.viewmodel.ChatViewModel

/** When true, [SettingsGroup] inside a [SettingsGroupColumn] suppresses its own bottom padding
 *  (spacing is handled by the column's [Arrangement.spacedBy] instead). */
val LocalSettingsGroupSpacing = staticCompositionLocalOf { false }

/** Settings page content container: uniform 24dp spacing between groups (and any other elements),
 *  with zero trailing after the last element. */
@Composable
fun SettingsGroupColumn(
    modifier: Modifier = Modifier,
    spacing: Dp = 24.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    CompositionLocalProvider(LocalSettingsGroupSpacing provides true) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content
        )
    }
}

@Composable
fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    bottomPadding: androidx.compose.ui.unit.Dp = 24.dp,
    items: List<@Composable () -> Unit>
) {
    val effectiveBottom = if (LocalSettingsGroupSpacing.current) 0.dp else bottomPadding
    Column(modifier = modifier.fillMaxWidth().padding(bottom = effectiveBottom)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            items.forEachIndexed { index, item ->
                if (index > 0) {
                    Spacer(modifier = Modifier.height(2.dp))
                }
                val isFirst = index == 0
                val isLast = index == items.lastIndex
                val shape = when {
                    items.size == 1 -> RoundedCornerShape(24.dp)
                    isFirst -> RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 5.dp, bottomEnd = 5.dp)
                    isLast -> RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp, bottomStart = 24.dp, bottomEnd = 24.dp)
                    else -> RoundedCornerShape(5.dp)
                }
                Surface(
                    shape = shape,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item()
                }
            }
        }
    }
}

@Composable
fun SettingsItem(
    modifier: Modifier = Modifier,
    headlineContent: @Composable () -> Unit,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    leadingSpacing: Dp = 16.dp,
) {
    val verticalPadding = if (supportingContent == null) 12.dp else 16.dp
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingContent != null) {
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                leadingContent()
            }
            Spacer(modifier = Modifier.width(leadingSpacing))
        }
        Column(modifier = Modifier.weight(1f)) {
            CompositionLocalProvider(
                LocalTextStyle provides MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                LocalContentColor provides MaterialTheme.colorScheme.onSurface
            ) {
                headlineContent()
            }
            if (supportingContent != null) {
                Spacer(modifier = Modifier.height(3.dp))
                CompositionLocalProvider(
                    LocalTextStyle provides MaterialTheme.typography.bodyMedium,
                    LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant
                ) {
                    supportingContent()
                }
            }
        }
        if (trailingContent != null) {
            Spacer(modifier = Modifier.width(16.dp))
            trailingContent()
        }
    }
}

private data class SettingsCategory(
    val key: String,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    val icon: ImageVector
)

private data class SettingsGroupData(
    val titleRes: Int? = null,
    val items: List<SettingsCategory>
)

private val settingsGroups = listOf(
    SettingsGroupData(titleRes = R.string.settings_group_services, items = listOf(
        SettingsCategory("provider", R.string.settings_provider, R.string.settings_provider_desc, Icons.Default.Cloud),
        SettingsCategory("models", R.string.settings_models, R.string.settings_models_desc, Icons.Default.Chat),
    )),
    SettingsGroupData(titleRes = R.string.settings_group_responses, items = listOf(
        SettingsCategory("prompts", R.string.settings_prompts, R.string.settings_prompts_desc, Icons.Default.Psychology),
        SettingsCategory("generation", R.string.settings_generation, R.string.settings_generation_desc, Icons.Default.Tune),
        SettingsCategory("titlegen", R.string.settings_title_gen, R.string.settings_title_gen_desc, Icons.Default.Edit),
    )),
    SettingsGroupData(titleRes = R.string.settings_group_multimodal, items = listOf(
        SettingsCategory("transcription", R.string.settings_transcription, R.string.settings_transcription_desc, Icons.Default.ImageSearch),
        SettingsCategory("imagegen", R.string.settings_image_gen, R.string.settings_image_gen_desc, Icons.Default.AddPhotoAlternate),
    )),
    SettingsGroupData(titleRes = R.string.settings_group_tools, items = listOf(
        SettingsCategory("websearch", R.string.settings_web_search, R.string.settings_web_search_desc, Icons.Default.Language),
        SettingsCategory("search", R.string.search_title, R.string.search_desc, Icons.Default.Search),
        SettingsCategory("mcp", R.string.mcp_title, R.string.mcp_desc, McpServerIcon),
        SettingsCategory("shell", R.string.shell_title, R.string.shell_desc, Icons.Default.Terminal),
    )),
    SettingsGroupData(titleRes = R.string.settings_group_network, items = listOf(
        SettingsCategory("proxy", R.string.settings_proxy, R.string.settings_proxy_desc, Icons.Default.Lan),
    )),
    SettingsGroupData(titleRes = R.string.settings_group_memory_data, items = listOf(
        SettingsCategory("memory", R.string.settings_memory, R.string.settings_memory_desc, Icons.Default.Description),
        SettingsCategory("datacontrol", R.string.settings_data_control, R.string.settings_data_control_desc, Icons.Default.Storage),
    )),
    SettingsGroupData(titleRes = R.string.settings_group_appearance_language, items = listOf(
        SettingsCategory("personalization", R.string.settings_personalization, R.string.settings_personalization_desc, Icons.Default.Person),
        SettingsCategory("appearance", R.string.settings_appearance, R.string.settings_appearance_desc, Icons.Default.Palette),
        SettingsCategory("language", R.string.language_title, R.string.language_desc, Icons.Default.Translate),
    )),
    SettingsGroupData(titleRes = R.string.settings_group_about, items = listOf(
        SettingsCategory("about", R.string.settings_about, R.string.settings_about_desc, Icons.Default.Info),
    )),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: ChatViewModel,
    selectedCategory: String?,
    onSelectedCategoryChange: (String?) -> Unit,
    onBack: () -> Unit
) {
    val listState = rememberLazyListState()
    val isSyncingModels by viewModel.isSyncingModels.collectAsState()
    val fetchingModelsMessage = stringResource(R.string.snackbar_fetching_models)

    LaunchedEffect(isSyncingModels) {
        if (isSyncingModels) {
            viewModel.emitSnackbar(fetchingModelsMessage)
        }
    }

    PredictiveBackHandler(enabled = selectedCategory != null) { events ->
        events.collect { }
        onSelectedCategoryChange(null)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        GuardedAnimatedContent(
            targetState = selectedCategory,
            forward = selectedCategory != null
        ) { category ->
            when (category) {
                "provider" -> SettingsProviderPage(viewModel, onBack = { onSelectedCategoryChange(null) })
                "prompts" -> SettingsPromptsPage(viewModel, onBack = { onSelectedCategoryChange(null) })
                "models" -> SettingsModelsPage(viewModel, onBack = { onSelectedCategoryChange(null) })
                "generation" -> SettingsGenerationPage(viewModel, onBack = { onSelectedCategoryChange(null) })
                "websearch" -> SettingsWebSearchPage(viewModel, onBack = { onSelectedCategoryChange(null) })
                "imagegen" -> SettingsImageGenPage(viewModel, onBack = { onSelectedCategoryChange(null) })
                "shell" -> SettingsShellPage(viewModel, onBack = { onSelectedCategoryChange(null) })
                "mcp" -> SettingsMcpPage(viewModel, onBack = { onSelectedCategoryChange(null) })
                "proxy" -> SettingsProxyPage(viewModel, onBack = { onSelectedCategoryChange(null) })
                "language" -> SettingsLanguagePage(viewModel, onBack = { onSelectedCategoryChange(null) })
                "titlegen" -> SettingsTitleGenPage(viewModel, onBack = { onSelectedCategoryChange(null) })
                "transcription" -> SettingsTranscriptionPage(viewModel, onBack = { onSelectedCategoryChange(null) })
                "search" -> SettingsSearchPage(viewModel, onBack = { onSelectedCategoryChange(null) })
                "memory" -> SettingsMemoryPage(viewModel, onBack = { onSelectedCategoryChange(null) })
                "datacontrol" -> SettingsDataControlPage(viewModel, onBack = { onSelectedCategoryChange(null) })
                "personalization" -> SettingsPersonalizationPage(viewModel, onBack = { onSelectedCategoryChange(null) })
                "appearance" -> SettingsAppearancePage(viewModel, onBack = { onSelectedCategoryChange(null) })
                "about" -> SettingsAboutPage(viewModel, onBack = { onSelectedCategoryChange(null) })
                else -> {
                    CollapsingSettingsLazyScaffold(
                        title = stringResource(R.string.settings_title),
                        onBack = onBack,
                        listState = listState
                    ) {
                        items(settingsGroups.size) { groupIndex ->
                            val group = settingsGroups[groupIndex]
                            Column(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (group.titleRes != null) {
                                    Text(
                                        text = stringResource(group.titleRes),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                                    )
                                }
                                group.items.forEachIndexed { index, cat ->
                                    if (index > 0) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                    }
                                    val isFirst = index == 0
                                    val isLast = index == group.items.lastIndex
                                    val shape = when {
                                        group.items.size == 1 -> RoundedCornerShape(24.dp)
                                        isFirst -> RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 5.dp, bottomEnd = 5.dp)
                                        isLast -> RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp, bottomStart = 24.dp, bottomEnd = 24.dp)
                                        else -> RoundedCornerShape(5.dp)
                                    }
                                    Surface(
                                        shape = shape,
                                        color = MaterialTheme.colorScheme.surface,
                                        tonalElevation = 1.dp,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(shape)
                                            .clickable { onSelectedCategoryChange(cat.key) }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 16.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                cat.icon,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(24.dp)
                                            )
                                            Spacer(modifier = Modifier.width(16.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = stringResource(cat.titleRes),
                                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
                                                )
                                                Spacer(modifier = Modifier.height(3.dp))
                                                Text(
                                                    text = stringResource(cat.descriptionRes),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Icon(
                                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                            )
                                        }
                                    }
                                }
                            }
                            if (groupIndex < settingsGroups.size - 1) {
                                Spacer(modifier = Modifier.height(20.dp))
                            }
                        }
                    }
                }
            }
        }

    }
}
