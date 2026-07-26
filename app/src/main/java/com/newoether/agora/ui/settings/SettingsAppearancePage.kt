package com.newoether.agora.ui.settings

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.model.ToolCallDisplayModes
import com.newoether.agora.ui.theme.ColorSchemePreset
import com.newoether.agora.ui.theme.SchemeStyle
import com.newoether.agora.ui.theme.colorSchemeForPreset
import com.newoether.agora.viewmodel.ChatViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAppearancePage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val themeMode by viewModel.settings.themeMode.collectAsState()
    val colorSchemeName by viewModel.settings.colorScheme.collectAsState()
    val schemeStyleName by viewModel.settings.schemeStyle.collectAsState()
    val dynamicColor by viewModel.settings.dynamicColor.collectAsState()
    val blurEffectsEnabled by viewModel.settings.blurEffectsEnabled.collectAsState()
    val hapticsEnabled by viewModel.settings.hapticsEnabled.collectAsState()
    val toolCallDisplayMode by viewModel.settings.toolCallDisplayMode.collectAsState()
    val showComposerExpand by viewModel.settings.showComposerExpandButton.collectAsState()
    val welcomeMessages by viewModel.settings.welcomeMessages.collectAsState()
    val welcomeDisplayMode by viewModel.settings.welcomeDisplayMode.collectAsState()
    val welcomeEnabled by viewModel.settings.welcomeEnabled.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()


    val isDynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val currentPreset = try { ColorSchemePreset.valueOf(colorSchemeName) } catch (_: Exception) { ColorSchemePreset.MIDNIGHT }
    val currentStyle = try { SchemeStyle.valueOf(schemeStyleName) } catch (_: Exception) { SchemeStyle.TONAL_SPOT }
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        "LIGHT" -> false
        "DARK" -> true
        else -> systemDark
    }
    CollapsingSettingsScaffold(
        title = stringResource(R.string.appearance_title),
        onBack = onBack,
    ) {
            SettingsGroupColumn {

            SettingsGroup(title = stringResource(R.string.composer_show_expand), items = listOf({
                SettingsItem(
                    headlineContent = { Text(stringResource(R.string.composer_show_expand)) },
                    supportingContent = { Text(stringResource(R.string.composer_show_expand_desc)) },
                    trailingContent = {
                        Switch(
                            checked = showComposerExpand,
                            onCheckedChange = { viewModel.settings.setShowComposerExpandButton(it) }
                        )
                    },
                    modifier = Modifier.clickable {
                        viewModel.settings.setShowComposerExpandButton(!showComposerExpand)
                    }
                )
            }))

                // ── Theme Mode ──
                SettingsGroup(
                    title = stringResource(R.string.theme_mode),
                    items = listOf {
                        var expanded by remember { mutableStateOf(false) }
                        val selectedLabel = when (themeMode) {
                            "LIGHT" -> stringResource(R.string.theme_mode_light)
                            "DARK" -> stringResource(R.string.theme_mode_dark)
                            else -> stringResource(R.string.theme_mode_follow_device)
                        }
                        val selectedIcon = when (themeMode) {
                            "LIGHT" -> Icons.Default.LightMode
                            "DARK" -> Icons.Default.DarkMode
                            else -> Icons.Default.SettingsBrightness
                        }
                        val options = listOf(
                            "LIGHT" to Pair(stringResource(R.string.theme_mode_light), Icons.Default.LightMode),
                            "DARK" to Pair(stringResource(R.string.theme_mode_dark), Icons.Default.DarkMode),
                            "FOLLOW_DEVICE" to Pair(stringResource(R.string.theme_mode_follow_device), Icons.Default.SettingsBrightness)
                        )
                        SettingsItem(
                            headlineContent = { Text(stringResource(R.string.theme_mode)) },
                            supportingContent = { Text(selectedLabel) },
                            leadingContent = {
                                Icon(selectedIcon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            },
                            trailingContent = {
                                Box {
                                    Text(
                                        selectedLabel,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.width(96.dp).padding(end = 4.dp),
                                        textAlign = TextAlign.End,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    DropdownMenu(
                                        expanded = expanded,
                                        onDismissRequest = { expanded = false },
                                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                        tonalElevation = 16.dp,
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        options.forEach { (mode, pair) ->
                                            val (label, icon) = pair
                                            val isSelected = when (mode) {
                                                "LIGHT" -> themeMode == "LIGHT"
                                                "DARK" -> themeMode == "DARK"
                                                else -> themeMode != "LIGHT" && themeMode != "DARK"
                                            }
                                            DropdownMenuItem(
                                                text = { Text(label) },
                                                leadingIcon = {
                                                    if (isSelected) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                                },
                                                trailingIcon = { Icon(icon, null) },
                                                onClick = { viewModel.settings.setThemeMode(mode); expanded = false }
                                            )
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.clickable { expanded = true }
                        )
                    }
                )

                // ── Interface ──
                SettingsGroup(
                    title = stringResource(R.string.appearance_interface),
                    items = buildList {
                        if (isDynamicAvailable) {
                            add {
                                SettingsItem(
                                    headlineContent = { Text(stringResource(R.string.dynamic_color)) },
                                    supportingContent = { Text(stringResource(R.string.dynamic_color_desc)) },
                                    trailingContent = {
                                        Switch(
                                            checked = dynamicColor,
                                            onCheckedChange = { viewModel.settings.setDynamicColor(it) }
                                        )
                                    },
                                    modifier = Modifier.clickable { viewModel.settings.setDynamicColor(!dynamicColor) }
                                )
                            }
                        }
                        add {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.blur_effects)) },
                                supportingContent = { Text(stringResource(R.string.blur_effects_desc)) },
                                trailingContent = {
                                    Switch(
                                        checked = blurEffectsEnabled,
                                        onCheckedChange = { viewModel.settings.setBlurEffectsEnabled(it) }
                                    )
                                },
                                modifier = Modifier.clickable { viewModel.settings.setBlurEffectsEnabled(!blurEffectsEnabled) }
                            )
                        }
                        add {
                            var expanded by remember { mutableStateOf(false) }
                            val normalizedToolCallDisplayMode = ToolCallDisplayModes.normalize(toolCallDisplayMode)
                            val selectedLabel = when (normalizedToolCallDisplayMode) {
                                ToolCallDisplayModes.GROUPED_TIMELINE -> stringResource(R.string.tool_call_display_mode_grouped_timeline)
                                ToolCallDisplayModes.COMPACT -> stringResource(R.string.tool_call_display_mode_compact)
                                else -> stringResource(R.string.tool_call_display_mode_timeline)
                            }
                            val selectedDescription = when (normalizedToolCallDisplayMode) {
                                ToolCallDisplayModes.GROUPED_TIMELINE -> stringResource(R.string.tool_call_display_mode_grouped_timeline_desc)
                                ToolCallDisplayModes.COMPACT -> stringResource(R.string.tool_call_display_mode_compact_desc)
                                else -> stringResource(R.string.tool_call_display_mode_timeline_desc)
                            }
                            val options = listOf(
                                ToolCallDisplayModes.TIMELINE to stringResource(R.string.tool_call_display_mode_timeline),
                                ToolCallDisplayModes.GROUPED_TIMELINE to stringResource(R.string.tool_call_display_mode_grouped_timeline),
                                ToolCallDisplayModes.COMPACT to stringResource(R.string.tool_call_display_mode_compact)
                            )
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.tool_call_display_mode)) },
                                supportingContent = { Text(selectedDescription) },
                                trailingContent = {
                                    Box {
                                        Text(
                                            selectedLabel,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.width(72.dp).padding(end = 4.dp),
                                            textAlign = TextAlign.End,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        DropdownMenu(
                                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                            tonalElevation = 16.dp,
                                            expanded = expanded,
                                            onDismissRequest = { expanded = false },
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            options.forEach { (mode, label) ->
                                                DropdownMenuItem(
                                                    text = { Text(label) },
                                                    leadingIcon = {
                                                        if (normalizedToolCallDisplayMode == mode) {
                                                            Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                                        }
                                                    },
                                                    onClick = {
                                                        viewModel.settings.setToolCallDisplayMode(mode)
                                                        expanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.clickable { expanded = true }
                            )
                        }
                        add {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.haptic_feedback)) },
                                supportingContent = { Text(stringResource(R.string.haptic_feedback_desc)) },
                                trailingContent = {
                                    Switch(
                                        checked = hapticsEnabled,
                                        onCheckedChange = { viewModel.settings.setHapticsEnabled(it) }
                                    )
                                },
                                modifier = Modifier.clickable { viewModel.settings.setHapticsEnabled(!hapticsEnabled) }
                            )
                        }
                    }
                )

                val schemeAlpha = if (dynamicColor && isDynamicAvailable) 0.38f else 1f
                SettingsGroup(
                    title = stringResource(R.string.color_scheme),
                    items = listOf(
                        {
                            var expanded by remember { mutableStateOf(false) }
                            val currentLabel = presetDisplayName(currentPreset)
                            val currentPrimary = remember(currentPreset, currentStyle, isDark) {
                                colorSchemeForPreset(currentPreset, currentStyle, isDark).primary
                            }
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.color_scheme)) },
                                supportingContent = { Text(currentLabel) },
                                leadingContent = {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(currentPrimary)
                                    )
                                },
                                trailingContent = {
                                    Box {
                                        Text(
                                            currentLabel,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.width(96.dp).padding(end = 4.dp),
                                            textAlign = TextAlign.End,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        DropdownMenu(
                                            expanded = expanded,
                                            onDismissRequest = { expanded = false },
                                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                            tonalElevation = 16.dp,
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            ColorSchemePreset.entries.forEach { preset ->
                                                val presetPrimary = remember(preset, currentStyle, isDark) {
                                                    colorSchemeForPreset(preset, currentStyle, isDark).primary
                                                }
                                                DropdownMenuItem(
                                                    text = { Text(presetDisplayName(preset)) },
                                                    leadingIcon = {
                                                        if (preset == currentPreset) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                                    },
                                                    trailingIcon = {
                                                        Box(modifier = Modifier.size(20.dp).clip(CircleShape).background(presetPrimary))
                                                    },
                                                    onClick = {
                                                                                                            viewModel.settings.setColorScheme(preset.name)
                                                                                                            expanded = false
                                                                                                        }
                                                )
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.alpha(schemeAlpha).clickable(enabled = schemeAlpha > 0.5f) { expanded = true }
                            )
                        },
                        {
                            var expanded by remember { mutableStateOf(false) }
                            val currentLabel = styleDisplayName(currentStyle)
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.scheme_style)) },
                                supportingContent = { Text(currentLabel) },
                                trailingContent = {
                                    Box {
                                        Text(
                                            currentLabel,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.width(96.dp).padding(end = 4.dp),
                                            textAlign = TextAlign.End,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        DropdownMenu(
                                            expanded = expanded,
                                            onDismissRequest = { expanded = false },
                                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                            tonalElevation = 16.dp,
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            SchemeStyle.entries.forEach { style ->
                                                DropdownMenuItem(
                                                    text = { Text(styleDisplayName(style)) },
                                                    leadingIcon = {
                                                        if (style == currentStyle) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                                    },
                                                    onClick = { viewModel.settings.setSchemeStyle(style.name); expanded = false }
                                                )
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.alpha(schemeAlpha).clickable(enabled = schemeAlpha > 0.5f) { expanded = true }
                            )
                        }
                    )
                )

            }

                // ── Home welcome messages ──
                SettingsGroup(
                    title = stringResource(R.string.welcome_messages_title),
                    items = buildList {
                        add {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.welcome_enabled)) },
                                supportingContent = { Text(stringResource(R.string.welcome_enabled_desc)) },
                                trailingContent = {
                                    Switch(
                                        checked = welcomeEnabled,
                                        onCheckedChange = { viewModel.settings.setWelcomeEnabled(it) }
                                    )
                                },
                                modifier = Modifier.clickable {
                                    viewModel.settings.setWelcomeEnabled(!welcomeEnabled)
                                }
                            )
                        }
                        add {
                            var draft by remember(welcomeMessages) { mutableStateOf(welcomeMessages) }
                            // Debounced auto-save; no extra Save button.
                            LaunchedEffect(draft) {
                                if (draft == welcomeMessages) return@LaunchedEffect
                                kotlinx.coroutines.delay(400)
                                if (draft != welcomeMessages) viewModel.settings.setWelcomeMessages(draft)
                            }
                            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                                OutlinedTextField(
                                    value = draft,
                                    onValueChange = { draft = it },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                                    minLines = 4,
                                    maxLines = 10,
                                    placeholder = { Text(stringResource(R.string.welcome_messages_placeholder)) },
                                    enabled = welcomeEnabled,
                                )
                            }
                        }
                        add {
                            val isSequential = welcomeDisplayMode == "sequential"
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.welcome_display_mode)) },
                                supportingContent = {
                                    Text(
                                        if (isSequential) stringResource(R.string.welcome_display_sequential)
                                        else stringResource(R.string.welcome_display_random)
                                    )
                                },
                                trailingContent = {
                                    Switch(
                                        checked = isSequential,
                                        onCheckedChange = { checked ->
                                            viewModel.settings.setWelcomeDisplayMode(
                                                if (checked) "sequential" else "random"
                                            )
                                        }
                                    )
                                },
                                modifier = Modifier.clickable {
                                    viewModel.settings.setWelcomeDisplayMode(
                                        if (isSequential) "random" else "sequential"
                                    )
                                }
                            )
                        }
                    }
                )

    }
}

@Composable
private fun presetDisplayName(preset: ColorSchemePreset): String = when (preset) {
    ColorSchemePreset.MIDNIGHT -> stringResource(R.string.color_scheme_midnight)
    ColorSchemePreset.NORDIC -> stringResource(R.string.color_scheme_nordic)
    ColorSchemePreset.FOREST -> stringResource(R.string.color_scheme_forest)
    ColorSchemePreset.SUNSET -> stringResource(R.string.color_scheme_sunset)
    ColorSchemePreset.ROSE -> stringResource(R.string.color_scheme_rose)
    ColorSchemePreset.LAVENDER -> stringResource(R.string.color_scheme_lavender)
    ColorSchemePreset.SLATE -> stringResource(R.string.color_scheme_slate)
    ColorSchemePreset.OCEAN -> stringResource(R.string.color_scheme_ocean)
    ColorSchemePreset.MONOCHROME -> stringResource(R.string.color_scheme_monochrome)
}

@Composable
private fun styleDisplayName(style: SchemeStyle): String = when (style) {
    SchemeStyle.TONAL_SPOT -> stringResource(R.string.scheme_style_tonal_spot)
    SchemeStyle.EXPRESSIVE -> stringResource(R.string.scheme_style_expressive)
    SchemeStyle.VIBRANT -> stringResource(R.string.scheme_style_vibrant)
    SchemeStyle.NEUTRAL -> stringResource(R.string.scheme_style_neutral)
}
