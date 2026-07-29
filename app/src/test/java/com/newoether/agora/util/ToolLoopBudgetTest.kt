package com.newoether.agora.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolLoopBudgetTest {
    @Test
    fun `single result keeps head and diagnostic tail within its budget`() {
        val budget = ToolLoopBudget(
            ToolLoopLimits(
                maxResultChars = 240,
                maxRoundChars = 1_000,
                maxGenerationChars = 2_000,
                maxContinuationRounds = 4,
            )
        )
        val raw = "stdout:" + "x".repeat(2_000) + "\nexit_code=1\nstderr=fatal"

        val round = budget.budgetRound(listOf(raw))

        assertEquals(1, round.results.size)
        assertTrue(round.results.single().length <= 240)
        assertTrue(round.results.single().startsWith("stdout:"))
        assertTrue(round.results.single().endsWith("exit_code=1\nstderr=fatal"))
        assertTrue(round.results.single().contains("\"truncated\":true"))
    }

    @Test
    fun `parallel results share the round budget fairly and deterministically`() {
        val budget = ToolLoopBudget(
            ToolLoopLimits(
                maxResultChars = 1_000,
                maxRoundChars = 441,
                maxGenerationChars = 2_000,
                maxContinuationRounds = 4,
            )
        )
        val firstLong = "first-head:" + "a".repeat(1_000) + ":first-tail-error"
        val secondLong = "second-head:" + "b".repeat(1_000) + ":second-tail-error"

        val round = budget.budgetRound(listOf("ok", firstLong, secondLong))

        assertEquals(listOf(2, 206, 206), round.results.map(String::length))
        assertTrue(round.results[1].startsWith("first-head:"))
        assertTrue(round.results[1].endsWith(":first-tail-error"))
        assertTrue(round.results[2].startsWith("second-head:"))
        assertTrue(round.results[2].endsWith(":second-tail-error"))
    }

    @Test
    fun `cumulative result budget stops further result injection with a diagnostic`() {
        val budget = ToolLoopBudget(
            ToolLoopLimits(
                maxResultChars = 500,
                maxRoundChars = 500,
                maxGenerationChars = 1_000,
                maxContinuationRounds = 4,
            )
        )

        budget.startProviderRound()
        val first = budget.budgetRound(listOf("a".repeat(400)))
        budget.startProviderRound()
        val exhausted = budget.budgetRound(listOf("head:" + "b".repeat(400) + ":tail-error"))
        budget.startProviderRound()
        val blocked = budget.executionBlockDiagnostic()!!
        val blockedResult = budget.budgetDiagnostics(blocked, 1).single()

        assertEquals(400, first.results.single().length)
        assertEquals(null, first.diagnostic)
        assertEquals(416, exhausted.results.single().length)
        assertTrue(exhausted.results.single().startsWith("head:"))
        assertTrue(exhausted.results.single().endsWith(":tail-error"))
        assertTrue(exhausted.exhausted)
        assertTrue(exhausted.diagnostic!!.contains("cumulative tool-result budget"))
        assertTrue(blocked.contains("cumulative tool-result budget"))
        assertTrue(blockedResult.isNotEmpty())
        assertTrue(
            first.results.single().length +
                exhausted.results.single().length +
                blockedResult.length <= 1_000,
        )
    }

    @Test
    fun `continuation breaker allows configured rounds then returns a diagnostic`() {
        val budget = ToolLoopBudget(
            ToolLoopLimits(
                maxResultChars = 500,
                maxRoundChars = 500,
                maxGenerationChars = 2_000,
                maxContinuationRounds = 2,
            )
        )

        val first = budget.startContinuation()
        val second = budget.startContinuation()
        val denied = budget.startContinuation()

        assertTrue(first.allowed)
        assertEquals(1, first.round)
        assertTrue(second.allowed)
        assertEquals(2, second.round)
        assertFalse(denied.allowed)
        assertEquals(2, denied.round)
        assertTrue(denied.diagnostic!!.contains("2 continuation rounds"))
    }

    @Test
    fun `sequential tool events share one provider round budget`() {
        val budget = ToolLoopBudget(
            ToolLoopLimits(
                maxResultChars = 200,
                maxRoundChars = 300,
                maxGenerationChars = 1_000,
                maxContinuationRounds = 4,
            ),
        )
        budget.startProviderRound()

        assertEquals(null, budget.executionBlockDiagnostic())
        val first = budget.budgetRound(listOf("a".repeat(200)))
        val blocked = budget.executionBlockDiagnostic()!!
        val diagnostic = budget.budgetDiagnostics(blocked, 1).single()

        assertEquals(200, first.results.single().length)
        assertTrue(blocked.contains("provider round"))
        assertTrue(diagnostic.startsWith("[Agora tool execution"))
        assertTrue(first.results.single().length + diagnostic.length <= 300)

        budget.startProviderRound()
        assertEquals(null, budget.executionBlockDiagnostic())
    }

    @Test
    fun `batch is blocked before execution when every result cannot stay usable`() {
        val budget = ToolLoopBudget(
            ToolLoopLimits(
                maxResultChars = 300,
                maxRoundChars = 600,
                maxGenerationChars = 2_000,
                maxContinuationRounds = 4,
            ),
        )
        budget.startProviderRound()
        val first = budget.budgetRound(listOf("a".repeat(300)))

        val blocked = budget.executionBlockDiagnostic(pendingResults = 2)
        val diagnostics = budget.budgetDiagnostics(blocked!!, count = 2)

        assertEquals(300, first.results.single().length)
        assertTrue(blocked.contains("2 pending"))
        assertEquals(2, diagnostics.size)
        assertTrue(diagnostics.all(String::isNotEmpty))
        assertTrue(first.results.single().length + diagnostics.sumOf(String::length) <= 600)
    }

    @Test
    fun `diagnostics obey the per-result hard limit`() {
        val budget = ToolLoopBudget(
            ToolLoopLimits(
                maxResultChars = 40,
                maxRoundChars = 500,
                maxGenerationChars = 1_000,
                maxContinuationRounds = 2,
            ),
        )
        budget.startProviderRound()

        val diagnostics = budget.budgetDiagnostics("d".repeat(300), count = 2)

        assertEquals(listOf(40, 40), diagnostics.map(String::length))
    }

    @Test
    fun `heterogeneous diagnostics keep their per-call cancellation meaning`() {
        val budget = ToolLoopBudget(
            ToolLoopLimits(
                maxResultChars = 100,
                maxRoundChars = 500,
                maxGenerationChars = 1_000,
                maxContinuationRounds = 2,
            ),
        )
        budget.startProviderRound()

        val diagnostics = budget.budgetDiagnostics(
            listOf(
                "[completion unknown; do not retry automatically]",
                "[not executed because generation stopped]",
            ),
        )

        assertEquals(2, diagnostics.size)
        assertTrue(diagnostics[0].contains("completion unknown"))
        assertTrue(diagnostics[1].contains("not executed"))
    }
}
