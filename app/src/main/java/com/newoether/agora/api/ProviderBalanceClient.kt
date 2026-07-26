package com.newoether.agora.api

import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads an account balance / remaining credit from a provider.
 *
 * No provider spec covers this: OpenRouter exposes `/credits`, SiliconFlow
 * `/user/info`, others nothing at all — and each buries the number under a
 * different field. So the endpoint path and the field to read are user-supplied
 * (see `ProviderBalanceConfig`), and this client only handles the mechanics:
 * URL joining, the provider's own auth-header dialect, and the dot-path lookup.
 */
object ProviderBalanceClient {

    private val json = Json { ignoreUnknownKeys = true }

    sealed class Result {
        /** [value] is the raw field content, [url] the endpoint it came from. */
        data class Success(val value: String, val url: String) : Result()

        /**
         * [code] is a stable machine-readable reason (`no_base_url`, `http_error`,
         * `key_not_found`, …); [detail] carries the specifics for display/logging.
         */
        data class Failure(val code: String, val detail: String = "") : Result()
    }

    /**
     * Joins [path] onto [baseUrl]. An absolute http(s) [path] wins outright, which
     * is the escape hatch for providers whose balance API lives off the chat host.
     */
    fun resolveUrl(baseUrl: String?, path: String): String? {
        val trimmedPath = path.trim()
        if (trimmedPath.startsWith("http://", ignoreCase = true) ||
            trimmedPath.startsWith("https://", ignoreCase = true)
        ) return trimmedPath
        val base = baseUrl?.trim()?.trimEnd('/').orEmpty()
        if (base.isBlank()) return null
        if (trimmedPath.isBlank()) return base
        return "$base/${trimmedPath.trimStart('/')}"
    }

    /** The credential header dialect of [providerName] — same as its model-catalog fetch. */
    fun authHeaders(providerName: String, apiKey: String): Map<String, String> = buildMap {
        put("Accept", "application/json")
        if (apiKey.isBlank()) return@buildMap
        when (providerName) {
            Constants.PROVIDER_ANTHROPIC -> {
                put("x-api-key", apiKey)
                put("anthropic-version", "2023-06-01")
            }
            Constants.PROVIDER_GOOGLE -> put("x-goog-api-key", apiKey)
            else -> put("Authorization", "Bearer $apiKey")
        }
    }

    /**
     * Walks a dot path such as `data.total_usage` (numeric segments index arrays)
     * and returns the primitive found there, or null if the path doesn't resolve.
     */
    fun extract(root: JsonElement, dotPath: String): JsonPrimitive? {
        var current: JsonElement = root
        for (segment in dotPath.split('.').map { it.trim() }.filter { it.isNotEmpty() }) {
            val node = current
            val next = when (node) {
                is JsonObject -> node[segment]
                is JsonArray -> segment.toIntOrNull()?.let { node.getOrNull(it) }
                else -> null
            } ?: return null
            current = next
        }
        return current as? JsonPrimitive
    }

    /**
     * Performs the probe. Runs on [Dispatchers.IO] and never throws — every
     * failure mode (missing URL, transport error, HTTP status, unparseable body,
     * absent key) comes back as [Result.Failure].
     */
    suspend fun fetch(
        providerName: String,
        baseUrl: String?,
        apiKey: String,
        path: String,
        jsonKey: String
    ): Result = withContext(Dispatchers.IO) {
        if (path.isBlank()) return@withContext Result.Failure("no_path")
        if (jsonKey.isBlank()) return@withContext Result.Failure("no_json_key")
        val url = resolveUrl(baseUrl, path) ?: return@withContext Result.Failure("no_base_url")

        val response = HttpClient.get(url, authHeaders(providerName, apiKey))
        if (!response.isSuccessful) {
            val detail = if (response.code == 0) response.error.orEmpty() else "HTTP ${response.code}"
            DebugLog.e("ProviderBalance", "[$providerName] GET $url failed: $detail")
            return@withContext Result.Failure(
                if (response.code == 0) "network_error" else "http_error",
                detail
            )
        }
        val body = response.body?.takeIf { it.isNotBlank() }
            ?: return@withContext Result.Failure("empty_response")

        val root = try {
            json.parseToJsonElement(body)
        } catch (e: Exception) {
            return@withContext Result.Failure("invalid_json", e.message.orEmpty())
        }
        val value = extract(root, jsonKey)
            ?: return@withContext Result.Failure("key_not_found", jsonKey)
        Result.Success(value.content, url)
    }
}
