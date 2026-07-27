package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Lets the model read/update the user's personalization profile (settings → 个性化)
 * during a conversation when the user states facts like their name.
 */
class PersonalizationToolProvider(
    private val settings: SettingsRepository,
) : ToolProvider {

    /**
     * `get_user_profile` is deliberately NOT advertised.
     *
     * Every profile field is already inlined into the system prompt by
     * [com.newoether.agora.viewmodel.GenerationRequestBuilder.buildUserProfileBlock], so exposing
     * a tool to read them paid for the same data twice — once as prose the model always sees, and
     * again as a schema on every request — while inviting a pointless round trip to fetch what was
     * already in front of it. The executor still answers the call so historical conversations that
     * recorded one replay cleanly.
     */
    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.personalizationToolsEnabled) return emptyList()
        return listOf(
        ToolDefinition(
            function = ToolFunction(
                name = "update_user_profile",
                description = "Update the user's personalization profile fields. Call this when the user states personal facts they want remembered (e.g. name/nickname). Only set fields the user provided. If the user asks NOT to save personalization, do not call this tool. Empty string clears a field.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "nickname" to ToolProperty("string", "User nickname / preferred name."),
                        "gender" to ToolProperty("string", "Gender."),
                        "age" to ToolProperty("string", "Age."),
                        "height" to ToolProperty("string", "Height."),
                        "occupation" to ToolProperty("string", "Occupation / job."),
                        "other" to ToolProperty("string", "Other free-form personal notes."),
                    ),
                    required = emptyList(),
                ),
            )
        ),
        )
    }

    override fun handles(name: String): Boolean =
        name == "get_user_profile" || name == "update_user_profile"

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String =
        withContext(Dispatchers.IO) {
            when (name) {
                "get_user_profile" -> buildJsonObject {
                    put("nickname", settings.userProfileNickname.value)
                    put("gender", settings.userProfileGender.value)
                    put("age", settings.userProfileAge.value)
                    put("height", settings.userProfileHeight.value)
                    put("occupation", settings.userProfileOccupation.value)
                    put("other", settings.userProfileOther.value)
                }.toString()
                "update_user_profile" -> {
                    val args = try {
                        Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(
                            arguments.ifBlank { "{}" }
                        )
                    } catch (_: Exception) {
                        emptyMap()
                    }
                    fun str(key: String): String? =
                        (args[key] as? JsonPrimitive)?.content

                    val updated = mutableListOf<String>()
                    str("nickname")?.let {
                        settings.setUserProfileNickname(it)
                        updated += "nickname"
                    }
                    str("gender")?.let {
                        settings.setUserProfileGender(it)
                        updated += "gender"
                    }
                    str("age")?.let {
                        settings.setUserProfileAge(it)
                        updated += "age"
                    }
                    str("height")?.let {
                        settings.setUserProfileHeight(it)
                        updated += "height"
                    }
                    str("occupation")?.let {
                        settings.setUserProfileOccupation(it)
                        updated += "occupation"
                    }
                    str("other")?.let {
                        settings.setUserProfileOther(it)
                        updated += "other"
                    }
                    if (updated.isEmpty()) {
                        return@withContext buildJsonObject {
                            put("status", "noop")
                            put("message", "No fields provided.")
                        }.toString()
                    }
                    buildJsonObject {
                        put("status", "ok")
                        put("updated", updated.joinToString(","))
                    }.toString()
                }
                else -> """{"error":"unknown_tool"}"""
            }
        }
}
