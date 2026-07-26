package com.newoether.agora.util

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import com.newoether.agora.CrashReportActivity
import com.newoether.agora.api.HttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Anonymous, opt-in crash reporting.
 *
 * On an uncaught exception we:
 *  1. Persist a pending report to disk
 *  2. Immediately launch [CrashReportActivity] so the user can inspect / submit
 *  3. Kill the broken process
 *
 * Cold-start of [com.newoether.agora.MainActivity] still checks [pendingReport] as a
 * fallback when the in-process activity launch did not run (e.g. native abort).
 *
 * Nothing is ever uploaded without an explicit user tap on Submit.
 * The report carries stack + coarse environment only — no chat content, no device IDs.
 */
object CrashReporter {

    /**
     * Crash-upload endpoint. **Deliberately empty in this fork.**
     *
     * Upstream pointed this at the original author's own server, which a fork must
     * not keep using — it would ship this fork's users' crash data to a third party
     * nobody here controls, and it contradicts the "no servers, no telemetry" claim
     * in PRIVACY.md. Crashes are still captured and shown locally so the user can
     * copy the stack into a bug report; nothing leaves the device.
     *
     * To re-enable uploads, put your own endpoint here — [isSubmitEnabled] then
     * turns the Submit button back on automatically.
     */
    private const val ENDPOINT = ""

    /** False while [ENDPOINT] is unset; the UI hides Submit instead of failing on tap. */
    val isSubmitEnabled: Boolean get() = ENDPOINT.isNotBlank()
    private const val DIR = "crash"
    private const val FILE = "pending.json"
    private const val MAX_TRACE_CHARS = 60_000

    /** Coarse, non-identifying app identity captured once at install time. */
    private data class AppInfo(val versionName: String, val versionCode: Long)

    @Volatile private var appInfo: AppInfo = AppInfo("?", 0)
    @Volatile private var appContext: Context? = null

    /** Rolling diagnostic trail attached to crash reports. */
    private const val MAX_BREADCRUMBS = 60
    private val breadcrumbs = ConcurrentLinkedDeque<String>()

    /** Prevents a crash inside the crash path from looping forever. */
    private val handling = AtomicBoolean(false)

    /** Append a timestamped breadcrumb to the diagnostic trail (thread-safe, bounded). */
    fun note(message: String) {
        breadcrumbs.addLast("${System.currentTimeMillis()} $message")
        while (breadcrumbs.size > MAX_BREADCRUMBS) breadcrumbs.pollFirst()
    }

    /**
     * Registers the global uncaught-exception handler. Call once, as early as possible
     * (Application.onCreate), so crashes during startup are captured too.
     */
    fun install(context: Context) {
        val app = context.applicationContext
        appContext = app
        appInfo = readAppInfo(app)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Nested crash while we are already handling one: fall back to the platform.
            if (!handling.compareAndSet(false, true)) {
                previous?.uncaughtException(thread, throwable)
                return@setDefaultUncaughtExceptionHandler
            }
            // Never let the crash UI itself re-enter this path in a tight loop.
            if (isCrashUiThread(thread, throwable)) {
                previous?.uncaughtException(thread, throwable)
                return@setDefaultUncaughtExceptionHandler
            }
            val reportJson = runCatching { writeReport(app, throwable, thread) }.getOrNull()
            runCatching { launchCrashUi(app, reportJson) }
            // Hard-stop the broken process so we do not keep running with a torn-down runtime.
            // Do NOT chain to the previous handler first — it may show the system "app stopped"
            // dialog and race our activity start.
            try {
                Thread.sleep(120L)
            } catch (_: InterruptedException) {
                // ignore
            }
            runCatching { Process.killProcess(Process.myPid()) }
            exitProcess(10)
        }
    }

    /** Returns the pending crash report JSON, or null if none is waiting. */
    fun pendingReport(context: Context): String? {
        val f = reportFile(context)
        if (!f.exists() || f.length() == 0L) return null
        return runCatching { f.readText() }.getOrNull()
    }

    /** Discards the pending report (after the user submits or dismisses it). */
    fun clear(context: Context) {
        runCatching { reportFile(context).delete() }
    }

    /**
     * POSTs the given report JSON to the crash endpoint. Returns true on success.
     * Must be called off the main thread (it performs blocking network I/O).
     */
    fun submit(reportJson: String): Boolean {
        if (!isSubmitEnabled) return false   // no endpoint configured → never touch the network
        return runCatching { HttpClient.post(ENDPOINT, reportJson) != null }.getOrDefault(false)
    }

    /** Pretty / human extract of the stack from a stored report JSON. */
    fun extractTrace(reportJson: String): String =
        runCatching { JSONObject(reportJson).optString("trace", "") }.getOrDefault("")

    private fun launchCrashUi(context: Context, reportJson: String?) {
        val intent = Intent(context, CrashReportActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION,
            )
            // File is source of truth; extra is a best-effort handoff if the write succeeded.
            if (!reportJson.isNullOrBlank()) {
                putExtra(CrashReportActivity.EXTRA_REPORT_JSON, reportJson)
            }
        }
        context.startActivity(intent)
    }

    private fun isCrashUiThread(thread: Thread, throwable: Throwable): Boolean {
        val hay = buildString {
            append(thread.name)
            append(' ')
            append(throwable.javaClass.name)
            append(' ')
            append(throwable.message.orEmpty())
            throwable.stackTrace.take(12).forEach {
                append(it.className)
                append('#')
                append(it.methodName)
                append(' ')
            }
        }
        return hay.contains("CrashReportActivity", ignoreCase = true) ||
            hay.contains("CrashReporter", ignoreCase = true)
    }

    private fun writeReport(context: Context, throwable: Throwable, thread: Thread): String {
        val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }
            .toString()
            .take(MAX_TRACE_CHARS)
        val json = JSONObject().apply {
            put("trace", trace)
            put("thread", thread.name)
            put("exception", throwable.javaClass.name)
            put("message", throwable.message ?: "")
            put("appVersion", appInfo.versionName)
            put("versionCode", appInfo.versionCode)
            put("androidApi", Build.VERSION.SDK_INT)
            put("androidRelease", Build.VERSION.RELEASE)
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("ts", System.currentTimeMillis())
            put("breadcrumbs", JSONArray(breadcrumbs.toList()))
        }
        val text = json.toString()
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        File(dir, FILE).writeText(text)
        return text
    }

    private fun reportFile(context: Context): File = File(File(context.filesDir, DIR), FILE)

    @Suppress("DEPRECATION")
    private fun readAppInfo(context: Context): AppInfo = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode
        else pi.versionCode.toLong()
        AppInfo(pi.versionName ?: "?", code)
    }.getOrDefault(AppInfo("?", 0))
}
