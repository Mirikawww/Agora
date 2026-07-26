package com.newoether.agora

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newoether.agora.ui.theme.AgoraTheme
import com.newoether.agora.util.CrashReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Shown immediately after an uncaught exception (and also reachable via a cold-start
 * fallback dialog in [MainActivity] if this activity failed to launch mid-crash).
 *
 * Submit is opt-in. Closing dismisses the pending report and returns to [MainActivity].
 */
class CrashReportActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val handedOff = intent?.getStringExtra(EXTRA_REPORT_JSON)
        setContent {
            AgoraTheme {
                CrashReportScreen(
                    initialReportJson = handedOff,
                    onFinished = {
                        // Clear task and open a fresh main so we do not resume a torn session.
                        val main = Intent(this, MainActivity::class.java).apply {
                            addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP,
                            )
                        }
                        startActivity(main)
                        finish()
                    },
                )
            }
        }
    }

    companion object {
        const val EXTRA_REPORT_JSON = "crash_report_json"
    }
}

@Composable
private fun CrashReportScreen(
    initialReportJson: String?,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var reportJson by remember { mutableStateOf(initialReportJson) }
    var loading by remember { mutableStateOf(initialReportJson.isNullOrBlank()) }
    var submitting by remember { mutableStateOf(false) }
    var submitFailed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (reportJson.isNullOrBlank()) {
            reportJson = withContext(Dispatchers.IO) { CrashReporter.pendingReport(context) }
        }
        loading = false
        // Nothing to show — go home rather than trapping the user.
        if (reportJson.isNullOrBlank()) onFinished()
    }

    fun dismissAndExit() {
        CrashReporter.clear(context)
        onFinished()
    }

    BackHandler(enabled = !submitting) { dismissAndExit() }

    val report = reportJson
    val trace = remember(report) {
        if (report.isNullOrBlank()) "" else CrashReporter.extractTrace(report)
    }
    val metaLine = remember(report) {
        if (report.isNullOrBlank()) ""
        else runCatching {
            val o = JSONObject(report)
            listOfNotNull(
                o.optString("exception").takeIf { it.isNotBlank() },
                o.optString("thread").takeIf { it.isNotBlank() }?.let { "thread=$it" },
                o.optString("appVersion").takeIf { it.isNotBlank() }?.let { "v$it" },
                o.optString("device").takeIf { it.isNotBlank() },
            ).joinToString(" · ")
        }.getOrDefault("")
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.BugReport,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.crash_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.crash_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(14.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier
                            .size(15.dp)
                            .padding(top = 1.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.crash_privacy_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (metaLine.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    metaLine,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (loading) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (trace.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(R.string.crash_log_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    IconButton(
                        onClick = { clipboard.setText(AnnotatedString(trace)) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.copy),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .heightIn(min = 160.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                    ) {
                        Text(
                            text = trace,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily(Font(R.font.jetbrains_mono_regular)),
                                lineHeight = 16.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
            }

            if (submitFailed) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.crash_submit_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { dismissAndExit() },
                    enabled = !submitting,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.crash_dismiss))
                }
                // No upload endpoint configured in this fork → Submit would always fail.
                if (CrashReporter.isSubmitEnabled) {
                    Button(
                        onClick = {
                            val payload = reportJson ?: return@Button
                            submitting = true
                            submitFailed = false
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { CrashReporter.submit(payload) }
                                if (ok) {
                                    CrashReporter.clear(context)
                                    onFinished()
                                } else {
                                    submitting = false
                                    submitFailed = true
                                }
                            }
                        },
                        enabled = !submitting && !reportJson.isNullOrBlank(),
                        modifier = Modifier.weight(1f),
                    ) {
                        if (submitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text(stringResource(R.string.crash_submit))
                        }
                    }
                }
            }
        }
    }
}
