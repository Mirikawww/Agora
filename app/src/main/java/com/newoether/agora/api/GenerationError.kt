package com.newoether.agora.api

/**
 * Typed error hierarchy for LLM generation failures.
 *
 * Replaces ad-hoc string-based error messages in StreamEvent.Error with
 * structured types that enable differentiated UI handling (retry actions,
 * error icons, recovery strategies) per error category.
 *
 * Phase 1b creates the type hierarchy. Phase 7 migrates all provider
 * emit sites from StreamEvent.Error(String) to StreamEvent.Error(GenerationError).
 */
sealed class GenerationError {

    enum class Origin { API, NETWORK, TOOL, CONFIGURATION, APP }

    val origin: Origin
        get() = when (this) {
            is Api, is SseParse, is EmptyStream -> Origin.API
            is Network -> if (statusCode == 0) Origin.NETWORK else Origin.API
            Timeout, is StreamStalled -> Origin.NETWORK
            is ToolExecution, is Transcription, is Embedding -> Origin.TOOL
            is Configuration -> Origin.CONFIGURATION
            is LocalModel, is Unknown, Cancelled -> Origin.APP
        }

    /** HTTP-level error (connection refused, timeout, DNS failure, etc.). */
    data class Network(
        val statusCode: Int,
        val message: String
    ) : GenerationError()

    /** API-level error returned by the provider (invalid key, rate limit, server error). */
    data class Api(
        val code: String?,
        val type: String?,
        val message: String
    ) : GenerationError()

    /** Failed to parse a line from the SSE stream. */
    data class SseParse(
        val rawLine: String,
        val cause: String
    ) : GenerationError()

    /**
     * The upstream accepted the request (HTTP 200) but the stream carried no displayable
     * content. Carries what the reader actually saw so the user gets a real diagnosis
     * instead of "the stream ended without a response" — the distinction between a relay
     * that sent nothing, one that sent unparseable frames, and one that stopped early for
     * a stated reason is exactly what makes this debuggable.
     */
    data class EmptyStream(
        val bytesRead: Long,
        val totalLines: Int,
        val dataLines: Int,
        val parseFailures: Int,
        val finishReason: String?,
        val elapsedMs: Long,
        val sampleLine: String? = null
    ) : GenerationError()

    /** The stream went silent mid-flight (or never produced a first token) and was abandoned. */
    data class StreamStalled(
        val afterMs: Long,
        val producedContent: Boolean
    ) : GenerationError()

    /** A tool execution failed (memory, web search, shell, RAG). */
    data class ToolExecution(
        val toolName: String,
        val arguments: String,
        val message: String
    ) : GenerationError()

    /** Image/video/PDF transcription failed. */
    data class Transcription(
        val imagePath: String,
        val message: String
    ) : GenerationError()

    /** Embedding computation failed. */
    data class Embedding(
        val modelId: String,
        val message: String
    ) : GenerationError()

    /** On-device GGUF model error (file not found, failed to load, etc.). */
    data class LocalModel(
        val message: String
    ) : GenerationError()

    /** Missing or invalid configuration (no API key, no base URL, etc.). */
    data class Configuration(
        val message: String
    ) : GenerationError()

    /** Wraps an unexpected exception. */
    data class Unknown(
        val cause: Throwable
    ) : GenerationError()

    /** Generation was cancelled by the user. */
    object Cancelled : GenerationError()

    /** Request timed out waiting for a server response. */
    object Timeout : GenerationError()

    /** Human-readable message suitable for displaying in the UI. */
    fun userMessage(): String = when (this) {
        is Network -> when (statusCode) {
            401 -> "Authentication failed. Please check your API key."
            429 -> "Rate limit exceeded. Please wait and try again."
            in 500..599 -> "Server error ($statusCode). The service may be temporarily unavailable."
            else -> "Network error ($statusCode): $message"
        }
        is Api -> buildString {
            if (code != null) append("$code")
            if (type != null) append(" [$type]")
            if (isNotEmpty()) append(": ")
            append(message)
        }
        is SseParse -> "Failed to parse server response."
        is EmptyStream -> buildString {
            append("The upstream accepted the request (HTTP 200) but returned no content.\n")
            append("Read $bytesRead bytes / $totalLines lines in ${elapsedMs}ms")
            if (dataLines > 0) append(", $dataLines SSE data frames") else append(", no SSE data frames")
            if (parseFailures > 0) append(", $parseFailures unparseable")
            append(".")
            if (finishReason != null) append("\nfinish_reason: \"$finishReason\"")
            when {
                bytesRead == 0L ->
                    append("\nThe response body was empty — the relay or upstream model is likely unavailable.")
                dataLines == 0 ->
                    append("\nNothing matched the SSE \"data:\" format — the endpoint may not be streaming.")
                parseFailures > 0 ->
                    append("\nFrames arrived but could not be decoded — the response shape is unexpected.")
            }
            if (sampleLine != null) append("\nFirst unrecognised line: ${sampleLine.take(300)}")
        }
        is StreamStalled ->
            if (producedContent) "The response stopped partway through and went silent for ${afterMs / 1000}s."
            else "No response after ${afterMs / 1000}s. The upstream accepted the request but never started replying."
        is ToolExecution -> "Tool '$toolName' failed: $message"
        is Transcription -> "Image transcription failed: $message"
        is Embedding -> "Embedding failed: $message"
        is LocalModel -> message
        is Configuration -> message
        is Unknown -> cause.localizedMessage ?: "An unexpected error occurred."
        Cancelled -> "Generation cancelled."
        Timeout -> "Request timed out."
    }
}
