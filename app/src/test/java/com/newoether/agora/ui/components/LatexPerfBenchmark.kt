package com.newoether.agora.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the LaTeX pre-pass complexity.
 *
 * Both scanners once worked on `substring(i)` and indexed into that copy, so on prose with
 * no LaTeX — where every character falls through to the default branch — the whole tail was
 * copied once per character. That is O(n^2), it runs on the Main thread, and
 * [com.newoether.agora.ui.chat.message.MessageItemMarkdown] re-runs it on the *full
 * accumulated* reply on every stream update. Measured before the fix (desktop JVM):
 * 32k chars = 727 ms, which on a phone (5-15x slower) is a guaranteed ANR.
 *
 * After switching to index-based scanning the same input is ~3.7 ms. The threshold below is
 * deliberately loose (~50x headroom over the fixed cost, but ~3.5x under the broken cost) so
 * it survives slow CI machines while still failing if the quadratic behaviour comes back.
 */
class LatexPerfBenchmark {

    private fun prose(chars: Int): String {
        val unit = "模型在流式输出时会不断把已经收到的文本重新解析一遍，这段文字用来测量解析耗时。"
        return buildString { while (length < chars) append(unit) }.take(chars)
    }

    private fun timeMs(reps: Int, block: () -> Unit): Double {
        block() // warm up
        val t0 = System.nanoTime()
        repeat(reps) { block() }
        return (System.nanoTime() - t0) / 1e6 / reps
    }

    @Test
    fun prePassStaysLinear() {
        val s = prose(32_000)
        val elapsed = timeMs(5) {
            parseLatexSpans(s)
            s.escapeDollarForMarkdown()
        }
        println("LaTeX pre-pass on 32k chars: %.2f ms".format(elapsed))
        assertTrue(
            "LaTeX pre-pass took %.1f ms on 32k chars — the O(n^2) tail copy is back. ".format(elapsed) +
                "Scan with startsWith(needle, i) / relIndexOf() instead of substring(i).",
            elapsed < 200.0,
        )
    }
}
