package com.newoether.agora.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.components.DialogWindowEdgeToEdge
import com.newoether.agora.viewmodel.AskController
import com.newoether.agora.viewmodel.AskMode
import kotlinx.coroutines.launch

/**
 * Bottom sheet for the `ask_user` tool.
 *
 * Every mode carries a free-text field: the model's options are suggestions, not a
 * closed set. In SINGLE mode tapping an option submits straight away, while a typed
 * answer needs the send button beside it — otherwise every keystroke pause would
 * risk submitting a half-written answer. MULTIPLE and RANK collect picks and submit
 * with the confirm button, and their text field *adds* the typed answer to the list
 * so it can be selected or ranked alongside the rest.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskSheet(
    pending: AskController.PendingAsk,
    onAnswer: (values: List<String>, custom: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // Model options plus anything the user typed in, in display order.
    val options = remember(pending) { mutableStateListOf<String>().apply { addAll(pending.options) } }
    val customAdded = remember(pending) { mutableStateListOf<String>() }
    val selected = remember(pending) { mutableStateListOf<String>() }   // RANK: order matters
    var draft by remember(pending) { mutableStateOf("") }

    /** Collapse the sheet first so the answer leaves with an animation, not a jump cut. */
    fun submit(values: List<String>, custom: Boolean) {
        scope.launch {
            try { sheetState.hide() } finally { onAnswer(values, custom) }
        }
    }

    fun toggle(option: String) {
        if (selected.contains(option)) selected.remove(option) else selected.add(option)
    }

    fun addDraft() {
        val text = draft.trim()
        if (text.isEmpty()) return
        if (!options.contains(text)) {
            options.add(text)
            customAdded.add(text)
        }
        if (!selected.contains(text)) selected.add(text)
        draft = ""
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        DialogWindowEdgeToEdge()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
        ) {
            if (pending.header.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Text(
                        pending.header,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
            Text(
                pending.question,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(
                    when (pending.mode) {
                        AskMode.SINGLE -> R.string.ask_hint_single
                        AskMode.MULTIPLE -> R.string.ask_hint_multiple
                        AskMode.RANK -> R.string.ask_hint_rank
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            Spacer(Modifier.height(14.dp))
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                options.forEach { option ->
                    AskOptionRow(
                        label = option,
                        mode = pending.mode,
                        rank = selected.indexOf(option).takeIf { it >= 0 }?.plus(1),
                        checked = selected.contains(option),
                        isCustom = customAdded.contains(option),
                        onClick = {
                            when (pending.mode) {
                                // A tap is the whole interaction in single-choice mode.
                                AskMode.SINGLE -> submit(listOf(option), customAdded.contains(option))
                                AskMode.MULTIPLE, AskMode.RANK -> toggle(option)
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = {
                        Text(
                            stringResource(
                                if (pending.mode == AskMode.SINGLE) R.string.ask_custom_hint_single
                                else R.string.ask_custom_hint_add
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    shape = RoundedCornerShape(20.dp),
                    maxLines = 3,
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.width(8.dp))
                // Single choice sends the typed answer outright; the other modes fold it
                // into the option list so it can be checked or ranked with the rest.
                IconButton(
                    onClick = {
                        if (pending.mode == AskMode.SINGLE) {
                            draft.trim().takeIf { it.isNotEmpty() }?.let { submit(listOf(it), custom = true) }
                        } else {
                            addDraft()
                        }
                    },
                    enabled = draft.isNotBlank(),
                ) {
                    Icon(
                        if (pending.mode == AskMode.SINGLE) Icons.AutoMirrored.Filled.Send else Icons.Default.Add,
                        contentDescription = stringResource(
                            if (pending.mode == AskMode.SINGLE) R.string.ask_send else R.string.ask_add_option
                        ),
                        tint = if (draft.isNotBlank()) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                }
            }

            if (pending.mode != AskMode.SINGLE) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            submit(selected.toList(), selected.any { customAdded.contains(it) })
                        },
                        enabled = selected.isNotEmpty(),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Text(
                            stringResource(
                                if (pending.mode == AskMode.RANK) R.string.ask_confirm_rank
                                else R.string.ask_confirm_selected, selected.size
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AskOptionRow(
    label: String,
    mode: AskMode,
    rank: Int?,
    checked: Boolean,
    isCustom: Boolean,
    onClick: () -> Unit,
) {
    val selectedLook = checked || rank != null
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selectedLook) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (mode) {
                AskMode.SINGLE -> RadioButton(selected = false, onClick = onClick, modifier = Modifier.size(22.dp))
                AskMode.MULTIPLE -> Checkbox(checked = checked, onCheckedChange = { onClick() }, modifier = Modifier.size(22.dp))
                // The rank badge doubles as the control: tap to append, tap again to drop.
                AskMode.RANK -> Box(
                    modifier = Modifier.size(22.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        shape = RoundedCornerShape(11.dp),
                        color = if (rank != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = if (rank != null) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                rank?.toString() ?: "",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (isCustom) {
                Surface(
                    shape = RoundedCornerShape(5.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Text(
                        stringResource(R.string.ask_custom_tag),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}
