package com.newoether.agora.util

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Where updates come from. */
enum class UpdateChannel(val id: String) {
    /** Published GitHub releases — versioned, permanent. */
    STABLE("stable"),

    /** Newest successful Actions build, served straight from its artifact. */
    CI("ci");

    companion object {
        fun from(id: String?): UpdateChannel =
            entries.firstOrNull { it.id == id?.trim()?.lowercase() } ?: STABLE
    }
}

data class UpdateInfo(
    val version: String,
    val url: String,
    val body: String,
    /** Direct APK URL suitable for in-app download (Worker-proxied when present). */
    val downloadUrl: String? = null,
    val htmlUrl: String = url,
    /**
     * True when [downloadUrl] serves a ZIP of split APKs rather than one APK —
     * the CI channel streams an Actions artifact, which is always an archive.
     */
    val isZipArchive: Boolean = false,
    val channel: UpdateChannel = UpdateChannel.STABLE,
) {
    val canDownloadApk: Boolean
        get() = !downloadUrl.isNullOrBlank() || UpdateInstaller.looksLikeApkUrl(url)
    val apkUrl: String?
        get() = downloadUrl?.takeIf { it.isNotBlank() }
            ?: url.takeIf { UpdateInstaller.looksLikeApkUrl(it) }

    /**
     * Prefer device primary ABI APK via Worker `?abi=…`, falling back to universal
     * selection server-side when the ABI-specific asset is missing.
     */
    fun apkUrlForDevice(): String? {
        val base = apkUrl ?: return null
        if (!base.contains("workers.dev") && !base.contains("/download")) return base
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        if (abi.isBlank()) return base
        val sep = if (base.contains("?")) "&" else "?"
        return "$base${sep}abi=${java.net.URLEncoder.encode(abi, Charsets.UTF_8.name())}"
    }
}

data class ReleaseNotes(
    val version: String,
    val body: String,
    val htmlUrl: String = "",
    val publishedAt: String = "",
)

/**
 * Outcome of an update check.
 *
 * "No update" and "the check did not complete" used to both be `null`, so the UI
 * reported *已是最新* for network errors, a misconfigured Worker, and a CI channel
 * that could not find a build alike — the one failure mode that most needs to be
 * visible was the one guaranteed to be silent.
 */
sealed class UpdateCheckResult {
    data class Available(val info: UpdateInfo) : UpdateCheckResult()

    /** The endpoint answered, and the installed build is current. */
    data object UpToDate : UpdateCheckResult()

    /** The check itself failed. [reason] is short and user-facing. */
    data class Failed(val reason: String) : UpdateCheckResult()
}

object UpdateChecker {
    /**
     * Cloudflare Worker that proxies latest-release checks for the private
     * GitHub repository. Token never ships in the APK.
     */
    private const val WORKER_BASE =
        "https://agora-update-check.mirikawww.workers.dev"
    private const val UPDATE_ENDPOINT = "$WORKER_BASE/latest"
    private const val RELEASES_ENDPOINT = "$WORKER_BASE/releases"
    private const val CI_ENDPOINT = "$WORKER_BASE/ci/latest"
    private const val CI_STATUS_ENDPOINT = "$WORKER_BASE/ci/status"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class LatestRelease(
        val tag_name: String,
        val html_url: String,
        val body: String? = null,
        // Optional Worker-only fields (ignored if absent)
        val version: String? = null,
        val download_url: String? = null,
        val published_at: String? = null,
    )

    /** `/ci/latest` payload — an Actions run, not a release, so there is no tag. */
    @Serializable
    private data class CiBuild(
        val run_number: Int = 0,
        val sha: String = "",
        val version: String = "",
        val title: String = "",
        val body: String = "",
        val html_url: String = "",
        val download_url: String? = null,
        val is_zip: Boolean = true,
        val size_bytes: Long = 0,
    )

    /**
     * `/ci/status` payload — the newest build run of ANY conclusion, so a queued or
     * in-progress build is visible. [conclusion] stays null until the run completes.
     */
    @Serializable
    data class CiRunStatus(
        val run_number: Int = 0,
        val run_id: Long = 0,
        /** queued | in_progress | completed */
        val status: String = "",
        /** success | failure | cancelled | null while running */
        val conclusion: String? = null,
        val title: String = "",
        val commit_sha: String = "",
        val created_at: String = "",
        val updated_at: String = "",
        val html_url: String = "",
    ) {
        val isRunning: Boolean get() = status == "queued" || status == "in_progress"
        val isSuccess: Boolean get() = conclusion == "success"
        val isFailure: Boolean get() = conclusion == "failure" || conclusion == "cancelled" ||
            conclusion == "timed_out"
    }

    /** Live CI build status, or null when unavailable (network error / no runs yet). */
    fun fetchCiStatus(): CiRunStatus? {
        return try {
            val request = Request.Builder()
                .url(CI_STATUS_ENDPOINT)
                .header("Accept", "application/json")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                // 204 = workflow exists but has no runs yet.
                if (response.code == 204 || !response.isSuccessful) return null
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return null
                json.decodeFromString<CiRunStatus>(body)
            }
        } catch (e: Exception) {
            DebugLog.w("UpdateChecker", "CI status fetch failed: ${e.message}")
            null
        }
    }

    /**
     * CI-channel check. Compares **run numbers**, not versions: every CI build
     * carries the same `versionName`, so semver comparison would never see a
     * newer build. [installedRun] is the run this APK came from (0 when the
     * installed build did not come from CI, in which case any CI build counts
     * as newer).
     */
    fun checkCi(installedRun: Int): UpdateCheckResult {
        return try {
            val request = Request.Builder()
                .url(CI_ENDPOINT)
                .header("Accept", "application/json")
                .build()
            val response = client.newCall(request).execute()
            // 204 is the one legitimate "nothing to offer": no successful build exists.
            if (response.code == 204) {
                response.close()
                return UpdateCheckResult.UpToDate
            }
            if (!response.isSuccessful) {
                val code = response.code
                val detail = runCatching { response.body.string() }.getOrNull()
                response.close()
                return UpdateCheckResult.Failed(describeHttpFailure(code, detail))
            }
            val body = response.body.string()
            response.close()

            val build = json.decodeFromString<CiBuild>(body)
            if (build.run_number <= installedRun) return UpdateCheckResult.UpToDate

            UpdateCheckResult.Available(
                UpdateInfo(
                    version = build.version.ifBlank { "ci-${build.run_number}" },
                    url = build.download_url ?: build.html_url,
                    body = build.body.ifBlank { build.title },
                    downloadUrl = build.download_url,
                    htmlUrl = build.html_url,
                    isZipArchive = build.is_zip,
                    channel = UpdateChannel.CI,
                )
            )
        } catch (e: Exception) {
            UpdateCheckResult.Failed(describeException(e))
        }
    }

    /**
     * Turns the Worker's error envelope into something actionable. Its failures are
     * specific for a reason — `no_artifact` (build's artifact expired) needs a very
     * different response from the user than a 500 (Worker misconfigured).
     */
    private fun describeHttpFailure(code: Int, body: String?): String {
        val tag = body?.let {
            runCatching {
                json.parseToJsonElement(it).jsonObject["error"]?.toString()?.trim('"')
            }.getOrNull()
        }
        return when (tag) {
            "no_artifact" -> "CI build artifact expired or missing"
            "no_run" -> "No successful CI build found"
            "misconfigured" -> "Update server misconfigured"
            "no_signed_url" -> "GitHub refused the artifact download"
            else -> "Update check failed (HTTP $code)"
        }
    }

    private fun describeException(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "No network connection"
        is java.net.SocketTimeoutException -> "Update server timed out"
        is javax.net.ssl.SSLException -> "Secure connection failed"
        is java.io.IOException -> "Network error: ${e.message ?: "unreachable"}"
        else -> "Unexpected response from update server"
    }

    /**
     * Check the update endpoint for a newer release. Distinguishes "up to date"
     * from "the check failed" so the caller can surface the difference.
     */
    fun check(currentVersion: String): UpdateCheckResult {
        return try {
            if (UPDATE_ENDPOINT.contains("CHANGE_ME")) {
                return UpdateCheckResult.Failed("Update endpoint not configured")
            }

            val request = Request.Builder()
                .url(UPDATE_ENDPOINT)
                .header("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            // 204 = repository has no releases at all; nothing newer can exist.
            if (response.code == 204) {
                response.close()
                return UpdateCheckResult.UpToDate
            }
            if (!response.isSuccessful) {
                val code = response.code
                val detail = runCatching { response.body.string() }.getOrNull()
                response.close()
                return UpdateCheckResult.Failed(describeHttpFailure(code, detail))
            }

            val body = response.body.string()
            response.close()

            val release = json.decodeFromString<LatestRelease>(body)
            val latestVersion = (release.version?.takeIf { it.isNotBlank() }
                ?: release.tag_name.removePrefix("v").removePrefix("V"))

            if (compareVersions(latestVersion, currentVersion) > 0) {
                val download = release.download_url?.takeIf { it.isNotBlank() }
                UpdateCheckResult.Available(
                    UpdateInfo(
                        version = latestVersion,
                        // Prefer Worker-proxied APK download when available (private assets),
                        // otherwise fall back to the release page URL.
                        url = download ?: release.html_url,
                        body = release.body.orEmpty(),
                        downloadUrl = download,
                        htmlUrl = release.html_url,
                    )
                )
            } else {
                UpdateCheckResult.UpToDate
            }
        } catch (e: Exception) {
            UpdateCheckResult.Failed(describeException(e))
        }
    }

    /**
     * Fetch **all** historical release notes (newest first) via the Worker.
     * Falls back to wrapping [check]'s latest payload when `/releases` is unavailable.
     */
    fun fetchAllReleaseNotes(currentVersion: String = ""): List<ReleaseNotes> {
        return try {
            val request = Request.Builder()
                .url(RELEASES_ENDPOINT)
                .header("Accept", "application/json")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return fallbackLatestNotes(currentVersion)
            }
            val body = response.body.string()
            response.close()
            // Accept either a bare array or `{ "releases": [...] }`.
            val trimmed = body.trim()
            val listJson = when {
                trimmed.startsWith("[") -> trimmed
                else -> {
                    val obj = json.parseToJsonElement(trimmed).jsonObject
                    obj["releases"]?.toString()
                        ?: obj["data"]?.toString()
                        ?: return fallbackLatestNotes(currentVersion)
                }
            }
            val releases = json.decodeFromString<List<LatestRelease>>(listJson)
            releases.map { r ->
                val ver = r.version?.takeIf { it.isNotBlank() }
                    ?: r.tag_name.removePrefix("v").removePrefix("V")
                ReleaseNotes(
                    version = ver,
                    body = r.body.orEmpty(),
                    htmlUrl = r.html_url,
                    publishedAt = r.published_at.orEmpty(),
                )
            }.ifEmpty { fallbackLatestNotes(currentVersion) }
        } catch (_: Exception) {
            fallbackLatestNotes(currentVersion)
        }
    }

    private fun fallbackLatestNotes(currentVersion: String): List<ReleaseNotes> {
        return try {
            val request = Request.Builder()
                .url(UPDATE_ENDPOINT)
                .header("Accept", "application/json")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return emptyList()
            }
            val body = response.body.string()
            response.close()
            val release = json.decodeFromString<LatestRelease>(body)
            val ver = release.version?.takeIf { it.isNotBlank() }
                ?: release.tag_name.removePrefix("v").removePrefix("V")
            listOf(
                ReleaseNotes(
                    version = ver,
                    body = release.body.orEmpty(),
                    htmlUrl = release.html_url,
                    publishedAt = release.published_at.orEmpty(),
                )
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Compare two semver strings (e.g. "1.0.10" vs "1.0.9").
     * Returns positive if [a] > [b], negative if [a] < [b], 0 if equal.
     */
    private fun compareVersions(a: String, b: String): Int {
        val partsA = a.split(".").map { it.toIntOrNull() ?: 0 }
        val partsB = b.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(partsA.size, partsB.size)
        for (i in 0 until maxLen) {
            val va = partsA.getOrElse(i) { 0 }
            val vb = partsB.getOrElse(i) { 0 }
            if (va != vb) return va - vb
        }
        return 0
    }
}
