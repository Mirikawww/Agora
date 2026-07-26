package com.newoether.agora.ui.chat.bottombar

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.Icon
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.*
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.material.icons.Icons

import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.ui.chat.PdfPageSelectDialog
import com.newoether.agora.ui.chat.VideoSliceDialog
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.ui.theme.ChatType
import com.newoether.agora.ui.theme.LocalIsMonochrome
import com.newoether.agora.util.noOpBringIntoView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachmentMenuButton(
    size: Dp,
    iconSize: Dp,
    onPhotos: () -> Unit,
    onFiles: () -> Unit,
    forceWebSearch: Boolean = false,
    onToggleForceWebSearch: () -> Unit = {},
    forceImageGen: Boolean = false,
    onToggleForceImageGen: () -> Unit = {},
    forceGithub: Boolean = false,
    onToggleForceGithub: () -> Unit = {},
    showGithubConnector: Boolean = false,
    forceTodoist: Boolean = false,
    onToggleForceTodoist: () -> Unit = {},
    showTodoistConnector: Boolean = false,
    forceNotion: Boolean = false,
    onToggleForceNotion: () -> Unit = {},
    showNotionConnector: Boolean = false,
) {
    val haptics = LocalAgoraHaptics.current
    var showAddMenu by remember { mutableStateOf(false) }
    var lastAddDismissTime by remember { mutableLongStateOf(0L) }
    val isMonochrome = com.newoether.agora.ui.theme.LocalIsMonochrome.current
    // Mono: solid container chip (surface alone blends into composer). Non-mono: original surface + tone.
    val plusContainer = if (isMonochrome) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.colorScheme.surface
    }
    val plusTonal = if (isMonochrome) 0.dp else 4.dp

    ExposedDropdownMenuBox(
        expanded = showAddMenu,
        onExpandedChange = { }
    ) {
        Surface(
            onClick = {
                haptics.action()
                val now = System.currentTimeMillis()
                if (showAddMenu) {
                    showAddMenu = false
                } else if (now - lastAddDismissTime > 200) {
                    showAddMenu = true
                }
            },
            shape = CircleShape,
            color = plusContainer,
            tonalElevation = plusTonal,
            shadowElevation = 0.dp,
            modifier = Modifier
                .size(size)
                .menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Add,
                    stringResource(R.string.add_attachment),
                    modifier = Modifier.size(iconSize),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        ExposedDropdownMenu(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            expanded = showAddMenu,
            onDismissRequest = {
                if (showAddMenu) {
                    showAddMenu = false
                    lastAddDismissTime = System.currentTimeMillis()
                }
            },
            matchTextFieldWidth = false,
            shape = RoundedCornerShape(16.dp)
        ) {
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Image, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.photos))
                    }
                },
                onClick = {
                    haptics.selection()
                    showAddMenu = false
                    lastAddDismissTime = 0L
                    onPhotos()
                }
            )
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AttachFile, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.files))
                    }
                },
                onClick = {
                    haptics.selection()
                    showAddMenu = false
                    lastAddDismissTime = 0L
                    onFiles()
                }
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.force_web_search))
                                                if (forceWebSearch) {
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Icon(
                                                        imageVector = Icons.Filled.Circle,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(6.dp),
                                                        tint = MaterialTheme.colorScheme.primary,
                                                    )
                                                }
                    }
                },
                onClick = {
                    haptics.selection()
                    showAddMenu = false
                    lastAddDismissTime = 0L
                    onToggleForceWebSearch()
                }
            )
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Image, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.force_image_gen))
                                                if (forceImageGen) {
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Icon(
                                                        imageVector = Icons.Filled.Circle,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(6.dp),
                                                        tint = MaterialTheme.colorScheme.primary,
                                                    )
                                                }
                    }
                },
                onClick = {
                    haptics.selection()
                    showAddMenu = false
                    lastAddDismissTime = 0L
                    onToggleForceImageGen()
                }
            )
            if (showGithubConnector || showTodoistConnector || showNotionConnector) {
                // One divider before the connector group; no separators between connectors.
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }
            if (showGithubConnector) {
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(R.drawable.ic_github_mark),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(stringResource(R.string.force_github))
                            if (forceGithub) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.Filled.Circle,
                                    contentDescription = null,
                                    modifier = Modifier.size(6.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    },
                    onClick = {
                        haptics.selection()
                        showAddMenu = false
                        lastAddDismissTime = 0L
                        onToggleForceGithub()
                    }
                )
            }
            if (showTodoistConnector) {
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(R.drawable.ic_todoist_mark),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(stringResource(R.string.force_todoist))
                            if (forceTodoist) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.Filled.Circle,
                                    contentDescription = null,
                                    modifier = Modifier.size(6.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    },
                    onClick = {
                        haptics.selection()
                        showAddMenu = false
                        lastAddDismissTime = 0L
                        onToggleForceTodoist()
                    }
                )
            }
            if (showNotionConnector) {
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(R.drawable.ic_notion_mark),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(stringResource(R.string.force_notion))
                            if (forceNotion) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.Filled.Circle,
                                    contentDescription = null,
                                    modifier = Modifier.size(6.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    },
                    onClick = {
                        haptics.selection()
                        showAddMenu = false
                        lastAddDismissTime = 0L
                        onToggleForceNotion()
                    }
                )
            }
        }
    }
}
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class
)
@Composable
fun ChatBottomBar(
    onSendMessage: (String, List<com.newoether.agora.model.SelectedAttachment>) -> Boolean,
    onStopGeneration: () -> Unit = {},
    isLoading: Boolean,
    isSwitching: Boolean = false,
    enabledModels: Set<String>,
    selectedModel: String,
    onImageClick: (String) -> Unit = {},
    onAllMediaClick: ((urls: List<String>, index: Int) -> Unit)? = null,
    onFileContentClick: ((fileName: String, content: String) -> Unit)? = null,
    onPdfPagesClick: ((pages: List<String>, startIndex: Int) -> Unit)? = null,
    onPdfPreviewSelect: ((pages: List<String>, startIndex: Int) -> Unit)? = null,
    onPdfViewerClosed: (() -> Unit)? = null,
    pdfViewerSelection: Set<Int> = emptySet(),
    onTogglePdfSelection: ((Int) -> Unit)? = null,
    onInitPdfSelection: ((Set<Int>) -> Unit)? = null,
    fullScreenViewerUrls: List<String>? = null,
    modifier: Modifier = Modifier,
    textFieldState: TextFieldState = rememberSaveable(saver = TextFieldState.Saver) { TextFieldState() },
    focusRequester: FocusRequester = FocusRequester(),
    isExpanded: Boolean = false,
    isExpandAnimating: Boolean = false,
    onCollapse: () -> Unit = {},
    onExpand: () -> Unit = {},
    showExpandButton: Boolean = true,
    forceWebSearch: Boolean = false,
    onToggleForceWebSearch: () -> Unit = {},
    forceImageGen: Boolean = false,
    onToggleForceImageGen: () -> Unit = {},
    forceGithub: Boolean = false,
    onToggleForceGithub: () -> Unit = {},
    showGithubConnector: Boolean = false,
    forceTodoist: Boolean = false,
    onToggleForceTodoist: () -> Unit = {},
    showTodoistConnector: Boolean = false,
    forceNotion: Boolean = false,
    onToggleForceNotion: () -> Unit = {},
    showNotionConnector: Boolean = false,
    // Queue panel is rendered by ChatApp ABOVE the composer surface (not inside).
    queuedMessages: List<com.newoether.agora.viewmodel.QueuedMessage> = emptyList(),
    editingQueueItemId: String? = null,
    onEditQueuedMessage: (com.newoether.agora.viewmodel.QueuedMessage) -> Unit = {},
    onCancelQueuedMessage: (com.newoether.agora.viewmodel.QueuedMessage) -> Unit = {},
    onClearQueueEdit: () -> Unit = {},
    isEditingSentMessage: Boolean = false,
    onClearSentMessageEdit: () -> Unit = {},
) {
    val scrollState = rememberScrollState()
    BackHandler(enabled = isExpanded) { onCollapse() }
    val isModelValid = selectedModel.isNotBlank() && enabledModels.contains(selectedModel)
    val fullscreenInputLabel = stringResource(R.string.composer_fullscreen_input)

    // No-op bring-into-view to prevent auto-scrolling on text field focus

    val composer = rememberChatComposerState()

    val context = LocalContext.current

    // Restore PDF dialog after viewer closes
    LaunchedEffect(fullScreenViewerUrls) {
        if (fullScreenViewerUrls == null && composer.pdfDialogHiddenForPreview && composer.pendingPdfUri != null) {
            composer.showPdfPageDialog = true
            composer.pdfDialogHiddenForPreview = false
        }
    }

    val mediaLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val images = mutableListOf<android.net.Uri>()
        val videos = mutableListOf<android.net.Uri>()
        uris.forEach { uri ->
            val mime = try { context.contentResolver.getType(uri) } catch (_: Exception) { null }
            if (mime?.startsWith("video/") == true) videos += uri else images += uri
        }
        if (images.isNotEmpty()) composer.onPickImages(images)
        if (videos.isNotEmpty()) composer.onPickVideos(videos)
    }
    val fileLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()
    ) { uris -> composer.onPickFiles(uris, onInitPdfSelection) }

    Box(modifier = modifier.fillMaxWidth().then(if (isExpanded) Modifier.fillMaxHeight() else Modifier).padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 12.dp)) {
        Column(modifier = Modifier.fillMaxWidth().then(if (isExpanded) Modifier.fillMaxHeight() else Modifier)) {
            AnimatedVisibility(
                visible = isExpanded,
                enter = EnterTransition.None,
                exit = shrinkVertically(tween(250)) + fadeOut(tween(250))
            ) {
                Spacer(modifier = Modifier.height(44.dp))
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (isExpanded) Modifier.weight(1f) else Modifier)
                    .animateContentSize(tween(400))
            ) {
                if (composer.selectedAttachments.isNotEmpty() && !isExpanded) {
                    AttachmentPreviewRow(
                        composer = composer,
                        onAllMediaClick = onAllMediaClick,
                        onFileContentClick = onFileContentClick,
                        onPdfPagesClick = onPdfPagesClick,
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (isExpanded) Modifier.weight(1f) else Modifier)
                        .noOpBringIntoView()
                ) {
                    TextField(
                        state = textFieldState,
                        scrollState = scrollState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (isExpanded) Modifier.fillMaxHeight() else Modifier)
                            .focusRequester(focusRequester)
                            // When the expand button is hidden, inject "Fullscreen input"
                            // into the text selection / long-press action menu.
                            .then(
                                if (!showExpandButton && !isExpanded) {
                                    Modifier.appendTextContextMenuComponents {
                                        separator()
                                        item(
                                            key = "agora_fullscreen_input",
                                            label = fullscreenInputLabel,
                                        ) {
                                            // receiver is TextContextMenuSession
                                            close()
                                            if (!isExpandAnimating) onExpand()
                                        }
                                    }
                                } else Modifier
                            )
                            .verticalScrollbar(scrollState, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                        placeholder = {
                            Text(
                                stringResource(R.string.ask_agora),
                                style = ChatType.input,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        },
                        lineLimits = TextFieldLineLimits.MultiLine(1, if (isExpanded) Int.MAX_VALUE else 6),
                        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            cursorColor = MaterialTheme.colorScheme.primary
                        ),
                        textStyle = ChatType.input.copy(color = MaterialTheme.colorScheme.onSurface)
                    )
                    androidx.compose.animation.AnimatedVisibility(
                        visible = !isExpanded && showExpandButton,
                        enter = fadeIn(tween(250)),
                        exit = ExitTransition.None,
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        val elevatedSurface = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
                        IconButton(
                            onClick = { if (!isExpandAnimating) onExpand() },
                            modifier = Modifier
                                .padding(end = 4.dp, top = 4.dp)
                                .size(40.dp)
                                .background(
                                    Brush.radialGradient(
                                        listOf(
                                            elevatedSurface,
                                            elevatedSurface.copy(alpha = 0.5f),
                                            Color.Transparent
                                        )
                                    ),
                                    CircleShape
                                )
                        ) {
                            Icon(
                                painter = androidx.compose.ui.res.painterResource(id = R.drawable.expand_all_24px),
                                contentDescription = stringResource(R.string.expand),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                            )
                        }
                    }
                }

                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 6.dp, start = 8.dp, end = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // Layout: fixed + | scrollable badges (weight) | fixed send.
                                    // Plus stays outside the horizontal scroll so it never slides away.
                                    val badgeScroll = rememberScrollState()
                                    val isMono = LocalIsMonochrome.current
                                    val actionBadgeContainer = if (isMono) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.secondaryContainer
                                    val actionBadgeContent = if (isMono) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSecondaryContainer
                                    AttachmentMenuButton(
                                        // Match ComposerSendButton diameter + zero shadow; keep Surface fill.
                                        size = 46.dp,
                                        iconSize = 24.dp,
                                        onPhotos = {
                                            mediaLauncher.launch(
                                                androidx.activity.result.PickVisualMediaRequest(
                                                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageAndVideo
                                                )
                                            )
                                        },
                                        onFiles = { fileLauncher.launch("*/*") },
                                        forceWebSearch = forceWebSearch,
                                        onToggleForceWebSearch = onToggleForceWebSearch,
                                        forceImageGen = forceImageGen,
                                        onToggleForceImageGen = onToggleForceImageGen,
                                        forceGithub = forceGithub,
                                        onToggleForceGithub = onToggleForceGithub,
                                        showGithubConnector = showGithubConnector,
                                        forceTodoist = forceTodoist,
                                        onToggleForceTodoist = onToggleForceTodoist,
                                        showTodoistConnector = showTodoistConnector,
                                        forceNotion = forceNotion,
                                        onToggleForceNotion = onToggleForceNotion,
                                        showNotionConnector = showNotionConnector,
                                    )
                                    Row(
                                        modifier = Modifier
                                            .weight(1f)
                                            .horizontalScroll(badgeScroll)
                                            .padding(start = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (forceWebSearch) {
                                            Surface(
                                                onClick = onToggleForceWebSearch,
                                                shape = RoundedCornerShape(50),
                                                color = actionBadgeContainer,
                                                contentColor = actionBadgeContent,
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.Search,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = stringResource(R.string.force_web_search),
                                                        style = MaterialTheme.typography.labelLarge,
                                                        fontWeight = FontWeight.SemiBold,
                                                        maxLines = 1,
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                        }
                                        if (forceImageGen) {
                                            Surface(
                                                onClick = onToggleForceImageGen,
                                                shape = RoundedCornerShape(50),
                                                color = actionBadgeContainer,
                                                contentColor = actionBadgeContent,
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.Image,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = stringResource(R.string.force_image_gen),
                                                        style = MaterialTheme.typography.labelLarge,
                                                        fontWeight = FontWeight.SemiBold,
                                                        maxLines = 1,
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                        }
                                        if (forceGithub) {
                                            Surface(
                                                onClick = onToggleForceGithub,
                                                shape = RoundedCornerShape(50),
                                                color = actionBadgeContainer,
                                                contentColor = actionBadgeContent,
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                                ) {
                                                    Icon(
                                                        painter = painterResource(R.drawable.ic_github_mark),
                                                        contentDescription = null,
                                                        modifier = Modifier.size(18.dp),
                                                        tint = if (isMono) actionBadgeContent else MaterialTheme.colorScheme.onSecondaryContainer,
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = stringResource(R.string.force_github),
                                                        style = MaterialTheme.typography.labelLarge,
                                                        fontWeight = FontWeight.SemiBold,
                                                        maxLines = 1,
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                        }
                                        if (forceTodoist) {
                                            Surface(
                                                onClick = onToggleForceTodoist,
                                                shape = RoundedCornerShape(50),
                                                color = actionBadgeContainer,
                                                contentColor = actionBadgeContent,
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                                ) {
                                                    Icon(
                                                        painter = painterResource(R.drawable.ic_todoist_mark),
                                                        contentDescription = null,
                                                        modifier = Modifier.size(18.dp),
                                                        tint = if (isMono) actionBadgeContent else MaterialTheme.colorScheme.onSecondaryContainer,
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = stringResource(R.string.force_todoist),
                                                        style = MaterialTheme.typography.labelLarge,
                                                        fontWeight = FontWeight.SemiBold,
                                                        maxLines = 1,
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                        }
                                        if (forceNotion) {
                                            Surface(
                                                onClick = onToggleForceNotion,
                                                shape = RoundedCornerShape(50),
                                                color = actionBadgeContainer,
                                                contentColor = actionBadgeContent,
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                                ) {
                                                    Icon(
                                                        painter = painterResource(R.drawable.ic_notion_mark),
                                                        contentDescription = null,
                                                        modifier = Modifier.size(18.dp),
                                                        tint = if (isMono) actionBadgeContent else MaterialTheme.colorScheme.onSecondaryContainer,
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = stringResource(R.string.force_notion),
                                                        style = MaterialTheme.typography.labelLarge,
                                                        fontWeight = FontWeight.SemiBold,
                                                        maxLines = 1,
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                        }
                                        if (editingQueueItemId != null || isEditingSentMessage) {
                                            Surface(
                                                onClick = {
                                                    // Exit edit mode without applying composer changes.
                                                    textFieldState.edit { replace(0, length, "") }
                                                    composer.clearAttachments()
                                                    if (isEditingSentMessage) onClearSentMessageEdit() else onClearQueueEdit()
                                                },
                                                shape = RoundedCornerShape(50),
                                                color = actionBadgeContainer,
                                                contentColor = actionBadgeContent,
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.Edit,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = stringResource(
                                                            if (isEditingSentMessage) R.string.message_editing_sent
                                                            else R.string.message_queue_editing
                                                        ),
                                                        style = MaterialTheme.typography.labelLarge,
                                                        fontWeight = FontWeight.SemiBold,
                                                        maxLines = 1,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    ComposerSendButton(
                                        textFieldState = textFieldState,
                                        composer = composer,
                                        isLoading = isLoading,
                                        isSwitching = isSwitching,
                                        isModelValid = isModelValid,
                                        onSendMessage = onSendMessage,
                                        onStopGeneration = onStopGeneration,
                                        onCollapse = onCollapse,
                                    )
                                }
            }
        }
        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(250)),
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp, top = 4.dp)
        ) {
            val elevatedSurface = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
            IconButton(onClick = { if (!isExpandAnimating) onCollapse() }, modifier = Modifier.size(40.dp).background(Brush.radialGradient(listOf(elevatedSurface, elevatedSurface.copy(alpha = 0.5f), Color.Transparent)), CircleShape)) { Icon(painter = androidx.compose.ui.res.painterResource(id = R.drawable.collapse_all_24px), contentDescription = stringResource(R.string.collapse), modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)) }
        }
    }

    // File rejection dialog
    if (composer.rejectedMessage != null) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { composer.rejectedMessage = null },
            title = { Text(stringResource(R.string.file_unsupported_title), fontWeight = FontWeight.Bold) },
            text = { Text(composer.rejectedMessage!!) },
            confirmButton = {
                TextButton(onClick = { composer.rejectedMessage = null }) {
                    Text(stringResource(R.string.provider_close))
                }
            }
        )
    }

    // PDF page selection dialog
    if (composer.showPdfPageDialog && composer.pendingPdfUri != null) {
        PdfPageSelectDialog(
            totalPages = composer.pendingPdfPages,
            thumbnailPaths = composer.pendingPdfRenderedPaths,
            isLoading = composer.pendingPdfIsRendering,
            renderProgress = composer.pendingPdfRenderProgress,
            selectedPages = pdfViewerSelection,
            onTogglePage = { onTogglePdfSelection?.invoke(it) },
            onSelectAll = { select -> onTogglePdfSelection?.let { toggle ->
                (0 until composer.pendingPdfPages.coerceAtLeast(1)).forEach { i ->
                    if ((i in pdfViewerSelection) != select) toggle(i)
                }
            }},
            onPreviewPage = { index ->
                composer.showPdfPageDialog = false
                composer.pdfDialogHiddenForPreview = true
                onPdfPreviewSelect?.invoke(composer.pendingPdfRenderedPaths, index)
            },
            onConfirm = { selection ->
                composer.showPdfPageDialog = false
                val rendered = composer.pendingPdfRenderedPaths
                val sel = selection.selectedPages
                // Keep only the selected pages; delete the rest so unselected pages don't
                // pile up in filesDir. The kept paths are re-indexed 0..n so the attachment
                // and the send path (which filters preRenderedPaths by selectedPages) stay in sync.
                val keptPaths = rendered.filterIndexed { i, _ -> i in sel }
                rendered.filterIndexedTo(mutableListOf()) { i, _ -> i !in sel }
                    .forEach { runCatching { java.io.File(it).delete() } }
                composer.selectedAttachments = composer.selectedAttachments + com.newoether.agora.model.SelectedAttachment(
                    uri = composer.pendingPdfUri!!, type = "pdf",
                    mimeType = composer.pendingPdfMimeType,
                    fileName = composer.pendingPdfFileName,
                    selectedPages = keptPaths.indices.toSet(),
                    preRenderedPaths = keptPaths
                )
                composer.pendingPdfUri = null
                composer.pendingPdfRenderedPaths = emptyList()
            },
            onDismiss = {
                composer.showPdfPageDialog = false
                // Cancel an in-flight render (renderAllPages deletes its own partial files on
                // cancellation) and delete any fully-rendered pages — nothing was attached.
                composer.pdfRenderJob?.cancel()
                composer.pdfRenderJob = null
                composer.pendingPdfRenderedPaths.forEach { runCatching { java.io.File(it).delete() } }
                composer.pendingPdfUri = null
                composer.pendingPdfRenderedPaths = emptyList()
                composer.pendingPdfIsRendering = false
            }
        )
    }

    // Video slice dialog
    if (composer.showVideoSliceDialog && composer.pendingVideoUri != null) {
        VideoSliceDialog(
            videoUri = composer.pendingVideoUri!!,
            durationMs = composer.pendingVideoDurationMs,
            onConfirm = { result ->
                composer.showVideoSliceDialog = false
                composer.addSlicedVideo(result.uri, result.frameCount, result.intervalMs)
                // Process next video in queue
                composer.processNextVideo()
            },
            onDismiss = {
                composer.showVideoSliceDialog = false
                // Process next video in queue
                composer.processNextVideo()
            }
        )
    }
}
