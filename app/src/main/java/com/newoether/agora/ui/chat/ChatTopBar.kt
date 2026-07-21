package com.newoether.agora.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.model.ModelId
import com.newoether.agora.model.apiModelName
import com.newoether.agora.ui.theme.ChatType

/**
 * Chat top bar: left title capsule (drawer + conversation/brand title with model
 * subtitle; the title block opens the model picker) and a right actions capsule
 * (new chat only).
 *
 * Title width hugs content when short; [widthIn] caps it against remaining space
 * so long titles ellipsize instead of overlapping the right capsule.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatTopBar(
    isNewChatMode: Boolean,
    conversations: List<ChatConversation>,
    currentConversationId: String?,
    selectedModel: String,
    modelAliases: Map<String, String>,
    enabledModels: Set<String>,
    thinkingEnabled: Boolean,
    thinkingLevel: String,
    reasoningSupported: Boolean,
    fastSupported: Boolean,
    fastEnabled: Boolean,
    builtInSearchEnabled: Boolean,
    externalSearchEnabled: Boolean,
    onOpenDrawer: () -> Unit,
    onNewChat: () -> Unit,
    onModelSelect: (String) -> Unit,
    onModelLongPress: (String) -> Unit = {},
    onThinkingSelect: (String?) -> Unit,
    onFastToggle: (Boolean) -> Unit,
    onSearchModeSelect: (builtIn: Boolean, external: Boolean) -> Unit,
    onAdvancedClick: () -> Unit,
    onModelsConfigClick: () -> Unit,
    onProvidersConfigClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 180.dp)
            .background(
                Brush.verticalGradient(
                    0.0f to MaterialTheme.colorScheme.background.copy(alpha = 0.98f),
                    0.6f to MaterialTheme.colorScheme.background.copy(alpha = 0.80f),
                    1.0f to Color.Transparent
                )
            )
    ) {
        // Compact right capsule plus a small gap from the title capsule.
        val actionsReserve = 56.dp

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
                .height(48.dp)
        ) {
            val titleMaxWidth = (maxWidth - actionsReserve).coerceAtLeast(96.dp)

            Row(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Resolve the active conversation's title; null in new-chat mode OR
                // before the conversation/title has loaded. Both the brand TEXT and the
                // brand font SIZE are gated on this single value, so the title never
                // changes size before the text swaps (no transient "Agora at 17sp").
                val resolvedTitle = if (isNewChatMode) null
                else conversations.find { it.id == currentConversationId }?.title?.takeIf { it.isNotBlank() }
                val showBrandTitle = resolvedTitle == null

                val parsed = ModelId.parse(selectedModel)
                val isModelValid = selectedModel.isNotBlank() && enabledModels.contains(selectedModel)
                val modelSubtitle = when {
                    isModelValid -> modelAliases[selectedModel]
                        ?: "${parsed.apiModelName} (${parsed.providerName})"
                    enabledModels.isNotEmpty() -> stringResource(R.string.select_model)
                    else -> stringResource(R.string.no_model_selected)
                }
                val hasModelSubtitle = modelSubtitle.isNotBlank()

                var modelMenuExpanded by remember { mutableStateOf(false) }
                var lastModelDismissTime by remember { mutableLongStateOf(0L) }

                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp,
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(max = titleMaxWidth)
                ) {
                    Row(
                        modifier = Modifier.fillMaxHeight(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = onOpenDrawer,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Default.Menu,
                                contentDescription = stringResource(R.string.menu),
                                modifier = Modifier.size(23.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(2.dp))

                        ExposedDropdownMenuBox(
                            expanded = modelMenuExpanded,
                            onExpandedChange = { },
                            modifier = Modifier.fillMaxHeight()
                        ) {
                            // Shared text metrics so brand/title + model subtitle share one
                            // optical left edge and sit centered in the capsule without the
                            // legacy Android font-padding offset between the two lines.
                            val titleLineHeightStyle = LineHeightStyle(
                                alignment = LineHeightStyle.Alignment.Center,
                                trim = LineHeightStyle.Trim.Both,
                            )
                            val titlePlatformStyle = PlatformTextStyle(includeFontPadding = false)
                            Column(
                                modifier = Modifier
                                    .menuAnchor(
                                        type = ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                                        enabled = true
                                    )
                                    .fillMaxHeight()
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        val now = System.currentTimeMillis()
                                        if (modelMenuExpanded) {
                                            modelMenuExpanded = false
                                        } else if (now - lastModelDismissTime > 200) {
                                            modelMenuExpanded = true
                                        }
                                    }
                                    // Tiny optical nudge toward the capsule's upper-left.
                                    .offset(x = (-1).dp, y = (-1).dp)
                                    .padding(end = 14.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.Start,
                            ) {
                                val primaryTitleStyle = when {
                                    showBrandTitle && hasModelSubtitle -> ChatType.conversationTitle
                                    showBrandTitle -> ChatType.brandTitle
                                    hasModelSubtitle -> ChatType.conversationTitle
                                    else -> ChatType.conversationTitleSolo
                                }.copy(
                                    platformStyle = titlePlatformStyle,
                                    lineHeightStyle = titleLineHeightStyle,
                                )
                                Text(
                                    text = if (showBrandTitle) {
                                        stringResource(R.string.app_name)
                                    } else {
                                        resolvedTitle.orEmpty()
                                    },
                                    style = primaryTitleStyle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (hasModelSubtitle) {
                                    Text(
                                        text = modelSubtitle,
                                        style = ChatType.micro.copy(
                                            platformStyle = titlePlatformStyle,
                                            lineHeightStyle = titleLineHeightStyle,
                                        ),
                                        color = if (isModelValid) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }

                            ExposedDropdownMenu(
                                                            expanded = modelMenuExpanded,
                                                            onDismissRequest = {
                                                                if (modelMenuExpanded) {
                                                                    modelMenuExpanded = false
                                                                    lastModelDismissTime = System.currentTimeMillis()
                                                                }
                                                            },
                                                            matchTextFieldWidth = false,
                                                            shape = MaterialTheme.shapes.medium,
                                                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                                                        ) {
                                                            GroupedModelMenuContent(
                                                                enabledModels = enabledModels,
                                                                selectedModel = selectedModel,
                                                                modelAliases = modelAliases,
                                                                thinkingEnabled = thinkingEnabled,
                                                                thinkingLevel = thinkingLevel,
                                                                reasoningSupported = reasoningSupported,
                                                                fastSupported = fastSupported,
                                                                fastEnabled = fastEnabled,
                                                                builtInSearchEnabled = builtInSearchEnabled,
                                                                externalSearchEnabled = externalSearchEnabled,
                                                                onModelSelect = onModelSelect,
                                                                onModelLongPress = onModelLongPress,
                                                                onThinkingSelect = onThinkingSelect,
                                                                onFastToggle = onFastToggle,
                                                                onSearchModeSelect = onSearchModeSelect,
                                                                onAdvancedClick = onAdvancedClick,
                                                                onModelsConfigClick = onModelsConfigClick,
                                                                onProvidersConfigClick = onProvidersConfigClick,
                                                                onDismissAll = {
                                                                    modelMenuExpanded = false
                                                                    lastModelDismissTime = 0L
                                                                }
                                                            )
                                                        }
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Surface(
                    onClick = onNewChat,
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp,
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(48.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.chat_add_on_24px),
                            contentDescription = stringResource(R.string.new_chat),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}
