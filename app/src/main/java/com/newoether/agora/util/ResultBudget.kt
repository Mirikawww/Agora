package com.newoether.agora.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Bounds text returned by a tool without discarding the diagnostic tail.
 *
 * Tool output commonly puts stderr, exit codes, pagination cursors, or structured error fields at
 * the end. Keeping only the prefix makes a failed operation look successful and can make the model
 * retry blindly. The marker is deliberately machine-readable while the surrounding head and tail
 * remain byte-for-byte unchanged.
 */
object ResultBudget {
    fun clip(value: String, maxChars: Int): String {
        require(maxChars > 0) { "maxChars must be positive" }
        if (value.length <= maxChars) return value
        // kotlinx.serialization accepts some unquoted literals as JSON primitives. Tool output such
        // as a long identifier or log line must still use the text path; only structured JSON needs
        // the validity-preserving envelope.
        val firstNonWhitespace = value.firstOrNull { !it.isWhitespace() }
        if (
            firstNonWhitespace in setOf('{', '[') &&
            runCatching { Json.parseToJsonElement(value) }.isSuccess
        ) {
            return clipJson(value, maxChars)
        }

        val marker = "\n[Agora result budget: " +
            "{\"truncated\":true,\"original_chars\":${value.length}}]\n"
        if (marker.length >= maxChars) return marker.take(maxChars)

        val contentChars = maxChars - marker.length
        val headChars = (contentChars * HEAD_WEIGHT_NUMERATOR) / HEAD_WEIGHT_DENOMINATOR
        val tailChars = contentChars - headChars
        return value.take(headChars) + marker + value.takeLast(tailChars)
    }

    /**
     * Never splice a marker into structured output: that creates malformed JSON and can hide a
     * trailing error or cursor. A valid envelope makes truncation explicit and keeps bounded
     * head/tail fragments as strings.
     */
    private fun clipJson(value: String, maxChars: Int): String {
        fun envelope(contentChars: Int): String {
            val headChars =
                (contentChars * HEAD_WEIGHT_NUMERATOR) / HEAD_WEIGHT_DENOMINATOR
            val tailChars = contentChars - headChars
            return buildJsonObject {
                put("_agora_result_budget", buildJsonObject {
                    put("truncated", true)
                    put("original_chars", value.length)
                    put("content_format", "json_fragments")
                })
                put("head", value.take(headChars))
                put("tail", value.takeLast(tailChars))
            }.toString()
        }

        val emptyEnvelope = envelope(0)
        if (emptyEnvelope.length > maxChars) {
            return when {
                maxChars >= 18 -> """{"truncated":true}"""
                maxChars >= 4 -> "null"
                maxChars >= 2 -> "{}"
                else -> "0"
            }.take(maxChars)
        }

        var low = 0
        var high = minOf(value.length, maxChars)
        var best = emptyEnvelope
        while (low <= high) {
            val candidateChars = low + (high - low) / 2
            val candidate = envelope(candidateChars)
            if (candidate.length <= maxChars) {
                best = candidate
                low = candidateChars + 1
            } else {
                high = candidateChars - 1
            }
        }
        return best
    }

    private const val HEAD_WEIGHT_NUMERATOR = 2
    private const val HEAD_WEIGHT_DENOMINATOR = 3
}
