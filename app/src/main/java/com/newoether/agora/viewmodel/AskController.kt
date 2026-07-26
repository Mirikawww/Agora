package com.newoether.agora.viewmodel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the user picks an answer in the ask sheet. */
enum class AskMode {
    /** Exactly one answer; tapping an option submits immediately. */
    SINGLE,

    /** Any number of answers, confirmed with a button. */
    MULTIPLE,

    /** Answers returned in the order the user picked them. */
    RANK;

    companion object {
        /** Lenient parse — models spell this field a dozen different ways. */
        fun parse(raw: String?): AskMode = when (raw?.trim()?.lowercase()) {
            "multiple", "multi", "multi_select", "multiselect", "multiple_choice", "checkbox" -> MULTIPLE
            "rank", "ranking", "rank_order", "order", "ordering", "sort", "priority" -> RANK
            else -> SINGLE
        }
    }
}

/**
 * Coordinates the `ask_user` tool's question sheet.
 *
 * Mirrors [ShellConfirmationController]: the generation pipeline asks and suspends,
 * the UI collects [pending] and answers, and a [CompletableDeferred] carries the
 * result back. A dismissed sheet resolves to null so the model learns the user
 * declined instead of hanging forever.
 */
class AskController {

    data class PendingAsk(
        val question: String,
        val header: String,
        val mode: AskMode,
        val options: List<String>,
        val deferred: CompletableDeferred<AskAnswer?>
    )

    /** [custom] is true when at least one answer was typed rather than picked. */
    data class AskAnswer(val values: List<String>, val custom: Boolean)

    private val _pending = MutableStateFlow<PendingAsk?>(null)
    val pending: StateFlow<PendingAsk?> = _pending.asStateFlow()

    /** Suspends until the user answers or dismisses; null means dismissed. */
    suspend fun ask(
        question: String,
        header: String,
        mode: AskMode,
        options: List<String>
    ): AskAnswer? {
        // A second question while one is open would orphan the first coroutine.
        _pending.value?.deferred?.complete(null)
        val deferred = CompletableDeferred<AskAnswer?>()
        _pending.value = PendingAsk(question, header, mode, options, deferred)
        return try {
            deferred.await()
        } finally {
            if (_pending.value?.deferred === deferred) _pending.value = null
        }
    }

    /** Called by the UI with the user's picks (in order, for RANK). */
    fun resolve(values: List<String>, custom: Boolean) {
        val pending = _pending.value ?: return
        pending.deferred.complete(AskAnswer(values, custom))
        _pending.value = null
    }

    /** Called by the UI when the sheet is swiped away or cancelled. */
    fun dismiss() {
        val pending = _pending.value ?: return
        pending.deferred.complete(null)
        _pending.value = null
    }
}
