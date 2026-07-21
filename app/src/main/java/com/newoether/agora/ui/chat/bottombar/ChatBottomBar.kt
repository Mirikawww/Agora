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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.*
import androidx.compose.material.icons.Icons

import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Image

import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.ui.chat.PdfPageSelectDialog
import com.newoether.agora.ui.chat.VideoSliceDialog
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.ui.theme.ChatType
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
    onVideos: () -> Unit,
    onFiles: () -> Unit,
) {
    val haptics = LocalAgoraHaptics.current
    var showAddMenu by remember { mutableStateOf(false) }
    var lastAddDismissTime by remember { mutableLongStateOf(0L) }

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
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
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
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
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
                        Icon(Icons.Default.Videocam, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.videos))
                    }
                },
                onClick = {
                    haptics.selection()
                    showAddMenu = false
                    lastAddDismissTime = 0L
                    onVideos()
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
    onExpand: () -> Unit = {}
) {
    val scrollState = rememberScrollState()
    BackHandler(enabled = isExpanded) { onCollapse() }
    val isModelValid = selectedModel.isNotBlank() && enabledModels.contains(selectedModel)

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

    val photoLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> composer.onPickImages(uris) }
    val videoLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> composer.onPickVideos(uris) }
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
                        visible = !isExpanded,
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
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    AttachmentMenuButton(
                        // Match ComposerSendButton diameter + zero shadow; keep Surface fill.
                        size = 46.dp,
                        iconSize = 24.dp,
                        onPhotos = {
                            photoLauncher.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        onVideos = {
                            videoLauncher.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.VideoOnly))
                        },
                        onFiles = { fileLauncher.launch("*/*") }
                    )
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
