package com.newoether.agora.tool

import com.newoether.agora.api.HttpClient
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * GitHub connector — full REST surface the user token allows (including destructive ops).
 * Capability is gated solely by the PAT scopes the user configured.
 */
class GitHubConnectorToolProvider : ToolProvider {

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.githubEnabled || ctx.githubToken.isBlank()) return emptyList()
        return listOf(
            ToolDefinition(
                function = ToolFunction(
                    name = "github_request",
                    description = "Call the GitHub REST API with the user's configured personal access token. Use for any GitHub operation the token permits: repos, issues, PRs, contents, workflows, deletions, etc. Base URL is https://api.github.com. Prefer official REST paths (e.g. /user, /repos/{owner}/{repo}, /user/repos). Destructive actions (delete repo, force-push related, etc.) are allowed if the token has those scopes — confirm intent with the user for irreversible ops when unclear.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "method" to ToolProperty("string", "HTTP method: GET, POST, PUT, PATCH, DELETE."),
                            "path" to ToolProperty(
                                "string",
                                "API path starting with /, e.g. /user/repos or /repos/octocat/Hello-World."
                            ),
                            "query" to ToolProperty(
                                "string",
                                "Optional raw query string without leading ?, e.g. per_page=30&page=1"
                            ),
                            "body" to ToolProperty(
                                "string",
                                "Optional JSON request body as a string for POST/PUT/PATCH."
                            ),
                        ),
                        required = listOf("method", "path"),
                    ),
                )
            )
        )
    }

    override fun handles(name: String): Boolean = name == "github_request"

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String =
        withContext(Dispatchers.IO) {
            if (!ctx.githubEnabled || ctx.githubToken.isBlank()) {
                return@withContext """{"error":"github_not_configured"}"""
            }
            val args = try {
                Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(
                    arguments.ifBlank { "{}" }
                )
            } catch (_: Exception) {
                emptyMap()
            }
            val method = ((args["method"] as? JsonPrimitive)?.content ?: "GET").uppercase()
            val path = (args["path"] as? JsonPrimitive)?.content?.trim().orEmpty()
            val query = (args["query"] as? JsonPrimitive)?.content?.trim().orEmpty()
            val body = (args["body"] as? JsonPrimitive)?.content
            if (!path.startsWith("/")) {
                return@withContext """{"error":"invalid_path","message":"path must start with /"}"""
            }
            val url = buildString {
                append("https://api.github.com")
                append(path)
                if (query.isNotEmpty()) {
                    append('?')
                    append(query.removePrefix("?"))
                }
            }
            try {
                val media = "application/json; charset=utf-8".toMediaType()
                val reqBody = when (method) {
                    "GET", "DELETE", "HEAD" -> null
                    else -> (body ?: "{}").toRequestBody(media)
                }
                val request = Request.Builder()
                    .url(url)
                    .method(method, reqBody)
                    .header("Authorization", "Bearer ${ctx.githubToken}")
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("User-Agent", "Agora-Android")
                    .build()
                val response = HttpClient.client.newCall(request).execute()
                response.use { resp ->
                    val respBody = resp.body?.string().orEmpty()
                    val clipped = if (respBody.length > 80_000) {
                        respBody.take(80_000) + "\n…[truncated]"
                    } else respBody
                    buildJsonObject {
                        put("status", resp.code)
                        put("ok", resp.isSuccessful)
                        put("body", clipped)
                    }.toString()
                }
            } catch (e: Exception) {
                DebugLog.e("GitHubConnector", "github_request failed", e)
                buildJsonObject {
                    put("error", "request_failed")
                    put("message", e.message ?: "unknown")
                }.toString()
            }
        }
}
