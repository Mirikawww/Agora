package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.util.ToolResultPayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Capability-broker calls keep their wire name for provider history, but the UI presents the
 * concrete tool selected inside the broker arguments. This preserves connector icons and labels
 * without corrupting the tool_call/tool_result name pair sent back to the model.
 */
internal fun MessageSegment.effectiveToolName(): String? {
    if (toolName != "agora_capabilities" && toolName != "mcp_tool_invoke") return toolName
    val args = try {
        Json.parseToJsonElement(toolArgs ?: "{}").jsonObject
    } catch (_: Exception) {
        return toolName
    }
    if (toolName == "agora_capabilities" &&
        (args["action"] as? JsonPrimitive)?.content?.lowercase() != "invoke"
    ) {
        return toolName
    }
    return (args["name"] as? JsonPrimitive)?.content ?: toolName
}

/** Drawable for built-in connector tools; null keeps the default wrench. */
internal fun toolConnectorIconRes(toolName: String?): Int? {
    val raw = toolName.orEmpty()
    return when {
        raw == "github_request" || raw.startsWith("github_") -> R.drawable.ic_github_mark
        raw.startsWith("todoist_") -> R.drawable.ic_todoist_color
        raw.startsWith("notion_") -> R.drawable.ic_notion_mark
        else -> null
    }
}


@Composable
internal fun ToolTypeIcon(
    toolName: String?,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
) {
    val tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
    val res = toolConnectorIconRes(toolName)
    val preservesBrandColors = toolName.orEmpty().startsWith("todoist_")
    if (res != null) {
        Icon(
            painter = painterResource(res),
            contentDescription = null,
            modifier = modifier.size(size),
            tint = if (preservesBrandColors) Color.Unspecified else tint,
        )
    } else {
        Icon(
            Icons.Default.Build,
            contentDescription = null,
            modifier = modifier.size(size),
            tint = tint,
        )
    }
}

@Composable
internal fun toolDisplayName(toolName: String?): String {
    return when (toolName) {
        // The merged dispatching tools. The specific operation shows up in the summary line
        // below, which has access to the `action` argument; the label stays generic.
        "memory" -> stringResource(R.string.tool_memory)
        "skills" -> stringResource(R.string.tool_skills)
        "list_memory_files" -> stringResource(R.string.tool_look_up_memories)
        "read_memory_file" -> stringResource(R.string.tool_read_memory)
        "create_memory_file" -> stringResource(R.string.tool_add_memory)
        "edit_memory_file" -> stringResource(R.string.tool_edit_memory)
        "delete_memory_file" -> stringResource(R.string.tool_delete_memory)
        "update_active_memory" -> stringResource(R.string.tool_update_active_memory)
        "web_search" -> stringResource(R.string.tool_web_search)
        "web_fetch" -> stringResource(R.string.tool_web_fetch)
        "search_conversations" -> stringResource(R.string.tool_search_conversations)
        "list_shells" -> stringResource(R.string.tool_list_shells)
        "execute_shell_command" -> stringResource(R.string.tool_execute_shell)
        "list_conversations" -> stringResource(R.string.tool_list_conversations)
        "read_conversation" -> stringResource(R.string.tool_read_conversation)
        "file_read" -> stringResource(R.string.tool_file_read)
        "file_write" -> stringResource(R.string.tool_file_write)
        "file_edit" -> stringResource(R.string.tool_file_edit)
        "file_glob" -> stringResource(R.string.tool_file_glob)
        "file_grep" -> stringResource(R.string.tool_file_grep)
        "generate_image" -> stringResource(R.string.tool_generate_image)
        "ask_user" -> stringResource(R.string.tool_ask_user)
        "get_provider_balance" -> stringResource(R.string.tool_get_provider_balance)
        "get_user_profile" -> stringResource(R.string.tool_get_user_profile)
        "update_user_profile" -> stringResource(R.string.tool_update_user_profile)
        "list_skills" -> stringResource(R.string.tool_list_skills)
        "load_skill" -> stringResource(R.string.tool_load_skill)
        "create_skill" -> stringResource(R.string.tool_create_skill)
        "update_skill" -> stringResource(R.string.tool_update_skill)
        "delete_skill" -> stringResource(R.string.tool_delete_skill)
        "search_skillsmp" -> stringResource(R.string.tool_search_skillsmp)
        "install_skill_from_skillsmp" -> stringResource(R.string.tool_install_skill_from_skillsmp)
        "github_request" -> stringResource(R.string.tool_github_request_display)
        else -> {
            val raw = toolName ?: stringResource(R.string.tool_context)
            // Built-in connector tools: todoist_add_tasks → "Todoist · Add Tasks"
            val connectorLabel = when {
                raw.startsWith("todoist_") -> stringResource(R.string.connector_todoist) to "todoist_"
                raw.startsWith("notion_") -> stringResource(R.string.connector_notion) to "notion_"
                else -> null
            }
            if (connectorLabel != null) {
                val (label, prefix) = connectorLabel
                val action = raw.removePrefix(prefix)
                    .split("_")
                    .filter { it.isNotBlank() }
                    .joinToString(" ") { part -> part.replaceFirstChar { c -> c.uppercaseChar() } }
                listOf(label, action)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
            } else {
                raw.split("_").joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }
            }
        }
    }
}

@Composable
internal fun toolSummary(seg: MessageSegment): String {
    val rawName = seg.effectiveToolName() ?: ""
    val outerArgs = try {
        Json.parseToJsonElement(seg.toolArgs ?: "{}").jsonObject
    } catch (_: Exception) {
        null
    }
    val argsJson = if (rawName != seg.toolName) {
        when (val nested = outerArgs?.get("arguments")) {
            is kotlinx.serialization.json.JsonObject -> nested
            is JsonPrimitive -> try {
                Json.parseToJsonElement(nested.content).jsonObject
            } catch (_: Exception) {
                outerArgs
            }
            else -> outerArgs
        }
    } else {
        outerArgs
    }
    // `memory` and `skills` dispatch on an `action` argument. Map it back to the per-operation
    // name every branch below is written against, so merging those tools did not silently
    // downgrade every memory/skill row to the generic fallback label.
    val name = when (rawName) {
        "memory", "skills" -> when ((argsJson?.get("action") as? JsonPrimitive)?.content?.lowercase()) {
            "list" -> if (rawName == "memory") "list_memory_files" else "list_skills"
            "read" -> "read_memory_file"
            "create" -> if (rawName == "memory") "create_memory_file" else "create_skill"
            "edit" -> "edit_memory_file"
            "delete" -> if (rawName == "memory") "delete_memory_file" else "delete_skill"
            "update_active" -> "update_active_memory"
            "load" -> "load_skill"
            "update" -> "update_skill"
            "search" -> "search_skillsmp"
            "install" -> "install_skill_from_skillsmp"
            else -> rawName
        }
        else -> rawName
    }
    val fileName = argsJson?.get("name")?.let { (it as? JsonPrimitive)?.content }
        ?: argsJson?.get("names")?.let { names ->
            val arr = names as? kotlinx.serialization.json.JsonArray
            if (arr != null && arr.size == 1) (arr[0] as? JsonPrimitive)?.content else null
        }
    val nameCount = argsJson?.get("names")?.let { (it as? kotlinx.serialization.json.JsonArray)?.size }
    val content = seg.toolResult ?: ""
    // Many tools return JSON error payloads ({"error":...}), not plain "Error..." text.
    val isError = content.startsWith("Error") || toolResultLooksLikeError(content)
    return when (name) {
        "read_memory_file" -> when {
            isError -> stringResource(R.string.tool_read_memory_failed)
            content.isBlank() && fileName != null -> stringResource(R.string.tool_reading_memory, fileName)
            nameCount != null && nameCount > 1 -> stringResource(R.string.tool_read_memory_count, nameCount)
            fileName != null -> stringResource(R.string.tool_read_memory_name, fileName)
            else -> stringResource(R.string.tool_read_memory_success)
        }
        "create_memory_file" -> when {
            isError -> stringResource(R.string.tool_save_memory_failed)
            content.isBlank() && fileName != null -> stringResource(R.string.tool_saving_memory, fileName)
            fileName != null -> stringResource(R.string.tool_save_memory_name, fileName)
            else -> stringResource(R.string.tool_save_memory_default)
        }
        "edit_memory_file" -> when {
            isError -> stringResource(R.string.tool_edit_memory_failed)
            content.isBlank() && fileName != null -> stringResource(R.string.tool_updating_memory, fileName)
            fileName != null -> stringResource(R.string.tool_edit_memory_name, fileName)
            else -> stringResource(R.string.tool_edit_memory_default)
        }
        "delete_memory_file" -> when {
            isError -> stringResource(R.string.tool_delete_memory_failed)
            content.isBlank() && fileName != null -> stringResource(R.string.tool_removing_memory, fileName)
            fileName != null -> stringResource(R.string.tool_delete_memory_name, fileName)
            else -> stringResource(R.string.tool_delete_memory_default)
        }
        "list_memory_files" -> {
            if (isError) stringResource(R.string.tool_lookup_failed)
            else if (content.isBlank()) stringResource(R.string.tool_looking_up_memories)
            else {
                // Already neutral when the count is unknown, but the accounting stays in one place.
                val fileCount = ToolResultPayload.countArray(content, "files")
                if (fileCount is ToolResultPayload.Count.Known && fileCount.size > 0) {
                    stringResource(R.string.tool_lookup_count, fileCount.size)
                } else {
                    stringResource(R.string.tool_lookup_default)
                }
            }
        }
        "update_active_memory" -> {
            if (isError) stringResource(R.string.tool_update_active_failed)
            else if (content.isBlank()) stringResource(R.string.tool_updating_active)
            else stringResource(R.string.tool_update_active_default)
        }
        "web_search" -> {
            val query = argsJson?.get("query")?.let { (it as? JsonPrimitive)?.content }
                ?: ToolResultPayload.stringField(content, "query")
            if (isError) {
                if (query != null) stringResource(R.string.tool_web_search_error, query)
                else stringResource(R.string.tool_search_failed)
            } else if (content.isBlank()) {
                if (query != null) stringResource(R.string.tool_searching_web, query)
                else stringResource(R.string.tool_web_search_done_default)
            } else {
                // A large successful result is stored already clipped by ResultBudget, which
                // replaces the payload with a head/tail envelope. Its `results` array is gone, so
                // counting it yielded 0 and a 40-result search rendered as "no results found".
                when (val count = ToolResultPayload.countArray(content, "results")) {
                    is ToolResultPayload.Count.Known ->
                        if (count.size > 0 && query != null) {
                            stringResource(R.string.tool_web_search_done, count.size, query)
                        } else if (query != null) {
                            stringResource(R.string.tool_web_search_no_result, query)
                        } else {
                            stringResource(R.string.tool_web_search_done_default)
                        }
                    // The search succeeded but its size is unrecoverable: report completion
                    // without asserting a count in either direction.
                    ToolResultPayload.Count.Unknown ->
                        if (query != null) stringResource(R.string.tool_web_search_done_query, query)
                        else stringResource(R.string.tool_web_search_done_default)
                }
            }
        }
        "web_fetch" -> {
            val url = argsJson?.get("url")?.let { (it as? JsonPrimitive)?.content }
            if (isError) stringResource(R.string.tool_web_fetch_failed)
            else if (content.isEmpty()) stringResource(R.string.tool_web_fetching, url?.take(60)?.ifEmpty { "page" } ?: "web page")
            else if (url != null) stringResource(R.string.tool_web_fetch_done, url.take(60).ifEmpty { "page" })
            else stringResource(R.string.tool_web_fetch_default)
        }
        "search_conversations" -> {
            val query = argsJson?.get("query")?.let { (it as? JsonPrimitive)?.content }
            if (isError) {
                if (query != null) stringResource(R.string.tool_conversation_search_error, query)
                else stringResource(R.string.tool_search_failed)
            } else if (content.isBlank()) {
                if (query != null) stringResource(R.string.tool_searching_for, query)
                else stringResource(R.string.tool_searching_conversations_default)
            } else {
                when (val count = ToolResultPayload.countArray(content, "results")) {
                    is ToolResultPayload.Count.Known ->
                        if (count.size > 0 && query != null) {
                            stringResource(R.string.tool_conversation_search_done_for, count.size, query)
                        } else if (query != null) {
                            stringResource(R.string.tool_conversation_search_no_result, query)
                        } else {
                            stringResource(R.string.tool_searching_conversations_default)
                        }
                    ToolResultPayload.Count.Unknown ->
                        if (query != null) {
                            stringResource(R.string.tool_conversation_search_done_query, query)
                        } else {
                            stringResource(R.string.tool_searching_conversations_default)
                        }
                }
            }
        }
        "list_shells" -> {
            if (isError) stringResource(R.string.tool_shell_listing)
            else if (content.isBlank()) stringResource(R.string.tool_listing_shells)
            else {
                val deviceCount = ToolResultPayload.countArray(content, "devices")
                if (deviceCount is ToolResultPayload.Count.Known && deviceCount.size > 0) {
                    stringResource(R.string.tool_shell_list_count, deviceCount.size)
                } else {
                    stringResource(R.string.tool_shell_list_done)
                }
            }
        }
        "execute_shell_command" -> {
            val command = argsJson?.get("command")?.let { (it as? JsonPrimitive)?.content }
            if (isError) stringResource(R.string.tool_shell_failed)
            else if (content.isBlank()) {
                if (command != null) stringResource(R.string.tool_executing_shell, command.take(80))
                else stringResource(R.string.tool_listing_shells)
            } else {
                if (command != null) stringResource(R.string.tool_executed_shell, command.take(80), content.length)
                else stringResource(R.string.tool_executed_no_output, command?.take(80) ?: "shell")
            }
        }
        "list_conversations" -> {
            if (isError) stringResource(R.string.tool_call_failed)
            else if (content.isBlank()) stringResource(R.string.tool_listing_conversations)
            else {
                val total = try {
                    (Json.parseToJsonElement(content).jsonObject["total"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                } catch (_: Exception) { 0 }
                if (total > 0) stringResource(R.string.tool_listed_conversations, total)
                else stringResource(R.string.tool_listed_no_conversations)
            }
        }
        "read_conversation" -> {
            if (isError) stringResource(R.string.tool_call_failed)
            else if (content.isBlank()) stringResource(R.string.tool_reading_conversation)
            else {
                val title = try {
                    (Json.parseToJsonElement(content).jsonObject["title"] as? JsonPrimitive)?.content
                } catch (_: Exception) { null }
                if (title != null) stringResource(R.string.tool_read_conversation_done, title.take(60))
                else stringResource(R.string.tool_read_conversation)
            }
        }
        "file_read" -> {
            val path = argsJson?.get("path")?.let { (it as? JsonPrimitive)?.content }
            if (isError) stringResource(R.string.tool_read_file_failed)
            else if (content.isBlank()) stringResource(R.string.tool_reading_file, path?.take(60) ?: "file")
            else stringResource(R.string.tool_read_file_done, path?.take(60) ?: "file")
        }
        "file_write" -> {
            val path = argsJson?.get("path")?.let { (it as? JsonPrimitive)?.content }
            if (isError) stringResource(R.string.tool_call_failed)
            else if (content.isBlank()) stringResource(R.string.tool_writing_file, path?.take(60) ?: "file")
            else stringResource(R.string.tool_wrote_file, path?.take(60) ?: "file")
        }
        "file_edit" -> {
            val path = argsJson?.get("path")?.let { (it as? JsonPrimitive)?.content }
            if (isError) stringResource(R.string.tool_call_failed)
            else if (content.isBlank()) stringResource(R.string.tool_editing_file, path?.take(60) ?: "file")
            else stringResource(R.string.tool_edited_file, path?.take(60) ?: "file")
        }
        "file_glob" -> {
            val pattern = argsJson?.get("pattern")?.let { (it as? JsonPrimitive)?.content }
            if (isError) stringResource(R.string.tool_call_failed)
            else if (content.isBlank()) stringResource(R.string.tool_finding_files, pattern?.take(60) ?: "files")
            else {
                val count = content.lines().filter { it.isNotBlank() }.size
                if (count > 0) stringResource(R.string.tool_found_files, count)
                else stringResource(R.string.tool_found_no_files)
            }
        }
        "file_grep" -> {
            val pattern = argsJson?.get("pattern")?.let { (it as? JsonPrimitive)?.content }
            if (isError) stringResource(R.string.tool_call_failed)
            else if (content.isBlank()) stringResource(R.string.tool_searching_file, pattern?.take(60) ?: "file")
            else {
                val matches = content.lines().filter { it.isNotBlank() }.size
                if (matches > 0) stringResource(R.string.tool_searched_file, matches)
                else stringResource(R.string.tool_found_no_files)
            }
        }
        "generate_image" -> {
            val resultObj = try {
                Json.parseToJsonElement(content).jsonObject
            } catch (_: Exception) {
                null
            }
            val statusOk = (resultObj?.get("status") as? JsonPrimitive)?.content == "ok"
            when {
                isError || (resultObj != null && !statusOk && content.isNotBlank()) ->
                    stringResource(R.string.tool_call_failed)
                content.isEmpty() -> stringResource(R.string.tool_generating_image)
                else -> stringResource(R.string.tool_generated_image)
            }
        }
        "ask_user" -> {
            // Payload: {"status":"answered","answers":[…]} — echo the user's own words back
            // into the transcript so the decision is visible without opening the tool detail.
            val payload = try { Json.parseToJsonElement(content).jsonObject } catch (_: Exception) { null }
            val status = (payload?.get("status") as? JsonPrimitive)?.content
            // `as?`, not `.jsonArray`: the extension THROWS IllegalArgumentException on anything
            // that is not an array, and this runs during composition on the Main thread — where
            // no CoroutineExceptionHandler applies and an escape goes straight to CrashReporter,
            // i.e. process death. The app's own writer can't produce a bad shape, but
            // DataImporter copies toolCallJson out of a backup archive verbatim, so a hand-edited
            // or older backup can. Rendering a label is never worth crashing over.
            val answers = (payload?.get("answers") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                ?.filter { it.isNotBlank() }
            when {
                content.isBlank() -> stringResource(R.string.tool_ask_waiting)
                status == "dismissed" -> stringResource(R.string.tool_ask_dismissed)
                !answers.isNullOrEmpty() ->
                    stringResource(R.string.tool_ask_answered, answers.joinToString("、").take(60))
                isError -> stringResource(R.string.tool_call_failed)
                else -> stringResource(R.string.tool_ask_user)
            }
        }
        "get_provider_balance" -> {
            // Payload: {"results":[{"provider":…,"keys":[{"key":…,"value":…}]}]} — one reading
            // per API key. The chip is a one-liner, so show the first successful value.
            val value = try {
                Json.parseToJsonElement(content).jsonObject["results"]?.jsonArray
                    ?.firstNotNullOfOrNull { providerEntry ->
                        providerEntry.jsonObject["keys"]?.jsonArray?.firstNotNullOfOrNull { key ->
                            (key.jsonObject["value"] as? JsonPrimitive)?.content
                        }
                    }
            } catch (_: Exception) { null }
            when {
                isError -> stringResource(R.string.tool_call_failed)
                content.isBlank() -> stringResource(R.string.tool_get_provider_balance)
                value != null -> stringResource(R.string.tool_provider_balance_done, value)
                else -> stringResource(R.string.tool_get_provider_balance)
            }
        }
        "list_skills" -> {
            if (isError) stringResource(R.string.tool_call_failed)
            else if (content.isBlank()) stringResource(R.string.tool_list_skills)
            else {
                val count = try {
                    (Json.parseToJsonElement(content).jsonObject["count"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                } catch (_: Exception) { 0 }
                stringResource(R.string.tool_list_skills_done, count)
            }
        }
        "load_skill" -> {
            if (isError) stringResource(R.string.tool_call_failed)
            else if (content.isBlank()) stringResource(R.string.tool_load_skill)
            else if (fileName != null) stringResource(R.string.tool_load_skill_name, fileName)
            else stringResource(R.string.tool_load_skill)
        }
        "create_skill" -> {
            if (isError) stringResource(R.string.tool_call_failed)
            else if (content.isBlank()) stringResource(R.string.tool_create_skill)
            else if (fileName != null) stringResource(R.string.tool_create_skill_name, fileName)
            else stringResource(R.string.tool_create_skill)
        }
        "update_skill" -> {
            if (isError) stringResource(R.string.tool_call_failed)
            else if (content.isBlank()) stringResource(R.string.tool_update_skill)
            else if (fileName != null) stringResource(R.string.tool_update_skill_name, fileName)
            else stringResource(R.string.tool_update_skill)
        }
        "delete_skill" -> {
            if (isError) stringResource(R.string.tool_call_failed)
            else if (content.isBlank()) stringResource(R.string.tool_delete_skill)
            else if (fileName != null) stringResource(R.string.tool_delete_skill_name, fileName)
            else stringResource(R.string.tool_delete_skill)
        }
        "search_skillsmp" -> {
            if (isError) stringResource(R.string.tool_call_failed)
            else if (content.isBlank()) stringResource(R.string.tool_search_skillsmp)
            else {
                val count = try {
                    (Json.parseToJsonElement(content).jsonObject["count"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                } catch (_: Exception) { 0 }
                stringResource(R.string.tool_search_skillsmp_done, count)
            }
        }
        "install_skill_from_skillsmp" -> {
            val resultName = try {
                (Json.parseToJsonElement(content).jsonObject["name"] as? JsonPrimitive)?.content
            } catch (_: Exception) { null }
            val argId = argsJson?.get("id")?.let { (it as? JsonPrimitive)?.content }
            when {
                isError -> stringResource(R.string.tool_call_failed)
                content.isBlank() -> stringResource(R.string.tool_install_skill_from_skillsmp)
                resultName != null -> stringResource(R.string.tool_install_skill_from_skillsmp_name, resultName)
                argId != null -> stringResource(R.string.tool_install_skill_from_skillsmp_name, argId)
                else -> stringResource(R.string.tool_install_skill_from_skillsmp)
            }
        }
        else -> {
            if (isError) stringResource(R.string.tool_call_failed)
            else stringResource(R.string.tool_done)
        }
    }
}

/** True when tool result is a JSON object with an `error` field (or explicit failed status). */
private fun toolResultLooksLikeError(content: String): Boolean {
    if (content.isBlank()) return false
    return try {
        val obj = Json.parseToJsonElement(content).jsonObject
        if (obj.containsKey("error")) return true
        val status = (obj["status"] as? JsonPrimitive)?.content?.lowercase()
        status == "error" || status == "failed"
    } catch (_: Exception) {
        false
    }
}
