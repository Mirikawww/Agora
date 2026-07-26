package com.newoether.agora.ui.chat.message

import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.ToolCallDisplayModes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageTimelineModeTest {
    private val toolOnly = listOf(
        MessageSegment(
            type = "tool",
            toolName = "search_conversations",
            toolArgs = "{\"query\":\"missing\"}",
            toolResult = "{\"error\":\"no_results\"}",
        ),
    )

    @Test
    fun timelineMode_keepsToolOnlyTurnInTimeline() {
        assertTrue(shouldUseTimelineSegments(ToolCallDisplayModes.TIMELINE, toolOnly))
    }

    @Test
    fun groupedTimelineMode_keepsToolOnlyTurnInTimeline() {
        assertTrue(shouldUseTimelineSegments(ToolCallDisplayModes.GROUPED_TIMELINE, toolOnly))
    }

    @Test
    fun compactMode_keepsToolOnlyTurnCompact() {
        assertFalse(shouldUseTimelineSegments(ToolCallDisplayModes.COMPACT, toolOnly))
    }

    @Test
    fun plainTimeline_stitchesAdjacentInfoIntoOneVisualBlock() {
        val twoTools = toolOnly + toolOnly.map { it.copy(toolName = "read_conversation") }

        assertEquals(2, timelineInfoBlockEnd(twoTools, startIndex = 0))
    }

    @Test
    fun timelineInfoBlock_stopsAtVisibleAnswer() {
        val segments = toolOnly + MessageSegment(type = "answer", content = "Done") + toolOnly

        assertEquals(1, timelineInfoBlockEnd(segments, startIndex = 0))
    }
}
