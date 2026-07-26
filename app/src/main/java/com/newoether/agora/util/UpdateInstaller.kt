package com.newoether.agora.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * In-app APK download + hand-off to the user's **default package installer**.
 *
 * Intentionally does **not** use [android.app.DownloadManager] (broken on some devices).
 * Download is performed with OkHttp into app cache, then exposed via FileProvider and
 * launched with ACTION_VIEW so the OS routes to the user's preferred installer app
 * (not the PackageInstaller session API).
 */
object UpdateInstaller {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .build()

    data class DownloadResult(
        val file: File,
        val contentLength: Long,
    )

    /**
     * Download [url] into `cache/updates/agora-update.apk`.
     * [onProgress] receives fraction in 0f..1f when total length is known, else null.
     */
    suspend fun downloadApk(
        url: String,
        context: Context,
        onProgress: (Float?) -> Unit = {},
    ): DownloadResult = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "agora-update.apk")
        if (out.exists()) out.delete()

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.android.package-archive, application/octet-stream, */*")
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("Download failed: HTTP ${response.code}")
        }
        val body = response.body ?: run {
            response.close()
            throw IOException("Empty response body")
        }
        val total = body.contentLength()
        body.byteStream().use { input ->
            out.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                var readTotal = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    readTotal += n
                    if (total > 0) {
                        onProgress((readTotal.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                    } else {
                        onProgress(null)
                    }
                }
                output.flush()
            }
        }
        response.close()
        if (out.length() < 1024L) {
            out.delete()
            throw IOException("Downloaded file too small")
        }
        onProgress(1f)
        DownloadResult(out, out.length())
    }

    fun canRequestInstall(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun installPermissionSettingsIntent(context: Context): Intent {
        return Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Hand the APK to the user's default installer via ACTION_VIEW.
     * Does **not** use PackageInstaller session API.
     */
    fun installWithDefaultInstaller(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk
        )
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // createChooser lets the user pick / use their preferred installer app.
        val chooser = Intent.createChooser(view, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    fun looksLikeApkUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.endsWith(".apk") ||
            lower.contains("/download/") ||
            lower.contains("application/vnd.android.package-archive") ||
            lower.contains("workers.dev") // Worker-proxied private assets
    }
}
