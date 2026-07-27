package com.newoether.agora.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class DefaultSystemPromptTest {
    @Test
    fun titleForLocale_usesChineseDefaultForChineseLocale() {
        assertEquals("Default", DefaultSystemPrompt.titleForLocale(Locale.ENGLISH))
        assertEquals("\u9ed8\u8ba4", DefaultSystemPrompt.titleForLocale(Locale.SIMPLIFIED_CHINESE))
        assertEquals("\u9810\u8a2d", DefaultSystemPrompt.titleForLocale(Locale.forLanguageTag("zh-Hant")))
        assertEquals("Predeterminado", DefaultSystemPrompt.titleForLocale(Locale.forLanguageTag("es")))
        assertEquals("Par d\u00e9faut", DefaultSystemPrompt.titleForLocale(Locale.FRENCH))
    }

    @Test
    fun create_carriesActiveMemoryAndToolPolicy() {
        val entry = DefaultSystemPrompt.create(Locale.ENGLISH)
        val systemPrompt = PredefinedVariables.compile(
            entry.systemItems,
            mapOf(
                PredefinedVariables.DATE to "2026-06-17",
                PredefinedVariables.TIME to "21:35:10",
                PredefinedVariables.ACTIVE_MEMORY to "User prefers concise answers."
            )
        )

        assertTrue(systemPrompt.contains("<active_memory_context>\nUser prefers concise answers.\n</active_memory_context>"))
        assertTrue(systemPrompt.contains("Tool use:"))
        assertTrue(systemPrompt.contains("wait for user approval"))
        assertFalse(systemPrompt.contains("generate_image"))
    }

    /**
     * The clock must not appear in the system prompt: a value that changes every second at the
     * front of the prefix makes everything after it — including the tool schemas — uncacheable.
     * Real-time awareness comes from the per-message `sent_time` stamp instead, which renders
     * identically on every request.
     */
    @Test
    fun create_keepsVolatileValuesOutOfTheCacheablePrefix() {
        val entry = DefaultSystemPrompt.create(Locale.ENGLISH)
        val systemPrompt = PredefinedVariables.compile(
            entry.systemItems,
            mapOf(
                PredefinedVariables.DATE to "2026-06-17",
                PredefinedVariables.TIME to "21:35:10",
                PredefinedVariables.ACTIVE_MEMORY to "User prefers concise answers."
            )
        )

        assertFalse("system prompt must not embed the wall clock", systemPrompt.contains("21:35:10"))
        assertFalse("system prompt must not embed the current date", systemPrompt.contains("2026-06-17"))
        assertTrue("model must be told where the timestamp lives", systemPrompt.contains("sent_time"))

        // The only per-request variable left must sit at the tail, so everything before it caches.
        val memoryAt = systemPrompt.indexOf("User prefers concise answers.")
        assertTrue("active memory must appear", memoryAt >= 0)
        assertTrue(
            "active memory is the only volatile block and must be near the end",
            memoryAt > systemPrompt.length / 2,
        )
    }

    @Test
    fun create_wrapsUserMessagesWithSentDateAndTimeMetadata() {
        val entry = DefaultSystemPrompt.create(Locale.ENGLISH)
        val prefix = PredefinedVariables.compile(
            entry.userPrependItems,
            mapOf(
                PredefinedVariables.SENT_DATE to "2026-06-17",
                PredefinedVariables.SENT_TIME to "21:35:10"
            ),
            emptyMap()
        )
        val suffix = PredefinedVariables.compile(entry.userPostpendItems, emptyMap(), emptyMap())

        assertEquals("<agora_user_message sent_date=\"2026-06-17\" sent_time=\"21:35:10\">\n", prefix)
        assertEquals("\n</agora_user_message>", suffix)
    }
}
