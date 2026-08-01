package com.newoether.agora.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads counts out of a stored tool result, accounting for [ResultBudget] truncation.
 *
 * A tool result is persisted in the exact form that was sent to the model, which means a large
 * payload has already been through [ResultBudget.clip]. For structured output that produces a
 * valid *envelope* rather than the original object:
 *
 * ```json
 * {"_agora_result_budget":{"truncated":true,...},"head":"{\"type\":\"web…","tail":"…\"}]}"}
 * ```
 *
 * Reading `results` off that envelope yields null, not an error. Treating the resulting 0 as
 * "the tool found nothing" is wrong in a way no exception handler catches: a successful 40-result
 * search renders as "no results found" purely because its payload was large enough to clip.
 *
 * [countArray] separates "the tool genuinely returned an empty array" from "the count is no longer
 * knowable", so callers can stay silent instead of asserting a false zero.
 */
object ToolResultPayload {

    /** Key written by [ResultBudget.clip] when it wraps structured output. */
    private const val BUDGET_KEY = "_agora_result_budget"

    sealed interface Count {
        /** The payload was intact and the array held exactly [size] entries. */
        data class Known(val size: Int) : Count

        /**
         * The payload was clipped or unparseable, so the original size is unrecoverable.
         *
         * Clipping keeps only bounded head/tail fragments as JSON strings; the element count of
         * the original array is not preserved anywhere, and reconstructing it from a fragment
         * would understate it.
         */
        data object Unknown : Count
    }

    /** True when [text] is a [ResultBudget] envelope rather than the tool's own output. */
    fun isTruncatedEnvelope(text: String): Boolean =
        parseObject(text)?.containsKey(BUDGET_KEY) == true

    /**
     * Counts the entries of the top-level array at [key].
     *
     * Returns [Count.Known] only when the payload is the tool's own intact output. A blank string
     * (the tool has not returned yet) is reported as [Count.Unknown], since no count exists yet.
     */
    fun countArray(text: String, key: String): Count {
        if (text.isBlank()) return Count.Unknown
        val json = parseObject(text) ?: return Count.Unknown
        if (json.containsKey(BUDGET_KEY)) return Count.Unknown
        val array = json[key] as? JsonArray ?: return Count.Known(0)
        return Count.Known(array.size)
    }

    /**
     * Reads a string field, seeing through a truncation envelope where possible.
     *
     * The head fragment retains the beginning of the original JSON byte-for-byte, so a short field
     * near the front (`type`, `query`) usually survives clipping intact.
     */
    fun stringField(text: String, key: String): String? {
        val json = parseObject(text) ?: return null
        (json[key] as? JsonPrimitive)?.content?.let { return it }
        if (!json.containsKey(BUDGET_KEY)) return null
        val head = (json["head"] as? JsonPrimitive)?.content ?: return null
        // The head is a prefix of a JSON object and is therefore not parseable on its own.
        // Match the field textually, accepting only a complete quoted value.
        val marker = "\"$key\":\""
        val start = head.indexOf(marker).takeIf { it >= 0 }?.plus(marker.length) ?: return null
        val builder = StringBuilder()
        var index = start
        while (index < head.length) {
            when (val c = head[index]) {
                '\\' -> {
                    val next = head.getOrNull(index + 1) ?: return null
                    builder.append(unescape(next) ?: return null)
                    index += 2
                }
                '"' -> return builder.toString()
                else -> {
                    builder.append(c)
                    index++
                }
            }
        }
        // Ran off the end of the fragment: the value itself was cut, so it is not recoverable.
        return null
    }

    private fun unescape(escaped: Char): Char? = when (escaped) {
        '"' -> '"'
        '\\' -> '\\'
        '/' -> '/'
        'n' -> '\n'
        't' -> '\t'
        'r' -> '\r'
        'b' -> '\b'
        // \uXXXX needs four more characters; a truncated fragment may not hold them.
        else -> null
    }

    private fun parseObject(text: String): JsonObject? =
        runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
}
