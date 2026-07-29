package com.newoether.agora.mcp

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Lossless, request-scoped paging for serialized MCP results.
 *
 * Cursors deliberately contain only a random local id and character offset. The backing value is
 * retained only until [clearRequest], which GenerationManager calls in its terminal `finally`.
 */
internal class McpResultPager(
    private val initialMaxChars: Int = DEFAULT_PAGE_CHARS,
) {
    private data class StoredResult(
        val sourceTool: String,
        val value: String,
        val validOffsets: MutableSet<Int> = ConcurrentHashMap.newKeySet(),
    )

    private data class RequestState(
        val results: ConcurrentHashMap<String, StoredResult> = ConcurrentHashMap(),
        var totalChars: Int = 0,
    )

    private val requests = ConcurrentHashMap<String, RequestState>()

    init {
        require(initialMaxChars >= MIN_PAGE_CHARS) {
            "initialMaxChars must be at least $MIN_PAGE_CHARS"
        }
    }

    fun budget(
        requestId: String,
        sourceTool: String,
        value: String,
        maxChars: Int = initialMaxChars,
    ): String {
        require(maxChars >= MIN_PAGE_CHARS) {
            "maxChars must be at least $MIN_PAGE_CHARS for a resumable MCP result"
        }
        val effectiveMaxChars = maxChars.coerceAtMost(MAX_PAGE_CHARS)
        if (value.length <= effectiveMaxChars) return value
        require(requestId.isNotBlank()) { "requestId is required for resumable MCP results" }
        val resultId = UUID.randomUUID().toString()
        val request = requests.getOrPut(requestId) { RequestState() }
        val stored = synchronized(request) {
            if (
                request.results.size >= MAX_RESULTS_PER_REQUEST ||
                value.length > MAX_CACHED_CHARS_PER_REQUEST - request.totalChars
            ) {
                return capacityError()
            }
            StoredResult(
                sourceTool = sourceTool.take(MAX_SOURCE_TOOL_CHARS),
                value = value,
            ).also {
                request.results[resultId] = it
                request.totalChars += value.length
            }
        }
        return render(
            stored = stored,
            resultId = resultId,
            offset = 0,
            maxChars = effectiveMaxChars,
        )
    }

    fun page(requestId: String, cursor: String, maxChars: Int = DEFAULT_PAGE_CHARS): String {
        if (maxChars < MIN_PAGE_CHARS) return error("result_page_budget_too_small")
        val parsed = parseCursor(cursor)
            ?: return error("invalid_or_expired_cursor")
        val (resultId, offset) = parsed
        val stored = requests[requestId]?.results?.get(resultId)
            ?: return error("invalid_or_expired_cursor")
        if (offset !in stored.validOffsets || offset !in 0 until stored.value.length) {
            return error("invalid_or_expired_cursor")
        }
        return render(
            stored = stored,
            resultId = resultId,
            offset = offset,
            maxChars = maxChars.coerceAtMost(MAX_PAGE_CHARS),
        )
    }

    fun clearRequest(requestId: String) {
        requests.remove(requestId)
    }

    fun clearAll() {
        requests.clear()
    }

    private fun render(
        stored: StoredResult,
        resultId: String,
        offset: Int,
        maxChars: Int,
    ): String {
        fun envelope(chunkChars: Int): String {
            val end = (offset + chunkChars).coerceAtMost(stored.value.length)
            return buildJsonObject {
                put("_agora_mcp_result", buildJsonObject {
                    put("truncated", offset > 0 || end < stored.value.length)
                    put("original_chars", stored.value.length)
                    put("source_tool", stored.sourceTool)
                    put("offset", offset)
                    put("complete", end == stored.value.length)
                    put("cursor_lifecycle", "current_generation")
                    if (end < stored.value.length) {
                        put("resume_tool", RESULT_PAGE_TOOL)
                        put("next_cursor", cursor(resultId, end))
                    }
                })
                put("chunk", stored.value.substring(offset, end))
            }.toString()
        }

        val remainingChars = stored.value.length - offset
        val complete = envelope(remainingChars)
        if (complete.length <= maxChars) return complete

        val empty = envelope(0)
        check(empty.length <= maxChars) {
            "MCP result page envelope exceeds its configured character budget"
        }
        var low = 0
        // The complete envelope is structurally smaller because it omits the next cursor. Test it
        // above instead of assuming envelope length is monotonic at the final character.
        var high = minOf(remainingChars - 1, maxChars)
        var best = empty
        var bestEnd = offset
        while (low <= high) {
            val candidateChars = low + (high - low) / 2
            val candidate = envelope(candidateChars)
            if (candidate.length <= maxChars) {
                best = candidate
                bestEnd = offset + candidateChars
                low = candidateChars + 1
            } else {
                high = candidateChars - 1
            }
        }
        if (bestEnd < stored.value.length) stored.validOffsets += bestEnd
        return best
    }

    private fun parseCursor(cursor: String): Pair<String, Int>? {
        val separator = cursor.lastIndexOf(':')
        if (separator <= 0 || separator == cursor.lastIndex) return null
        val resultId = cursor.substring(0, separator)
        val offset = cursor.substring(separator + 1).toIntOrNull() ?: return null
        return resultId to offset
    }

    private fun cursor(resultId: String, offset: Int): String = "$resultId:$offset"

    private fun error(code: String): String = buildJsonObject {
        put("isError", true)
        put("error", code)
        put("hint", "MCP result cursors are valid only during the current generation.")
    }.toString()

    private fun capacityError(): String = buildJsonObject {
        put("isError", true)
        put("error", "mcp_result_cache_limit")
        put(
            "hint",
            "The current generation exceeded its resumable MCP result cache; " +
                "narrow the request before retrying.",
        )
    }.toString()

    companion object {
        internal const val MIN_PAGE_CHARS = 512
        const val DEFAULT_PAGE_CHARS = 12_000
        const val MAX_PAGE_CHARS = 12_000
        const val MAX_SOURCE_TOOL_CHARS = 64
        const val RESULT_PAGE_TOOL = "mcp_result_page"
        const val MAX_RESULTS_PER_REQUEST = 8
        const val MAX_CACHED_CHARS_PER_REQUEST = 1_000_000
    }
}
