package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.data.SkillsManager
import com.newoether.agora.data.SkillsMpClient
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Claude Code–style skills management for the model:
 * list / load / create / update / delete, plus skillsmp.com catalog search / install / audit.
 *
 * Users can say e.g. "find a skill for git commits and install it" and the model should
 * call [search_skillsmp] then [install_skill_from_skillsmp].
 */
class SkillsToolProvider(
    private val skillsManager: SkillsManager,
    private val settings: SettingsRepository,
) : ToolProvider {

    private val toolNames = setOf(
        "list_skills",
        "load_skill",
        "create_skill",
        "update_skill",
        "delete_skill",
        "search_skillsmp",
        "install_skill_from_skillsmp",
    )

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.skillsEnabled) return emptyList()
        return listOf(
            ToolDefinition(
                function = ToolFunction(
                    name = "list_skills",
                    description = "List available local skills (name + short preview). Call when specialized workflows may help, or before create/update/delete to avoid name clashes.",
                    parameters = ToolParameters(properties = emptyMap()),
                )
            ),
            ToolDefinition(
                function = ToolFunction(
                    name = "load_skill",
                    description = "Load the full markdown content of a local skill by exact name. Use after list_skills when a skill matches the task. Follow the skill instructions carefully.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "name" to ToolProperty("string", "Skill name to load."),
                        ),
                        required = listOf("name"),
                    ),
                )
            ),
            ToolDefinition(
                function = ToolFunction(
                    name = "create_skill",
                    description = "Create and save a new local skill as markdown. Use when the user asks you to create/add a skill, or when a reusable workflow should be stored. Name must be unique (case-insensitive).",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "name" to ToolProperty("string", "Unique skill name (human-readable)."),
                            "content" to ToolProperty(
                                "string",
                                "Full skill markdown body (instructions, steps, constraints). Prefer SKILL.md style.",
                            ),
                        ),
                        required = listOf("name", "content"),
                    ),
                )
            ),
            ToolDefinition(
                function = ToolFunction(
                    name = "update_skill",
                    description = "Replace the full markdown content of an existing local skill by name. Optionally rename with new_name.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "name" to ToolProperty("string", "Existing skill name."),
                            "content" to ToolProperty("string", "New full markdown content."),
                            "new_name" to ToolProperty("string", "Optional new name for the skill."),
                        ),
                        required = listOf("name", "content"),
                    ),
                )
            ),
            ToolDefinition(
                function = ToolFunction(
                    name = "delete_skill",
                    description = "Permanently delete a local skill by exact name. Only when the user clearly asks to remove it.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "name" to ToolProperty("string", "Skill name to delete."),
                        ),
                        required = listOf("name"),
                    ),
                )
            ),
            ToolDefinition(
                function = ToolFunction(
                    name = "search_skillsmp",
                    description = "Search the SkillsMP catalog for installable agent skills. Use when the user asks to find, browse, or discover skills online. Returns the SkillsMP id, author, GitHub source URL, description, and stars.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "query" to ToolProperty(
                                "string",
                                "Search query (min 2 chars). e.g. \"git commit\", \"react\", \"code review\".",
                            ),
                            "limit" to ToolProperty(
                                "integer",
                                "Max results 1-50. Default 15.",
                            ),
                        ),
                        required = listOf("query"),
                    ),
                )
            ),
            ToolDefinition(
                function = ToolFunction(
                    name = "install_skill_from_skillsmp",
                    description = "Download the SKILL.md for a result from SkillsMP's GitHub source and save it to the local skill library. Pass the id returned by search_skillsmp. Re-installing the same catalog id updates the existing entry.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "id" to ToolProperty(
                                "string",
                                "SkillsMP catalog id or skill name from search_skillsmp.",
                            ),
                            "name" to ToolProperty(
                                "string",
                                "Optional local display name override.",
                            ),
                        ),
                        required = listOf("id"),
                    ),
                )
            ),
        )
    }

    override fun handles(name: String): Boolean = name in toolNames

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (!ctx.skillsEnabled) {
            return """{"error":"skills_disabled"}"""
        }
        SkillsMpClient.apiToken = settings.skillsApiToken.first()

        val args = parseArgs(arguments)
        return when (name) {
            "list_skills" -> listSkills()
            "load_skill" -> loadSkill(stringArg(args, "name"))
            "create_skill" -> createSkill(stringArg(args, "name"), stringArg(args, "content"))
            "update_skill" -> updateSkill(
                stringArg(args, "name"),
                stringArg(args, "content"),
                stringArg(args, "new_name").ifBlank { null },
            )
            "delete_skill" -> deleteSkill(stringArg(args, "name"))
            "search_skillsmp" -> searchSkillsMp(
                stringArg(args, "query"),
                intArg(args, "limit", 15),
            )
            "install_skill_from_skillsmp" -> installSkillFromSkillsMp(
                stringArg(args, "id").ifBlank { stringArg(args, "name") },
                stringArg(args, "name").ifBlank { null },
            )
            else -> """{"error":"unknown_tool"}"""
        }
    }

    private fun listSkills(): String {
        val skills = skillsManager.skills.value
        return buildJsonObject {
            put("count", skills.size)
            put(
                "skills",
                buildJsonArray {
                    skills.forEach { s ->
                        add(
                            buildJsonObject {
                                put("name", s.name)
                                put("preview", s.content.lineSequence().take(3).joinToString(" ").take(160))
                                put("updated_at", s.updatedAt)
                                s.catalogId?.let { put("catalog_id", it) }
                                s.source?.let { put("source", it) }
                                s.pageUrl?.let { put("page_url", it) }
                            }
                        )
                    }
                }
            )
        }.toString()
    }

    private fun loadSkill(skillName: String): String {
        if (skillName.isEmpty()) return """{"error":"missing_name"}"""
        val skill = skillsManager.getByName(skillName)
            ?: return """{"error":"not_found","name":"$skillName"}"""
        return buildJsonObject {
            put("name", skill.name)
            put("content", skill.content)
            skill.catalogId?.let { put("catalog_id", it) }
            skill.source?.let { put("source", it) }
            skill.pageUrl?.let { put("page_url", it) }
        }.toString()
    }

    private fun createSkill(skillName: String, content: String): String {
        if (skillName.isBlank()) return """{"error":"missing_name"}"""
        if (content.isBlank()) return """{"error":"missing_content"}"""
        if (skillsManager.getByName(skillName) != null) {
            return """{"error":"already_exists","name":"$skillName","hint":"Use update_skill to overwrite, or choose another name."}"""
        }
        val entry = skillsManager.importMarkdown(
            name = skillName.trim(),
            content = content,
            sourceFileName = "${sanitizeFileBase(skillName)}.md",
        )
        return buildJsonObject {
            put("status", "ok")
            put("action", "created")
            put("name", entry.name)
            put("id", entry.id)
            put("bytes", entry.content.length)
        }.toString()
    }

    private fun updateSkill(skillName: String, content: String, newName: String?): String {
        if (skillName.isBlank()) return """{"error":"missing_name"}"""
        if (content.isBlank()) return """{"error":"missing_content"}"""
        val existing = skillsManager.getByName(skillName)
            ?: return """{"error":"not_found","name":"$skillName"}"""
        val targetName = newName?.trim()?.takeIf { it.isNotEmpty() } ?: existing.name
        if (!targetName.equals(existing.name, ignoreCase = true) &&
            skillsManager.getByName(targetName) != null
        ) {
            return """{"error":"name_conflict","name":"$targetName"}"""
        }
        val updated = skillsManager.updateContent(existing.id, content, targetName)
            ?: return """{"error":"update_failed"}"""
        return buildJsonObject {
            put("status", "ok")
            put("action", "updated")
            put("name", updated.name)
            put("id", updated.id)
            put("bytes", updated.content.length)
        }.toString()
    }

    private fun deleteSkill(skillName: String): String {
        if (skillName.isBlank()) return """{"error":"missing_name"}"""
        val existing = skillsManager.getByName(skillName)
            ?: return """{"error":"not_found","name":"$skillName"}"""
        skillsManager.delete(existing.id)
        return buildJsonObject {
            put("status", "ok")
            put("action", "deleted")
            put("name", existing.name)
            put("id", existing.id)
        }.toString()
    }

    private suspend fun searchSkillsMp(query: String, limit: Int): String {
        if (query.isBlank()) return """{"error":"missing_query"}"""
        val result = SkillsMpClient.search(query, limit.coerceIn(1, 50))
        return result.fold(
            onSuccess = { sr ->
                buildJsonObject {
                    put("status", "ok")
                    put("query", sr.query)
                    put("count", sr.skills.size)
                    put("duration_ms", sr.durationMs)
                    put(
                        "skills",
                        buildJsonArray {
                            sr.skills.forEach { s ->
                                add(
                                    buildJsonObject {
                                        put("id", s.id)
                                        put("name", s.name)
                                        put("author", s.author)
                                        put("description", s.description)
                                        put("github_url", s.githubUrl)
                                        put("stars", s.stars)
                                        put("url", s.url)
                                        put("installed", skillsManager.isInstalledFromCatalog(s.id))
                                    }
                                )
                            }
                        }
                    )
                    put(
                        "hint",
                        "To install, call install_skill_from_skillsmp with id=<SkillsMP id>.",
                    )
                }.toString()
            },
            onFailure = { e ->
                buildJsonObject {
                    put("error", "search_failed")
                    put("message", e.message ?: "unknown")
                }.toString()
            },
        )
    }

    private suspend fun installSkillFromSkillsMp(ref: String, nameOverride: String?): String {
        if (ref.isBlank()) return """{"error":"missing_id"}"""
        val installed = SkillsMpClient.install(ref)
        return installed.fold(
            onSuccess = { content ->
                val localName = nameOverride?.trim()?.takeIf { it.isNotEmpty() } ?: content.name
                // Name clash with a different skill: append source hint
                val finalName = when {
                    skillsManager.getByName(localName) == null -> localName
                    skillsManager.getByCatalogId(content.catalogId) != null -> localName
                    else -> "$localName (${content.source})"
                }
                val entry = skillsManager.importMarkdown(
                    name = finalName,
                    content = content.content,
                    sourceFileName = content.sourceFileName,
                    catalogId = content.catalogId,
                    source = content.source,
                    pageUrl = content.pageUrl,
                )
                buildJsonObject {
                    put("status", "ok")
                    put("action", "installed")
                    put("name", entry.name)
                    put("id", entry.id)
                    put("catalog_id", content.catalogId)
                    put("source", content.source)
                    put("page_url", content.pageUrl)
                    put("fetched_from", content.fetchedFrom)
                    put("bytes", entry.content.length)
                    put("hint", "Call load_skill with name=\"${entry.name}\" to use it.")
                }.toString()
            },
            onFailure = { e ->
                buildJsonObject {
                    put("error", "install_failed")
                    put("id", ref)
                    put("message", e.message ?: "unknown")
                }.toString()
            },
        )
    }

    private fun parseArgs(arguments: String): Map<String, kotlinx.serialization.json.JsonElement> {
        return try {
            Json.decodeFromString(arguments.ifBlank { "{}" })
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun stringArg(args: Map<String, kotlinx.serialization.json.JsonElement>, key: String): String =
        (args[key] as? JsonPrimitive)?.content?.trim().orEmpty()

    private fun intArg(
        args: Map<String, kotlinx.serialization.json.JsonElement>,
        key: String,
        default: Int,
    ): Int {
        val p = args[key] as? JsonPrimitive ?: return default
        return p.content.toIntOrNull() ?: default
    }

    private fun sanitizeFileBase(name: String): String =
        name.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_").take(64).ifBlank { "skill" }
}
