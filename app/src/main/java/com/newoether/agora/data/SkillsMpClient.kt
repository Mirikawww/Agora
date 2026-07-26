package com.newoether.agora.data

import com.newoether.agora.api.HttpClient
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** Client for the SkillsMP catalog API. See https://skillsmp.com/docs/api. */
object SkillsMpClient {
    private const val TAG = "SkillsMpClient"
    private const val BASE_URL = "https://skillsmp.com"
    const val DOCS_URL = "$BASE_URL/docs/api"
    const val SITE_URL = BASE_URL

    /** A user-owned SkillsMP API key. An empty key uses SkillsMP's anonymous quota. */
    var apiToken: String = ""

    data class CatalogSkill(
        val id: String,
        val name: String,
        val author: String,
        val description: String,
        val stars: Long,
        val githubUrl: String,
        val url: String,
        val contentLanguage: String = "",
        /** Unix seconds from SkillsMP `updatedAt`; 0 if unknown. */
        val updatedAt: Long = 0L,
    )

    data class SearchResult(
        val query: String,
        val count: Int,
        val skills: List<CatalogSkill>,
        val durationMs: Long = 0,
    )

    data class InstalledContent(
        val name: String,
        val content: String,
        val sourceFileName: String,
        val catalogId: String,
        val source: String,
        val pageUrl: String,
        val fetchedFrom: String,
    )

    private val cachedSkills = ConcurrentHashMap<String, CatalogSkill>()

    suspend fun search(query: String, limit: Int = 30): Result<SearchResult> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.length < 2) {
            return@withContext Result.failure(IllegalArgumentException("query must be at least 2 characters"))
        }
        val capped = limit.coerceIn(1, 100)
        val url = "$BASE_URL/api/v1/skills/search?q=${enc(q)}&limit=$capped&sortBy=stars"
        try {
            val headers = buildMap {
                put("Accept", "application/json")
                apiToken.trim().takeIf { it.isNotEmpty() }?.let { put("Authorization", "Bearer $it") }
            }
            val body = HttpClient.fetchModels(url, headers)
                ?: return@withContext Result.failure(IOExceptionFriendly("SkillsMP search failed. Check your connection or API key."))
            val root = JSONObject(body)
            if (!root.optBoolean("success", true)) {
                val error = root.optJSONObject("error")
                return@withContext Result.failure(
                    IOExceptionFriendly(error?.optString("message").orEmpty().ifBlank { "SkillsMP search failed" })
                )
            }
            val data = root.optJSONObject("data") ?: JSONObject()
            val skills = parseSkills(data.optJSONArray("skills") ?: JSONArray()).take(capped)
            skills.forEach { cachedSkills[it.id] = it }
            Result.success(
                SearchResult(
                    query = data.optJSONObject("filters")?.optString("search", q).orEmpty().ifBlank { q },
                    count = skills.size,
                    skills = skills,
                    durationMs = root.optJSONObject("meta")?.optLong("responseTimeMs", 0L) ?: 0L,
                )
            )
        } catch (e: Exception) {
            DebugLog.e(TAG, "search failed: ${e.message}")
            Result.failure(e)
        }
    }

    /** SkillsMP has keyword search only; use its star sort for the store's popular tab. */
    suspend fun browsePopular(limit: Int = 40): Result<List<CatalogSkill>> =
        search("skill", limit).map { it.skills }

    suspend fun install(skill: CatalogSkill): Result<InstalledContent> = withContext(Dispatchers.IO) {
        fetchSkillMarkdown(skill).map { (content, from) ->
            InstalledContent(
                name = skill.name,
                content = content,
                sourceFileName = "${sanitize(skill.name)}.md",
                catalogId = skill.id,
                source = skill.githubUrl,
                pageUrl = skill.url,
                fetchedFrom = from,
            )
        }
    }

    /** Resolves a SkillsMP result returned earlier in this process, or searches by name. */
    suspend fun resolveCatalogSkill(ref: String): Result<CatalogSkill> = withContext(Dispatchers.IO) {
        val trimmed = ref.trim()
        if (trimmed.isBlank()) return@withContext Result.failure(IllegalArgumentException("missing skill id or name"))
        cachedSkills[trimmed]?.let { return@withContext Result.success(it) }
        val result = search(trimmed, limit = 40).getOrElse { return@withContext Result.failure(it) }
        val exact = result.skills.firstOrNull {
            it.id.equals(trimmed, ignoreCase = true) || it.name.equals(trimmed, ignoreCase = true)
        }
        Result.success(exact ?: result.skills.firstOrNull()
            ?: return@withContext Result.failure(IOExceptionFriendly("skill not found: $trimmed")))
    }

    suspend fun install(skillRef: String): Result<InstalledContent> = withContext(Dispatchers.IO) {
        val catalog = resolveCatalogSkill(skillRef).getOrElse { return@withContext Result.failure(it) }
        install(catalog)
    }

    private fun parseSkills(skills: JSONArray): List<CatalogSkill> = buildList {
        for (index in 0 until skills.length()) {
            val item = skills.optJSONObject(index) ?: continue
            val id = item.optString("id")
            val githubUrl = item.optString("githubUrl")
            if (id.isBlank() || githubUrl.isBlank()) continue
            add(
                CatalogSkill(
                    id = id,
                    name = item.optString("name").ifBlank { id },
                    author = item.optString("author"),
                    description = item.optString("description"),
                    stars = item.optLong("stars", 0L),
                    githubUrl = githubUrl,
                    url = item.optString("skillUrl").ifBlank { "$SITE_URL/skills/$id" },
                    contentLanguage = item.optString("contentLanguage"),
                    updatedAt = item.optLong("updatedAt", 0L),
                )
            )
        }
    }

    private fun fetchSkillMarkdown(skill: CatalogSkill): Result<Pair<String, String>> {
        val candidates = githubRawCandidates(skill.githubUrl)
        if (candidates.isEmpty()) {
            return Result.failure(IOExceptionFriendly("SkillsMP did not provide a supported GitHub source for ${skill.name}"))
        }
        var lastError: String? = null
        for (url in candidates) {
            try {
                val body = HttpClient.fetchModels(url) ?: continue
                val trimmed = body.trim()
                if (trimmed.isNotBlank() && !trimmed.startsWith("<!DOCTYPE", true) && !trimmed.startsWith("<html", true)) {
                    return Result.success(trimmed to url)
                }
            } catch (e: Exception) {
                lastError = e.message
            }
        }
        return Result.failure(
            IOExceptionFriendly(
                "could not download SKILL.md for ${skill.name}" +
                    (lastError?.let { " ($it)" } ?: "") + ". Open ${skill.url} to review the source."
            )
        )
    }

    private fun githubRawCandidates(githubUrl: String): List<String> {
        val match = GITHUB_TREE_OR_BLOB.matchEntire(githubUrl.trim()) ?: return emptyList()
        val owner = match.groupValues[1]
        val repo = match.groupValues[2]
        val kind = match.groupValues[3]
        val branch = match.groupValues[4]
        val path = match.groupValues[5].trim('/')
        val base = "https://raw.githubusercontent.com/$owner/$repo/$branch"
        return when {
            kind == "blob" && path.isNotBlank() -> listOf("$base/$path")
            path.isBlank() -> listOf("$base/SKILL.md", "$base/skill.md")
            else -> listOf("$base/$path/SKILL.md", "$base/$path/skill.md")
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun sanitize(name: String): String =
        name.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_").take(64).ifBlank { "skill" }

    private class IOExceptionFriendly(message: String) : Exception(message)

    private val GITHUB_TREE_OR_BLOB = Regex(
            """https?://github\.com/([^/]+)/([^/]+)/(tree|blob)/([^/]+)(?:/(.*))?/?""",
            RegexOption.IGNORE_CASE,
        )
}
