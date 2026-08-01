package com.newoether.agora.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trailing cushion in [MessageList] must never grow back once it has shrunk.
 *
 * The spacer holds the newest user message near the top of the viewport while its reply is still
 * short, and it is derived from live per-message heights. Several of those heights are corrected
 * *downward* after a reply finishes — MessageItem re-reports 50ms after the terminal status, and
 * the thought block's collapsed height is only captured 500ms after it settles. A smaller reported
 * content height means a larger cushion, which pushes the anchored content upward: the jump the
 * user sees at the end of a reply, growing with every extra segment a long turn accumulates.
 *
 * This pins the clamp itself. The rule is what makes those late corrections inert.
 */
class MessageListCushionTest {

    /** Mirrors the clamp applied in MessageList: cushion is the running minimum. */
    private class Cushion {
        private var floor = Float.MAX_VALUE

        fun next(availableDp: Float, contentDp: Float): Float {
            val live = (availableDp - contentDp).coerceAtLeast(0f)
            floor = minOf(floor, live)
            return floor
        }
    }

    @Test
    fun `cushion shrinks while the reply grows`() {
        val cushion = Cushion()
        val available = 500f

        assertEquals(400f, cushion.next(available, contentDp = 100f), 0.01f)
        assertEquals(300f, cushion.next(available, contentDp = 200f), 0.01f)
        assertEquals(100f, cushion.next(available, contentDp = 400f), 0.01f)
    }

    @Test
    fun `a late downward height correction cannot widen the cushion`() {
        val cushion = Cushion()
        val available = 500f
        // The reply streams to its full height.
        cushion.next(available, contentDp = 420f)
        val settled = cushion.next(available, contentDp = 430f)

        // Generation ends; the thought block collapses and its excess is subtracted, so the
        // reported content height drops. Without the clamp the cushion would jump back up by
        // exactly that delta and shove the anchored content off the top of the screen.
        val afterCollapse = cushion.next(available, contentDp = 250f)
        val afterDelayedReport = cushion.next(available, contentDp = 300f)

        assertEquals(settled, afterCollapse, 0.01f)
        assertEquals(settled, afterDelayedReport, 0.01f)
    }

    @Test
    fun `the correction that used to cause the biggest jump is fully absorbed`() {
        val cushion = Cushion()
        val available = 600f
        val atCompletion = cushion.next(available, contentDp = 590f)

        // A long turn accumulates a large thought block, so its post-collapse correction is big.
        // This is why the jump got worse the further into a conversation the user went.
        val corrected = cushion.next(available, contentDp = 120f)

        assertEquals(atCompletion, corrected, 0.01f)
        assertTrue("cushion must stay at its floor", corrected <= atCompletion)
    }

    @Test
    fun `cushion never goes negative when the reply outgrows the viewport`() {
        val cushion = Cushion()

        assertEquals(0f, cushion.next(availableDp = 400f, contentDp = 900f), 0.01f)
        // Once pinned at zero it stays there, whatever later corrections claim.
        assertEquals(0f, cushion.next(availableDp = 400f, contentDp = 10f), 0.01f)
    }

    @Test
    fun `a fresh reply starts from a fresh cushion`() {
        // MessageList keys the running minimum on the last message id, so a new turn is a new
        // Cushion instance rather than an inherited floor from the previous reply.
        val previousTurn = Cushion()
        previousTurn.next(availableDp = 500f, contentDp = 480f)

        val newTurn = Cushion()
        assertEquals(400f, newTurn.next(availableDp = 500f, contentDp = 100f), 0.01f)
    }
}
