package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class MemoryToolProvider(
    private val memoryManager: MemoryManager
) : ToolProvider {

    /**
     * One dispatching tool instead of six.
     *
     * Six sibling definitions repeated the same JSON scaffolding — `type`/`function`/`parameters`/
     * `properties`/`required` plus overlapping `name`, `content`, `old_string`, `new_string`
     * parameters — six times on every single request, for ~1,170 tokens of schema the model rarely
     * needed. Collapsing them behind an `action` selector keeps every operation reachable at about
     * a third of the size. Legacy names still execute (see [execute]) so historical conversations
     * that recorded them replay unchanged.
     *
     * The two access flags stay independent: they decide which actions are advertised, so turning
     * off saved memories genuinely removes file access rather than merely hiding it.
     */
    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.accessSavedMemories && !ctx.accessActiveMemory) return emptyList()

        val actions = buildList {
            if (ctx.accessSavedMemories) {
                add("list — every memory file with its description; no other parameters")
                add("read — file contents; 'name' for one file, or 'names' for several at once")
                add("create — new file; needs 'name' and 'content', optional 'description'")
                add(
                    "edit — change a file; needs 'name', then EITHER 'content' (full rewrite) OR " +
                        "'old_string' + 'new_string' (exact replace, old_string must match once); " +
                        "optional 'new_name' to rename and 'description' to retitle"
                )
                add("delete — remove a file; needs 'name'")
            }
            if (ctx.accessActiveMemory) {
                add(
                    "update_active — rewrite the always-loaded active memory; needs 'content', " +
                        "optional 'mode' (replace | append | prepend | patch, default replace); " +
                        "patch mode needs 'old_string' + 'new_string' instead"
                )
            }
        }

        val properties = buildMap {
            put("action", ToolProperty("string", "One of: ${actions.joinToString(", ") { it.substringBefore(" —") }}."))
            if (ctx.accessSavedMemories) {
                put("name", ToolProperty("string", "File name, e.g. 'notes.md'."))
                put(
                    "names",
                    ToolProperty("array", "Several file names, for action=read.", items = ToolProperty("string", "A file name."))
                )
                put("new_name", ToolProperty("string", "New file name, for action=edit."))
                put("description", ToolProperty("string", "Short description of the file. Empty string removes it."))
            }
            put("content", ToolProperty("string", "File or active-memory content."))
            put("old_string", ToolProperty("string", "Exact text to replace. Must match exactly once."))
            put("new_string", ToolProperty("string", "Replacement for old_string. Empty string deletes the match."))
            if (ctx.accessActiveMemory) {
                put("mode", ToolProperty("string", "replace | append | prepend | patch, for action=update_active."))
            }
        }

        return listOf(
            ToolDefinition(
                function = ToolFunction(
                    name = "memory",
                    description = "Read and write the user's persistent memory. Choose an operation with 'action':\n" +
                        actions.joinToString("\n") { "- $it" },
                    parameters = ToolParameters(properties = properties, required = listOf("action")),
                )
            )
        )
    }

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        val argsStr = arguments.ifBlank { "{}" }
        val args =
            Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(argsStr)
        fun arg(key: String): String =
            (args[key] as? JsonPrimitive)?.content ?: ""

        // The merged `memory` entry point maps onto the legacy operation names, which stay live
        // below so tool calls recorded in existing conversations still execute.
        val op = if (name == "memory") {
            when (val action = arg("action").trim().lowercase()) {
                "list", "list_memory_files" -> "list_memory_files"
                "read", "read_memory_file" -> "read_memory_file"
                "create", "create_memory_file" -> "create_memory_file"
                "edit", "edit_memory_file" -> "edit_memory_file"
                "delete", "delete_memory_file" -> "delete_memory_file"
                "update_active", "update_active_memory" -> "update_active_memory"
                "" -> return "Error: 'action' is required. Use list, read, create, edit, delete, or update_active."
                else -> return "Error: unknown action '$action'. Use list, read, create, edit, delete, or update_active."
            }
        } else {
            name
        }

        return when (op) {
            "list_memory_files" -> {
                val files = memoryManager.listFiles()
                if (files.isEmpty()) {
                    buildJsonObject {
                        put("type", "list_memory_files")
                        putJsonArray("files") {}
                    }.toString()
                } else {
                    buildJsonObject {
                        put("type", "list_memory_files")
                        putJsonArray("files") {
                            files.forEach { f ->
                                add(
                                    buildJsonObject {
                                        put("name", f.name)
                                        put("description", f.description)
                                    }
                                )
                            }
                        }
                    }.toString()
                }
            }

            "read_memory_file" -> {
                val singleName = arg("name")
                val namesArray = args["names"] as? JsonArray
                if (namesArray != null && namesArray.isNotEmpty()) {
                    val names = namesArray.map {
                        (it as? JsonPrimitive)?.content ?: ""
                    }.filter { it.isNotEmpty() }
                    names.joinToString("\n\n") { name ->
                        "--- $name ---\n${memoryManager.readFile(name)}"
                    }
                } else if (singleName.isNotEmpty()) {
                    memoryManager.readFile(singleName)
                } else {
                    "Error: No file name provided. Use 'name' for a single file or 'names' for multiple files."
                }
            }

            "create_memory_file" -> memoryManager.createFile(
                arg("name"),
                arg("content"),
                arg("description")
            )

            "edit_memory_file" -> {
                val editContent = arg("content").ifBlank { null }
                val oldStr = arg("old_string").ifBlank { null }
                val newStr = arg("new_string")
                val newName = arg("new_name").ifBlank { null }
                val descArg = arg("description")
                val desc = if (args.containsKey("description")) descArg else null
                if (editContent != null && oldStr != null) {
                    "Error: 'content' and 'old_string' are mutually exclusive. Use one or the other."
                } else if (oldStr != null && !args.containsKey("new_string")) {
                    "Error: 'old_string' requires 'new_string' (pass empty string to delete)."
                } else if (editContent == null && oldStr == null && newName == null && desc == null) {
                    "Error: At least 'content', 'old_string', 'new_name', or 'description' must be provided."
                } else {
                    memoryManager.editFile(
                        arg("name"),
                        editContent,
                        newName,
                        desc,
                        oldStr,
                        newStr
                    )
                }
            }

            "delete_memory_file" -> memoryManager.deleteFile(arg("name"))

            "update_active_memory" -> {
                val mode = arg("mode").ifBlank { "replace" }
                val oldStr = arg("old_string").ifBlank { null }
                val newStr = arg("new_string").ifBlank { null }
                if (mode == "patch" && oldStr == null) {
                    "Error: 'old_string' is required for patch mode."
                } else {
                    memoryManager.updateActiveMemory(arg("content"), mode, oldStr, newStr)
                }
            }

            else -> "Unknown tool: $name"
        }
    }

    override fun handles(name: String): Boolean = name in setOf(
        "memory",
        "list_memory_files",
        "read_memory_file",
        "create_memory_file",
        "edit_memory_file",
        "delete_memory_file",
        "update_active_memory"
    )
}
