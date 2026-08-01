package com.newoether.agora.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ToolCallDisplayModes
import com.newoether.agora.ui.chat.message.MessageItem
import com.newoether.agora.util.Constants

/** Statuses that mean the model is still producing this message. */
private val STREAMING_STATUSES = setOf(
    MessageStatus.SENDING,
    MessageStatus.THINKING,
    MessageStatus.TOOL_CALLING,
    MessageStatus.TRANSCRIBING,
)

@Composable
fun MessageList(
    messages: List<ChatMessage>,
    allMessages: List<ChatMessage> = emptyList(),
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(8.dp),
    state: LazyListState = rememberLazyListState(),
    userScrollEnabled: Boolean = true,
    isLoading: Boolean = false,
    isSwitching: Boolean = false,
    visualizeContextRollout: Boolean = false,
    toolCallDisplayMode: String = ToolCallDisplayModes.DEFAULT,
    maxContextWindow: Int = 20,
    modelAliases: Map<String, String> = emptyMap(),
    bottomBarHeight: Dp = 0.dp,
    viewportHeight: Int = 0,
    messageHeights: SnapshotStateMap<String, Int> = remember { mutableStateMapOf() },
    onEditMessage: (String, String) -> Unit = { _, _ -> },
    onBeginComposerEdit: (String) -> Unit = {},
    onSwitchBranch: (String?, String, Int) -> Unit = { _, _, _ -> },
    onRegenerate: (String) -> Unit = {},
    onDelete: (String) -> Unit = {},
    onMediaClick: (List<String>, Int) -> Unit = { _, _ -> },
    onFileContentClick: ((fileName: String, content: String) -> Unit)? = null,
    onPdfPagesClick: ((pages: List<String>, startIndex: Int) -> Unit)? = null,
    thoughtExpandedStates: SnapshotStateMap<String, Boolean> = remember { mutableStateMapOf() }
) {
    var editingMessageId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(isLoading) { if (isLoading) editingMessageId = null }
    val density = androidx.compose.ui.platform.LocalDensity.current

    val currentPath = messages.filter { it.participant != Participant.ERROR }
    val contextStartIndex = if (currentPath.size > maxContextWindow) currentPath.size - maxContextWindow else 0
    val inContextIds = currentPath.drop(contextStartIndex).map { it.id }.toSet()

    val lastUserMessageIndex = messages.indexOfLast { it.participant == Participant.USER }

    // Precompute branch siblings grouped by parent once per allMessages change.
    // Previously this filter+sort ran per visible item (O(n²) and re-run on every
    // streaming-token recomposition of the active message).
    val siblingsByParent = remember(allMessages) {
        allMessages
            .filter { !it.id.startsWith(Constants.TOOL_MSG_PREFIX) && !it.id.startsWith(Constants.RESULT_MSG_PREFIX) }
            .groupBy { it.parentId }
            .mapValues { (_, v) -> v.sortedBy { it.timestamp } }
    }

    // Cushion that lets the newest user message sit ~140dp from the top while its reply is still
    // short. It shrinks as the reply grows, and must never grow back: a reply's *reported* height
    // dips after the fact (MessageItem re-reports 50ms after the terminal status, and the thought
    // block's collapsed height is only captured 500ms after it settles). Each dip would widen this
    // spacer, pushing the anchored content upward — the jump seen right after a reply finishes,
    // and again on every later correction. Clamping the cushion to non-increasing makes those
    // corrections inert while keeping the shrink-as-you-grow behaviour the anchor depends on.
    //
    // The running minimum is held in a plain remembered box rather than snapshot state: it is
    // derived from `messageHeights`, which already invalidates this composable, so making it
    // observable would only add a redundant recomposition per height report. A composition that
    // is discarded can still have lowered the floor, which at worst leaves the cushion slightly
    // smaller than ideal — the same direction this fix wants, and never a jump.
    val cushion = remember(messages.lastOrNull()?.id, viewportHeight, bottomBarHeight) {
        object { var floorDp: Float = Float.MAX_VALUE }
    }
    val extraPadding = if (lastUserMessageIndex == -1 || viewportHeight == 0) {
        0.dp
    } else {
        with(density) {
            val availableSpaceDp = viewportHeight.toDp() - 140.dp - (bottomBarHeight + 8.dp)
            var contentHeightPx = 0
            for (i in lastUserMessageIndex until messages.size) {
                contentHeightPx += messageHeights[messages[i].id] ?: 0
            }
            val live = (availableSpaceDp - contentHeightPx.toDp()).coerceAtLeast(0.dp)
            cushion.floorDp = minOf(cushion.floorDp, live.value)
            cushion.floorDp.dp
        }
    }

    Box(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            reverseLayout = false,
            state = state,
            userScrollEnabled = userScrollEnabled
        ) {
            items(messages, key = { it.id }) { message ->
                val isLastMessage = messages.lastOrNull()?.id == message.id
                val isInContext = inContextIds.contains(message.id)
                val siblings = siblingsByParent[message.parentId].orEmpty()
                val branchIndex = siblings.indexOfFirst { it.id == message.id }
                val totalBranches = siblings.size

                // Fade newly-appended messages in. Placement/fade-out left off so this
                // doesn't fight the manual height/scroll padding management below.
                Box(modifier = Modifier.animateItem(fadeInSpec = tween(400), placementSpec = null, fadeOutSpec = null)) {
                MessageItem(
                    message = message,
                    onEdit = { id, text ->
                        onEditMessage(id, text)
                        editingMessageId = null
                    },
                    // isStreaming driven by message status, not isLoading flag
                    isStreaming = isLastMessage && message.participant == Participant.MODEL
                        && message.status in STREAMING_STATUSES,
                    isLoading = isLoading,
                    isEditingAllowed = (editingMessageId == null || editingMessageId == message.id) && !isLoading,
                    isEditing = false,
                    isSwitching = isSwitching,
                    isInContext = isInContext,
                    modelAliases = modelAliases,
                    visualizeContextRollout = visualizeContextRollout,
                    toolCallDisplayMode = toolCallDisplayMode,
                    onStartEdit = {
                        // Queue-style edit: load text into composer, not in-bubble editor.
                        onBeginComposerEdit(message.id)
                    },
                    onCancelEdit = { editingMessageId = null },
                    branchIndex = branchIndex,
                    totalBranches = totalBranches,
                    onSwitchBranch = { direction -> onSwitchBranch(message.parentId, message.id, direction) },
                    onRegenerate = onRegenerate,
                    onDelete = onDelete,
                    onMediaClick = onMediaClick,
                    onFileContentClick = onFileContentClick,
                    onPdfPagesClick = onPdfPagesClick,
                    onHeightChanged = { height -> messageHeights[message.id] = height },
                    thoughtExpandedStates = thoughtExpandedStates
                )
                }
            }
            item {
                Spacer(modifier = Modifier.height(extraPadding))
            }
        }
    }
}
