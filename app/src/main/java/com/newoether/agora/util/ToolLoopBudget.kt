package com.newoether.agora.util

data class ToolLoopLimits(
    val maxResultChars: Int = 16_000,
    val maxRoundChars: Int = 32_000,
    val maxGenerationChars: Int = 64_000,
    val maxContinuationRounds: Int = 8,
) {
    init {
        require(maxResultChars > 0) { "maxResultChars must be positive" }
        require(maxRoundChars > 0) { "maxRoundChars must be positive" }
        require(maxGenerationChars > 0) { "maxGenerationChars must be positive" }
        require(maxContinuationRounds > 0) { "maxContinuationRounds must be positive" }
    }
}

data class BudgetedToolRound(
    val results: List<String>,
    val exhausted: Boolean = false,
    val diagnostic: String? = null,
)

data class ToolContinuationDecision(
    val allowed: Boolean,
    val round: Int,
    val diagnostic: String? = null,
)

/**
 * Owns all result and continuation limits for one generation.
 *
 * Callers submit the raw results produced by one tool event and receive wire-safe values in the
 * same order. Stateful generation and continuation limits stay behind this small interface.
 */
class ToolLoopBudget(
    private val limits: ToolLoopLimits = ToolLoopLimits(),
) {
    private val providerRoundDiagnosticReserve =
        minOf(MAX_ROUND_DIAGNOSTIC_RESERVE, limits.maxRoundChars / RESERVE_FRACTION)
            .coerceAtLeast(1)
    private val generationDiagnosticReserve =
        minOf(MAX_GENERATION_DIAGNOSTIC_RESERVE, limits.maxGenerationChars / RESERVE_FRACTION)
            .coerceAtLeast(1)
    private val providerRoundDataLimit =
        (limits.maxRoundChars - providerRoundDiagnosticReserve).coerceAtLeast(0)
    private val generationDataLimit =
        (limits.maxGenerationChars - generationDiagnosticReserve).coerceAtLeast(0)
    private val minimumResultChars = minOf(MIN_USABLE_RESULT_CHARS, limits.maxResultChars)

    private var generationDataChars = 0
    private var generationDiagnosticChars = 0
    private var providerRoundDataChars = 0
    private var providerRoundDiagnosticChars = 0
    private var continuationRounds = 0

    /** Starts one provider HTTP response; all tool events in that response share one round cap. */
    fun startProviderRound() {
        providerRoundDataChars = 0
        providerRoundDiagnosticChars = 0
    }

    /**
     * Checked before side-effecting tools run.
     *
     * [pendingResults] makes the guard reject a whole batch unless every call can retain a useful
     * minimum result. This prevents executing N mutations and later assigning an empty result to
     * the last one merely because the shared round budget had one character left.
     */
    fun executionBlockDiagnostic(pendingResults: Int = 1): String? {
        require(pendingResults > 0) { "pendingResults must be positive" }
        val requiredChars = pendingResults.toLong() * minimumResultChars
        val remainingGeneration =
            (generationDataLimit - generationDataChars).coerceAtLeast(0)
        val remainingRound =
            (providerRoundDataLimit - providerRoundDataChars).coerceAtLeast(0)
        return when {
            remainingGeneration.toLong() < requiredChars ->
            cumulativeBudgetDiagnostic()
            remainingRound.toLong() < requiredChars ->
                "[Agora tool execution skipped: this provider round cannot preserve at least " +
                    "$minimumResultChars characters for each of $pendingResults pending " +
                    "tool result(s) within its ${limits.maxRoundChars}-character budget.]"
            else -> null
        }
    }

    /**
     * Maximum size each of [pendingResults] may return without a later shared-budget clip.
     *
     * Resumable providers use this before execution so any cursor they emit advances only past
     * bytes that are guaranteed to reach the model. Other tools may still return more and use the
     * normal head/tail clipping path.
     */
    fun maxResultCharsPerPending(pendingResults: Int = 1): Int {
        require(pendingResults > 0) { "pendingResults must be positive" }
        val remainingGeneration =
            (generationDataLimit - generationDataChars).coerceAtLeast(0)
        val remainingRound =
            (providerRoundDataLimit - providerRoundDataChars).coerceAtLeast(0)
        return minOf(
            limits.maxResultChars,
            minOf(remainingGeneration, remainingRound) / pendingResults,
        )
    }

    fun budgetRound(results: List<String>): BudgetedToolRound {
        val desiredChars = results.map { minOf(it.length, limits.maxResultChars) }
        val remainingGenerationChars =
            (generationDataLimit - generationDataChars).coerceAtLeast(0)
        val remainingRoundChars =
            (providerRoundDataLimit - providerRoundDataChars).coerceAtLeast(0)
        val effectiveRoundBudget = minOf(remainingRoundChars, remainingGenerationChars)
        val allocations = fairAllocations(desiredChars, effectiveRoundBudget)
        val budgetedResults = results.mapIndexed { index, result ->
            allocations[index].takeIf { it > 0 }?.let { ResultBudget.clip(result, it) }.orEmpty()
        }
        val injectedChars = budgetedResults.sumOf(String::length)
        providerRoundDataChars += injectedChars
        generationDataChars += injectedChars
        val cumulativeBudgetExhausted =
            results.isNotEmpty() &&
                generationDataLimit - generationDataChars < minimumResultChars
        return BudgetedToolRound(
            results = budgetedResults,
            exhausted = cumulativeBudgetExhausted,
            diagnostic = cumulativeBudgetExhausted.takeIf { it }?.let {
                cumulativeBudgetDiagnostic()
            },
        )
    }

    /**
     * Budgets synthetic "not executed" results separately from real tool payloads.
     *
     * A small reserve keeps at least one diagnostic available after the data cap is reached, while
     * the separate accounting prevents repeated blocked calls from bypassing the round/generation
     * hard limits.
     */
    fun budgetDiagnostics(diagnostic: String, count: Int): List<String> {
        require(count >= 0) { "count must not be negative" }
        return budgetDiagnostics(List(count) { diagnostic })
    }

    /**
     * Variant for a heterogeneous batch. Cancellation uses this to distinguish the one call whose
     * completion is unknown from the untouched suffix that is known not to have executed.
     */
    fun budgetDiagnostics(diagnostics: List<String>): List<String> {
        if (diagnostics.isEmpty()) return emptyList()
        // Diagnostics may borrow unused data headroom, but real payloads can never consume the
        // reserved tail. Both counters still sum to at most the public hard limits.
        val remainingGeneration = (
            limits.maxGenerationChars -
                generationDataChars -
                generationDiagnosticChars
            ).coerceAtLeast(0)
        val remainingRound = (
            limits.maxRoundChars -
                providerRoundDataChars -
                providerRoundDiagnosticChars
            ).coerceAtLeast(0)
        val available = minOf(remainingGeneration, remainingRound)
        val desired = diagnostics.map { minOf(it.length, limits.maxResultChars) }
        val allocations = fairAllocations(desired, available)
        val results = allocations.mapIndexed { index, allocation ->
            diagnostics[index].take(allocation)
        }
        val injectedChars = results.sumOf(String::length)
        generationDiagnosticChars += injectedChars
        providerRoundDiagnosticChars += injectedChars
        return results
    }

    fun startContinuation(): ToolContinuationDecision {
        if (continuationRounds >= limits.maxContinuationRounds) {
            return ToolContinuationDecision(
                allowed = false,
                round = continuationRounds,
                diagnostic = "[Agora tool loop stopped after ${limits.maxContinuationRounds} " +
                    "continuation rounds; no further tool continuation will be sent.]",
            )
        }
        continuationRounds += 1
        return ToolContinuationDecision(
            allowed = true,
            round = continuationRounds,
        )
    }

    private fun cumulativeBudgetDiagnostic() =
        "[Agora tool loop stopped: cumulative tool-result budget of " +
            "${limits.maxGenerationChars} characters has no safe payload space remaining; " +
            "no further tools will execute.]"

    private fun fairAllocations(desiredChars: List<Int>, totalBudget: Int): IntArray {
        val allocations = IntArray(desiredChars.size)
        val active = desiredChars.indices.filterTo(mutableListOf()) { desiredChars[it] > 0 }
        var remaining = totalBudget

        while (active.isNotEmpty() && remaining > 0) {
            val evenShare = remaining / active.size
            val completed = active.filter { desiredChars[it] <= evenShare }
            if (completed.isNotEmpty()) {
                completed.forEach { index ->
                    allocations[index] = desiredChars[index]
                    remaining -= allocations[index]
                    active.remove(index)
                }
                continue
            }

            val remainder = remaining % active.size
            active.forEachIndexed { position, index ->
                allocations[index] = evenShare + if (position < remainder) 1 else 0
            }
            remaining = 0
        }
        return allocations
    }

    private companion object {
        const val MIN_USABLE_RESULT_CHARS = 512
        const val RESERVE_FRACTION = 16
        const val MAX_ROUND_DIAGNOSTIC_RESERVE = 512
        const val MAX_GENERATION_DIAGNOSTIC_RESERVE = 2_048
    }
}
