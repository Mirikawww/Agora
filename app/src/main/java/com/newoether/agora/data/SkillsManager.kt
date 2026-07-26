package com.newoether.agora.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

data class SkillEntry(
    val id: String,
    val name: String,
    val fileName: String,
    val content: String,
    val updatedAt: Long,
    /** SkillsMP catalog id */
    val catalogId: String? = null,
    /** GitHub source owner/repo or well-known host */
    val source: String? = null,
    /** Public SkillsMP page URL */
    val pageUrl: String? = null,
)

/**
 * Claude Code–style Skills: markdown skill files the model can invoke by name.
 * Stored under filesDir/skills/<id>.md with a sidecar name in skills_index.json.
 */
class SkillsManager(private val appContext: Context) {
    private val dir: File = File(appContext.filesDir, "skills").also { it.mkdirs() }
    private val indexFile = File(dir, "index.json")

    private val _skills = MutableStateFlow(loadAll())
    val skills: StateFlow<List<SkillEntry>> = _skills.asStateFlow()

    fun refresh() {
        _skills.value = loadAll()
    }

    fun get(id: String): SkillEntry? = _skills.value.find { it.id == id }

    fun getByName(name: String): SkillEntry? =
        _skills.value.find { it.name.equals(name, ignoreCase = true) }

    fun getByCatalogId(catalogId: String): SkillEntry? =
        _skills.value.find { it.catalogId?.equals(catalogId, ignoreCase = true) == true }

    fun isInstalledFromCatalog(catalogId: String): Boolean =
        getByCatalogId(catalogId) != null

    fun importMarkdown(
        name: String,
        content: String,
        sourceFileName: String = "skill.md",
        catalogId: String? = null,
        source: String? = null,
        pageUrl: String? = null,
    ): SkillEntry {
        // Reinstall from same catalog id: replace existing instead of duplicating.
        val existingByCatalog = catalogId?.let { getByCatalogId(it) }
        if (existingByCatalog != null) {
            return updateContent(
                id = existingByCatalog.id,
                content = content,
                newName = name.trim().ifBlank { existingByCatalog.name },
                catalogId = catalogId,
                source = source ?: existingByCatalog.source,
                pageUrl = pageUrl ?: existingByCatalog.pageUrl,
                fileName = sourceFileName,
            ) ?: existingByCatalog
        }
        val id = UUID.randomUUID().toString()
        val safeName = name.trim().ifBlank {
            sourceFileName.removeSuffix(".md").removeSuffix(".MD").ifBlank { "skill" }
        }
        val file = File(dir, "$id.md")
        file.writeText(content)
        val entry = SkillEntry(
            id = id,
            name = safeName,
            fileName = sourceFileName,
            content = content,
            updatedAt = System.currentTimeMillis(),
            catalogId = catalogId,
            source = source,
            pageUrl = pageUrl,
        )
        val next = _skills.value + entry
        persistIndex(next)
        _skills.value = next
        return entry
    }

    /**
     * Replace content (and optionally rename) an existing skill.
     * Returns the updated entry, or null if [id] is unknown.
     */
    fun updateContent(
        id: String,
        content: String,
        newName: String? = null,
        catalogId: String? = null,
        source: String? = null,
        pageUrl: String? = null,
        fileName: String? = null,
    ): SkillEntry? {
        val current = _skills.value.find { it.id == id } ?: return null
        val file = File(dir, "$id.md")
        file.writeText(content)
        val entry = current.copy(
            name = newName?.trim()?.takeIf { it.isNotEmpty() } ?: current.name,
            content = content,
            updatedAt = System.currentTimeMillis(),
            catalogId = catalogId ?: current.catalogId,
            source = source ?: current.source,
            pageUrl = pageUrl ?: current.pageUrl,
            fileName = fileName ?: current.fileName,
        )
        val next = _skills.value.map { if (it.id == id) entry else it }
        persistIndex(next)
        _skills.value = next
        return entry
    }

    fun delete(id: String) {
        File(dir, "$id.md").delete()
        val next = _skills.value.filterNot { it.id == id }
        persistIndex(next)
        _skills.value = next
    }

    fun rename(id: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        val next = _skills.value.map {
            if (it.id == id) it.copy(name = trimmed, updatedAt = System.currentTimeMillis()) else it
        }
        persistIndex(next)
        _skills.value = next
    }

    private fun loadAll(): List<SkillEntry> {
        val index = readIndex()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".md") }?.toList().orEmpty()
        return files.mapNotNull { file ->
            val id = file.nameWithoutExtension
            val meta = index[id]
            val name = meta?.get("name") ?: id
            val sourceName = meta?.get("fileName") ?: file.name
            SkillEntry(
                id = id,
                name = name,
                fileName = sourceName,
                content = file.readText(),
                updatedAt = file.lastModified(),
                catalogId = meta?.get("catalogId")?.takeIf { it.isNotBlank() },
                source = meta?.get("source")?.takeIf { it.isNotBlank() },
                pageUrl = meta?.get("pageUrl")?.takeIf { it.isNotBlank() },
            )
        }.sortedBy { it.name.lowercase() }
    }

    private fun readIndex(): Map<String, Map<String, String>> {
        if (!indexFile.exists()) return emptyMap()
        return try {
            val root = org.json.JSONObject(indexFile.readText())
            val out = mutableMapOf<String, Map<String, String>>()
            for (key in root.keys()) {
                val obj = root.optJSONObject(key) ?: continue
                out[key] = mapOf(
                    "name" to obj.optString("name", key),
                    "fileName" to obj.optString("fileName", "$key.md"),
                    "catalogId" to obj.optString("catalogId", ""),
                    "source" to obj.optString("source", ""),
                    "pageUrl" to obj.optString("pageUrl", ""),
                )
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun persistIndex(entries: List<SkillEntry>) {
        val root = org.json.JSONObject()
        entries.forEach { e ->
            root.put(
                e.id,
                org.json.JSONObject()
                    .put("name", e.name)
                    .put("fileName", e.fileName)
                    .put("catalogId", e.catalogId.orEmpty())
                    .put("source", e.source.orEmpty())
                    .put("pageUrl", e.pageUrl.orEmpty()),
            )
        }
        indexFile.writeText(root.toString())
    }
}
