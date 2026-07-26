package com.newoether.agora.util

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val version: String,
    val url: String,
    val body: String,
    /** Direct APK URL suitable for in-app download (Worker-proxied when present). */
    val downloadUrl: String? = null,
    val htmlUrl: String = url,
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

object UpdateChecker {
    /**
     * Cloudflare Worker that proxies latest-release checks for the private
     * GitHub repository. Token never ships in the APK.
     */
    private const val WORKER_BASE =
        "https://agora-update-check.mirikawww.workers.dev"
    private const val UPDATE_ENDPOINT = "$WORKER_BASE/latest"
    private const val RELEASES_ENDPOINT = "$WORKER_BASE/releases"

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

    /**
     * Check the update endpoint for a newer release. Returns [UpdateInfo] if an
     * update is available, or null if the current version is up-to-date or the
     * check fails.
     */
    fun check(currentVersion: String): UpdateInfo? {
        return try {
            if (UPDATE_ENDPOINT.contains("CHANGE_ME")) {
                // Endpoint not configured yet — skip quietly.
                return null
            }

            val request = Request.Builder()
                .url(UPDATE_ENDPOINT)
                .header("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            if (response.code == 204) {
                response.close()
                return null
            }
            if (!response.isSuccessful) {
                response.close()
                return null
            }

            val body = response.body.string()
            response.close()

            val release = json.decodeFromString<LatestRelease>(body)
            val latestVersion = (release.version?.takeIf { it.isNotBlank() }
                ?: release.tag_name.removePrefix("v").removePrefix("V"))

            if (compareVersions(latestVersion, currentVersion) > 0) {
                val download = release.download_url?.takeIf { it.isNotBlank() }
                UpdateInfo(
                    version = latestVersion,
                    // Prefer Worker-proxied APK download when available (private assets),
                    // otherwise fall back to the release page URL.
                    url = download ?: release.html_url,
                    body = release.body.orEmpty(),
                    downloadUrl = download,
                    htmlUrl = release.html_url,
                )
            } else {
                null
            }
        } catch (_: Exception) {
            null
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
