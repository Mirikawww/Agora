package com.newoether.agora.util

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val version: String,
    val url: String,
    val body: String
)

object UpdateChecker {
    /**
     * Cloudflare Worker that proxies latest-release checks for the private
     * GitHub repository. Token never ships in the APK.
     */
    private const val UPDATE_ENDPOINT =
        "https://agora-update-check.mirikawww.workers.dev/latest"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
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
                UpdateInfo(
                    version = latestVersion,
                    // Prefer Worker-proxied APK download when available (private assets),
                    // otherwise fall back to the release page URL.
                    url = release.download_url?.takeIf { it.isNotBlank() } ?: release.html_url,
                    body = release.body.orEmpty()
                )
            } else {
                null
            }
        } catch (_: Exception) {
            null
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
