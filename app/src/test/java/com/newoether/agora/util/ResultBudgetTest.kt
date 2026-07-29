package com.newoether.agora.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultBudgetTest {
    @Test
    fun `oversized tool result preserves diagnostic tail within budget`() {
        val result = "stdout:" + "x".repeat(2_000) + "\nexit_code=1\nstderr=fatal"

        val clipped = ResultBudget.clip(result, maxChars = 240)

        assertTrue(clipped.length <= 240)
        assertTrue(clipped.startsWith("stdout:"))
        assertTrue(clipped.endsWith("exit_code=1\nstderr=fatal"))
        assertTrue(clipped.contains("\"truncated\":true"))
        assertTrue(clipped.contains("\"original_chars\":"))
    }

    @Test
    fun `oversized JSON remains valid and exposes bounded head and tail fragments`() {
        val raw = buildJsonObject {
            put("items", "x".repeat(2_000))
            put("next_cursor", "cursor-secret")
        }.toString()

        val clipped = ResultBudget.clip(raw, maxChars = 320)
        val envelope = Json.parseToJsonElement(clipped).jsonObject

        assertTrue(clipped.length <= 320)
        assertTrue(
            envelope["_agora_result_budget"]!!
                .jsonObject["truncated"]!!
                .jsonPrimitive
                .content
                .toBoolean(),
        )
        assertTrue(envelope["head"]!!.jsonPrimitive.content.startsWith("{"))
        assertTrue(envelope["tail"]!!.jsonPrimitive.content.endsWith("}"))
        assertTrue(envelope["tail"]!!.jsonPrimitive.content.contains("cursor-secret"))
    }
}
