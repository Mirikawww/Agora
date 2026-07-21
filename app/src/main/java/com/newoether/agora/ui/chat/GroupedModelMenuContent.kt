package com.newoether.agora.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.newoether.agora.R
import com.newoether.agora.model.ModelId
import com.newoether.agora.model.ThinkingLevels
import com.newoether.agora.model.apiModelName
import com.newoether.agora.util.Constants

private const val SUBMENU_ENTER_DURATION_MS = 70
private const val SUBMENU_EXIT_DURATION_MS = 45
private const val THINKING_MENU_KEY = "__thinking__"
private const val WEB_SEARCH_MENU_KEY = "__web_search__"

private class CascadingMenuPositionProvider(
    private val overlapPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val right = anchorBounds.right - overlapPx
        val left = anchorBounds.left - popupContentSize.width + overlapPx
        val preferredX = if (layoutDirection == LayoutDirection.Ltr) right else left
        val fallbackX = if (layoutDirection == LayoutDirection.Ltr) left else right
        val x = when {
            preferredX >= 0 && preferredX + popupContentSize.width <= windowSize.width -> preferredX
            fallbackX >= 0 && fallbackX + popupContentSize.width <= windowSize.width -> fallbackX
            else -> preferredX.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        }
        val y = anchorBounds.top.coerceIn(
            0,
            (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        )
        return IntOffset(x, y)
    }
}

/**
 * Grouped model-picker rows for an already-open [DropdownMenu] / [androidx.compose.material3.ExposedDropdownMenu].
 *
 * First level = provider groups (chevron on the right). Tapping a group opens a
 * nested menu to the right listing that provider's models.
 */
@Composable
fun GroupedModelMenuContent(
    enabledModels: Set<String>,
    selectedModel: String,
    modelAliases: Map<String, String>,
    thinkingEnabled: Boolean,
    thinkingLevel: String,
    reasoningSupported: Boolean,
    fastSupported: Boolean,
    fastEnabled: Boolean,
    builtInSearchEnabled: Boolean,
    externalSearchEnabled: Boolean,
    onModelSelect: (String) -> Unit,
    onThinkingSelect: (String?) -> Unit,
    onFastToggle: (Boolean) -> Unit,
    onSearchModeSelect: (builtIn: Boolean, external: Boolean) -> Unit,
    onAdvancedClick: () -> Unit,
    onModelsConfigClick: () -> Unit,
    onProvidersConfigClick: () -> Unit,
    onDismissAll: () -> Unit,
) {
    if (enabledModels.isEmpty()) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.models_no_models)) },
            onClick = onDismissAll,
            enabled = false
        )
    }

    val groups = remember(enabledModels, modelAliases) {
        enabledModels
            .groupBy { ModelId.parse(it).providerName }
            .entries
            .sortedWith(
                compareBy(
                    { if (it.key.equals(Constants.PROVIDER_LOCAL, ignoreCase = true)) 0 else 1 },
                    { it.key.lowercase() }
                )
            )
            .map { (provider, models) ->
                provider to models.sortedBy { id ->
                    modelAliases[id] ?: ModelId.parse(id).apiModelName
                }
            }
    }

    var expandedProvider by remember { mutableStateOf<String?>(null) }
    val density = LocalDensity.current
    val submenuPositionProvider = remember(density) {
        CascadingMenuPositionProvider(overlapPx = with(density) { 8.dp.roundToPx() })
    }

    LaunchedEffect(reasoningSupported) {
        if (!reasoningSupported && expandedProvider == THINKING_MENU_KEY) expandedProvider = null
    }

    groups.forEach { (provider, models) ->
        val expanded = expandedProvider == provider

        Box {
            DropdownMenuItem(
                text = { Text(provider) },
                trailingIcon = {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier
                            .size(18.dp)
                            .offset(x = 6.dp),
                        // Solid monochrome chevron that follows light/dark theme.
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                },
                onClick = {
                    expandedProvider = if (expanded) null else provider
                }
            )

            val visibility = remember(provider) { MutableTransitionState(false) }
            visibility.targetState = expanded
            if (visibility.currentState || visibility.targetState) {
                Popup(
                    popupPositionProvider = submenuPositionProvider,
                    onDismissRequest = {
                        if (expandedProvider == provider) expandedProvider = null
                    },
                    // Keep the child popup non-focusable so another first-level row
                    // receives the same tap and can replace this submenu immediately.
                    properties = PopupProperties(
                        focusable = false,
                        dismissOnClickOutside = false
                    )
                ) {
                    AnimatedVisibility(
                        visibleState = visibility,
                        enter = fadeIn(tween(SUBMENU_ENTER_DURATION_MS)) +
                            scaleIn(tween(SUBMENU_ENTER_DURATION_MS), initialScale = 0.92f),
                        exit = if (expandedProvider == null) {
                            fadeOut(tween(SUBMENU_EXIT_DURATION_MS)) +
                                scaleOut(tween(SUBMENU_EXIT_DURATION_MS), targetScale = 0.96f)
                        } else {
                            ExitTransition.None
                        }
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = MaterialTheme.shapes.medium,
                            tonalElevation = 16.dp,
                            shadowElevation = 8.dp
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(vertical = 8.dp)
                                    .width(IntrinsicSize.Max)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                models.forEach { model ->
                                    val item = ModelId.parse(model)
                                    val label = modelAliases[model] ?: item.apiModelName
                                    val isSelected = model == selectedModel
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                label,
                                                color = if (isSelected) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
                                                }
                                            )
                                        },
                                        onClick = {
                                            onModelSelect(model)
                                            expandedProvider = null
                                            onDismissAll()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

    val thinkingExpanded = expandedProvider == THINKING_MENU_KEY
    Box {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.thinking)) },
            trailingIcon = {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp).offset(x = 6.dp),
                    tint = if (reasoningSupported) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    }
                )
            },
            enabled = reasoningSupported,
            onClick = {
                expandedProvider = if (thinkingExpanded) null else THINKING_MENU_KEY
            }
        )

        val visibility = remember { MutableTransitionState(false) }
        visibility.targetState = thinkingExpanded
        if (visibility.currentState || visibility.targetState) {
            Popup(
                popupPositionProvider = submenuPositionProvider,
                onDismissRequest = {
                    if (expandedProvider == THINKING_MENU_KEY) expandedProvider = null
                },
                properties = PopupProperties(
                    focusable = false,
                    dismissOnClickOutside = false
                )
            ) {
                AnimatedVisibility(
                    visibleState = visibility,
                    enter = fadeIn(tween(SUBMENU_ENTER_DURATION_MS)) +
                        scaleIn(tween(SUBMENU_ENTER_DURATION_MS), initialScale = 0.92f),
                    exit = if (expandedProvider == null) {
                        fadeOut(tween(SUBMENU_EXIT_DURATION_MS)) +
                            scaleOut(tween(SUBMENU_EXIT_DURATION_MS), targetScale = 0.96f)
                    } else {
                        ExitTransition.None
                    }
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.medium,
                        tonalElevation = 16.dp,
                        shadowElevation = 8.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(vertical = 8.dp)
                                .width(IntrinsicSize.Max)
                                .verticalScroll(rememberScrollState())
                        ) {
                            val normalizedLevel = ThinkingLevels.normalize(thinkingLevel)
                            val efforts: List<String?> = listOf(null) + ThinkingLevels.effortValues
                            efforts.forEach { effort ->
                                val isSelected = if (effort == null) {
                                    !thinkingEnabled
                                } else {
                                    thinkingEnabled && normalizedLevel == effort
                                }
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = effort ?: stringResource(R.string.thinking_control_off),
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            }
                                        )
                                    },
                                    onClick = {
                                        onThinkingSelect(effort)
                                        expandedProvider = null
                                        onDismissAll()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    DropdownMenuItem(
        text = { Text(stringResource(R.string.thinking_control_quick)) },
        trailingIcon = {
            Switch(
                checked = fastSupported && fastEnabled,
                onCheckedChange = null,
                enabled = fastSupported
            )
        },
        enabled = fastSupported,
        onClick = {
            expandedProvider = null
            onFastToggle(!fastEnabled)
        }
    )

    val webSearchExpanded = expandedProvider == WEB_SEARCH_MENU_KEY
    Box {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.model_menu_web_search)) },
            trailingIcon = {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp).offset(x = 6.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            },
            onClick = {
                expandedProvider = if (webSearchExpanded) null else WEB_SEARCH_MENU_KEY
            }
        )

        val visibility = remember { MutableTransitionState(false) }
        visibility.targetState = webSearchExpanded
        if (visibility.currentState || visibility.targetState) {
            Popup(
                popupPositionProvider = submenuPositionProvider,
                onDismissRequest = {
                    if (expandedProvider == WEB_SEARCH_MENU_KEY) expandedProvider = null
                },
                properties = PopupProperties(
                    focusable = false,
                    dismissOnClickOutside = false
                )
            ) {
                AnimatedVisibility(
                    visibleState = visibility,
                    enter = fadeIn(tween(SUBMENU_ENTER_DURATION_MS)) +
                        scaleIn(tween(SUBMENU_ENTER_DURATION_MS), initialScale = 0.92f),
                    exit = if (expandedProvider == null) {
                        fadeOut(tween(SUBMENU_EXIT_DURATION_MS)) +
                            scaleOut(tween(SUBMENU_EXIT_DURATION_MS), targetScale = 0.96f)
                    } else {
                        ExitTransition.None
                    }
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.medium,
                        tonalElevation = 16.dp,
                        shadowElevation = 8.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(vertical = 8.dp)
                                .width(IntrinsicSize.Max)
                                .verticalScroll(rememberScrollState())
                        ) {
                            val modes = listOf(
                                Triple(R.string.model_menu_search_off, false, false),
                                Triple(R.string.model_menu_search_builtin, true, false),
                                Triple(R.string.model_menu_search_external, false, true),
                                Triple(R.string.model_menu_search_hybrid, true, true),
                            )
                            modes.forEach { (labelRes, builtIn, external) ->
                                val isSelected = builtInSearchEnabled == builtIn &&
                                    externalSearchEnabled == external
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = stringResource(labelRes),
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            }
                                        )
                                    },
                                    onClick = {
                                        onSearchModeSelect(builtIn, external)
                                        expandedProvider = null
                                        onDismissAll()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    DropdownMenuItem(
        text = { Text(stringResource(R.string.advanced_settings)) },
        onClick = {
            expandedProvider = null
            onDismissAll()
            onAdvancedClick()
        }
    )

    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

    DropdownMenuItem(
        text = { Text(stringResource(R.string.model_menu_models_config)) },
        onClick = {
            expandedProvider = null
            onDismissAll()
            onModelsConfigClick()
        }
    )

    DropdownMenuItem(
        text = { Text(stringResource(R.string.model_menu_providers_config)) },
        onClick = {
            expandedProvider = null
            onDismissAll()
            onProvidersConfigClick()
        }
    )
}
