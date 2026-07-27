package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import com.newoether.agora.model.apiModelName
import com.newoether.agora.util.Constants
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class MessageTokenBreakdown(
    val totalInputTokens: Int,
    val freshInputTokens: Int,
    val cachedInputTokens: Int,
    val cacheTelemetryAvailable: Boolean,
    val outputTokens: Int,
)

internal fun messageTokenBreakdown(
    promptTokens: Int,
    cachedPromptTokens: Int,
    cacheTelemetryAvailable: Boolean,
    completionTokens: Int,
): MessageTokenBreakdown {
    val totalInput = promptTokens.coerceAtLeast(0)
    val cachedInput = cachedPromptTokens.coerceIn(0, totalInput)
    return MessageTokenBreakdown(
        totalInputTokens = totalInput,
        freshInputTokens = totalInput - cachedInput,
        cachedInputTokens = cachedInput,
        cacheTelemetryAvailable = cacheTelemetryAvailable && totalInput > 0,
        outputTokens = completionTokens.coerceAtLeast(0),
    )
}

/** Read-only message metadata (timestamp + resolved model name) shown from the overflow menu. */
@Composable
internal fun MessageInfoDialog(
    message: ChatMessage,
    modelAliases: Map<String, String>,
    onDismiss: () -> Unit
) {
    val sdf = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val dateString = sdf.format(Date(message.completedAt ?: message.timestamp))
    val modelDisplay = if (message.modelName != null) {
        val parsed = message.modelName?.let { com.newoether.agora.model.ModelId.parse(it) }
        val modelId = parsed?.apiModelName ?: message.modelName
        val provider = parsed?.providerName ?: Constants.PROVIDER_UNKNOWN
        modelAliases[message.modelName] ?: ("$modelId ($provider)")
    } else stringResource(R.string.unknown)

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.message_info), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                val bodyStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp)
                Text(stringResource(R.string.time_with_label, dateString), style = bodyStyle)
                if (message.participant == Participant.MODEL) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.model_with_label, modelDisplay), style = bodyStyle)
                    // First-token latency
                    if (message.ttftMs > 0L) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.ttft_with_label, message.ttftMs), style = bodyStyle)
                    }
                    val usage = messageTokenBreakdown(
                        promptTokens = message.promptTokens,
                        cachedPromptTokens = message.cachedPromptTokens,
                        cacheTelemetryAvailable = message.cacheTelemetryAvailable,
                        completionTokens = message.completionTokens,
                    )
                    if (usage.totalInputTokens > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.input_tokens_total_with_label, usage.totalInputTokens), style = bodyStyle)
                        if (usage.cacheTelemetryAvailable) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(stringResource(R.string.input_tokens_fresh_with_label, usage.freshInputTokens), style = bodyStyle)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(stringResource(R.string.input_tokens_cached_with_label, usage.cachedInputTokens), style = bodyStyle)
                        } else {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(stringResource(R.string.input_tokens_cache_not_reported), style = bodyStyle)
                        }
                    }
                    if (usage.outputTokens > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.output_tokens_with_label, usage.outputTokens), style = bodyStyle)
                    }
                    if (message.tokenCount > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.total_tokens, message.tokenCount), style = bodyStyle)
                    }
                } else if (message.participant == Participant.USER) {
                    val tokenCount = com.newoether.agora.util.TokenEstimator.estimate(message.text)
                    if (message.text.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.user_tokens_with_label, tokenCount), style = bodyStyle)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.provider_close)) }
        }
    )
}

/** Destructive confirmation for deleting a single message (and its subtree). */
@Composable
internal fun MessageDeleteDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_message_title), fontWeight = FontWeight.Bold) },
        text = { Text(stringResource(R.string.delete_message_confirm)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text(stringResource(R.string.delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
