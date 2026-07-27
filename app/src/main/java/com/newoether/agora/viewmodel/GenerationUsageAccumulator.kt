package com.newoether.agora.viewmodel

import com.newoether.agora.api.StreamEvent

internal data class GenerationTokenUsage(
    val tokenCount: Int = 0,
    val promptTokens: Int = 0,
    val cachedPromptTokens: Int = 0,
    /** Cache split is trustworthy only when every prompt-bearing round reported it. */
    val cacheTelemetryAvailable: Boolean = false,
    val promptUsageReported: Boolean = false,
    val completionTokens: Int = 0,
)

/**
 * Provider usage is cumulative within one HTTP request, while a tool-using generation issues
 * multiple requests. Keep the live request separate and settle it exactly once between rounds.
 */
internal class GenerationUsageAccumulator {
    private var settled = GenerationTokenUsage()
    private var round = GenerationTokenUsage()

    fun update(event: StreamEvent.UsageUpdate) {
        val eventHasPrompt = event.promptTokens > 0
        val cacheTelemetryAvailable = when {
            !eventHasPrompt -> round.cacheTelemetryAvailable
            !round.promptUsageReported -> event.cacheTelemetryAvailable
            else -> round.cacheTelemetryAvailable && event.cacheTelemetryAvailable
        }
        round = round.copy(
            tokenCount = event.tokenCount.takeIf { it > 0 } ?: round.tokenCount,
            promptTokens = event.promptTokens.takeIf { it > 0 } ?: round.promptTokens,
            cachedPromptTokens = event.cachedPromptTokens.takeIf { it > 0 } ?: round.cachedPromptTokens,
            cacheTelemetryAvailable = cacheTelemetryAvailable,
            promptUsageReported = round.promptUsageReported || eventHasPrompt,
            completionTokens = event.completionTokens.takeIf { it > 0 } ?: round.completionTokens,
        )
    }

    fun settleRound() {
        settled = combine(settled, round)
        round = GenerationTokenUsage()
    }

    fun snapshot() = combine(settled, round)

    private fun combine(
        left: GenerationTokenUsage,
        right: GenerationTokenUsage,
    ): GenerationTokenUsage {
        val promptReported = left.promptUsageReported || right.promptUsageReported
        val cacheComplete =
            (!left.promptUsageReported || left.cacheTelemetryAvailable) &&
                (!right.promptUsageReported || right.cacheTelemetryAvailable)
        return GenerationTokenUsage(
            tokenCount = left.tokenCount + right.tokenCount,
            promptTokens = left.promptTokens + right.promptTokens,
            cachedPromptTokens = left.cachedPromptTokens + right.cachedPromptTokens,
            cacheTelemetryAvailable = promptReported && cacheComplete,
            promptUsageReported = promptReported,
            completionTokens = left.completionTokens + right.completionTokens,
        )
    }
}
