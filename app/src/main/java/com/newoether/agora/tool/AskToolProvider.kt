package com.newoether.agora.tool

import com.newoether.agora.api.DeferPolicy
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.viewmodel.AskController
import com.newoether.agora.viewmodel.AskMode
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Lets the model put a question to the user mid-turn and wait for the answer.
 *
 * The sheet always offers a free-text field alongside the model's options — the
 * user is never boxed into choices the model imagined. Execution suspends until
 * the sheet is answered or dismissed (see [com.newoether.agora.viewmodel.AskController]).
 */
class AskToolProvider : ToolProvider {

    /** Set by [com.newoether.agora.viewmodel.GenerationManager]; null = no UI attached. */
    var ask: (suspend (question: String, header: String, mode: AskMode, options: List<String>) -> AskController.AskAnswer?)? = null

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.askToolEnabled) return emptyList()
        return listOf(
        ToolDefinition(
            function = ToolFunction(
                name = "ask_user",
                description = "Ask the user a question and wait for their answer. Use this when a decision is " +
                    "genuinely theirs — an ambiguous request, a choice between approaches, a preference you " +
                    "cannot infer — instead of guessing or listing options in prose. Do NOT use it for questions " +
                    "you can answer yourself, and do not use it to ask for confirmation of work already agreed on. " +
                    "The user can always type an answer of their own, so options need not be exhaustive.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "question" to ToolProperty(
                            "string",
                            "The question, phrased clearly and completely. Ends with a question mark."
                        ),
                        "mode" to ToolProperty(
                            "string",
                            "\"single\" = pick one option (default), \"multiple\" = pick any number, " +
                                "\"rank\" = put the options in order of preference."
                        ),
                        "options" to ToolProperty(
                            "array",
                            "2-6 short, distinct choices. For \"rank\", these are the items to order.",
                            items = ToolProperty("string", "One choice, a few words.")
                        ),
                        "header" to ToolProperty(
                            "string",
                            "Optional short label for the question (max ~12 chars), e.g. \"Auth method\"."
                        ),
                    ),
                    required = listOf("question", "options"),
                ),
            ),
            defer = DeferPolicy.EAGER,
        )
        )
    }

    override fun handles(name: String): Boolean = name == "ask_user"

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (name != "ask_user") return """{"error":"unknown_tool"}"""
        val askUser = ask ?: return buildJsonObject {
            put("type", "ask_user")
            put("error", "unavailable")
        }.toString()

        val args = runCatching {
            Json.parseToJsonElement(arguments.ifBlank { "{}" }) as? JsonObject
        }.getOrNull() ?: JsonObject(emptyMap())

        fun str(key: String): String = (args[key] as? JsonPrimitive)?.content?.trim().orEmpty()
        val question = str("question")
        if (question.isBlank()) {
            return buildJsonObject {
                put("type", "ask_user")
                put("error", "no_question")
            }.toString()
        }
        val mode = AskMode.parse(str("mode"))
        // Accept both ["a","b"] and [{"label":"a"}] — models emit either shape.
        val options = (args["options"] as? JsonArray).orEmpty().mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> element.content.trim()
                is JsonObject -> listOf("label", "option", "text", "value", "name")
                    .firstNotNullOfOrNull { (element[it] as? JsonPrimitive)?.content?.trim() }
                else -> null
            }?.takeIf { it.isNotBlank() }
        }.distinct()

        val answer = askUser(question, str("header"), mode, options)
            ?: return buildJsonObject {
                put("type", "ask_user")
                put("question", question)
                put("status", "dismissed")
                put("message", "The user closed the question without answering. Do not ask again immediately; " +
                    "proceed with a sensible default and say which one you chose.")
            }.toString()

        return buildJsonObject {
            put("type", "ask_user")
            put("question", question)
            put("mode", mode.name.lowercase())
            put("status", "answered")
            // RANK returns the values in the user's chosen order; the array order is the answer.
            put("answers", buildJsonArray { answer.values.forEach { add(JsonPrimitive(it)) } })
            put("custom", answer.custom)
        }.toString()
    }

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
}
