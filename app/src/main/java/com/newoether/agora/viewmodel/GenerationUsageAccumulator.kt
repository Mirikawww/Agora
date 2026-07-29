package com.newoether.agora.viewmodel

import com.newoether.agora.api.StreamEvent
import com.newoether.agora.model.GenerationRoundUsage

internal data class GenerationTokenUsage(
    val tokenCount: Int = 0,
    val promptTokens: Int = 0,
    val cachedPromptTokens: Int = 0,
    /** Cache split is trustworthy only when every prompt-bearing round reported it. */
    val cacheTelemetryAvailable: Boolean = false,
    val promptUsageReported: Boolean = false,
    val completionTokens: Int = 0,
)

internal data class RoundUsageMetadata(
    val durationMs: Long = 0L,
    val toolExecutionMs: Long = 0L,
    val toolNames: List<String> = emptyList(),
    val originalToolResultChars: Int = 0,
    val injectedToolResultChars: Int = 0,
    val brokerActions: List<String> = emptyList(),
    val routeMode: String? = null,
    val inlineSchemaTokens: Int = 0,
    val brokerSchemaTokens: Int = 0,
    val wireSchemaTokens: Int = 0,
    val wireToolCount: Int = 0,
)

/**
 * Provider usage is cumulative within one HTTP request, while a tool-using generation issues
 * multiple requests. Keep the live request separate and settle it exactly once between rounds.
 */
internal class GenerationUsageAccumulator {
    private var settled = GenerationTokenUsage()
    private var round = GenerationTokenUsage()
    private val settledRounds = mutableListOf<GenerationRoundUsage>()

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

    fun settleRound(metadata: RoundUsageMetadata = RoundUsageMetadata()) {
        settledRounds += GenerationRoundUsage(
            roundIndex = settledRounds.size + 1,
            tokenCount = round.tokenCount,
            promptTokens = round.promptTokens,
            cachedPromptTokens = round.cachedPromptTokens,
            cacheTelemetryAvailable = round.promptUsageReported && round.cacheTelemetryAvailable,
            providerUsageReported = round.promptUsageReported ||
                round.completionTokens > 0 ||
                round.tokenCount > 0,
            promptUsageReported = round.promptUsageReported,
            completionTokens = round.completionTokens,
            durationMs = metadata.durationMs,
            toolExecutionMs = metadata.toolExecutionMs,
            toolNames = metadata.toolNames.distinct(),
            originalToolResultChars = metadata.originalToolResultChars,
            injectedToolResultChars = metadata.injectedToolResultChars,
            brokerActions = metadata.brokerActions.distinct(),
            routeMode = metadata.routeMode,
            inlineSchemaTokens = metadata.inlineSchemaTokens,
            brokerSchemaTokens = metadata.brokerSchemaTokens,
            wireSchemaTokens = metadata.wireSchemaTokens,
            wireToolCount = metadata.wireToolCount,
        )
        settled = combine(settled, round)
        round = GenerationTokenUsage()
    }

    fun snapshot() = combine(settled, round)

    fun rounds(): List<GenerationRoundUsage> = settledRounds.toList()

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
