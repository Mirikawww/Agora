package com.newoether.agora.util

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolResultPayloadTest {

    /** A realistic web_search payload with [count] results. */
    private fun webSearchResult(count: Int, query: String = "kotlin coroutines"): String =
        buildJsonObject {
            put("type", "web_search")
            put("query", query)
            put("results", buildJsonArray {
                repeat(count) { i ->
                    add(buildJsonObject {
                        put("title", "Result $i about $query")
                        put("url", "https://example.com/article-$i")
                        put(
                            "description",
                            "A reasonably long snippet for result $i, matching the size real " +
                                "search providers return for each hit in their response body.",
                        )
                    })
                }
            })
        }.toString()

    @Test
    fun `intact payload reports its exact result count`() {
        val payload = webSearchResult(12)

        assertEquals(
            ToolResultPayload.Count.Known(12),
            ToolResultPayload.countArray(payload, "results"),
        )
        assertFalse(ToolResultPayload.isTruncatedEnvelope(payload))
    }

    @Test
    fun `genuinely empty results stay a known zero`() {
        val payload = buildJsonObject {
            put("type", "web_search")
            put("query", "nothing matches this")
            put("results", buildJsonArray {})
        }.toString()

        assertEquals(
            ToolResultPayload.Count.Known(0),
            ToolResultPayload.countArray(payload, "results"),
        )
    }

    @Test
    fun `a successful search clipped by the result budget is not reported as zero`() {
        // Exactly what GenerationManager persists: the tool succeeded with 40 results, then
        // ResultBudget clipped the payload to fit the round's data budget.
        val payload = webSearchResult(40)
        val clipped = ResultBudget.clip(payload, 800)

        assertTrue(
            "expected the budget to actually clip this payload",
            clipped.length < payload.length,
        )
        assertTrue(
            "clipped structured output must stay valid JSON",
            ToolResultPayload.isTruncatedEnvelope(clipped),
        )
        // The regression: reading `results` off the envelope yields null with no exception, so a
        // naive `?: 0` renders a successful search as "no results found".
        assertEquals(
            ToolResultPayload.Count.Unknown,
            ToolResultPayload.countArray(clipped, "results"),
        )
    }

    @Test
    fun `query survives clipping so the label can still name the search`() {
        val payload = webSearchResult(40, query = "how do kotlin coroutines work")
        val clipped = ResultBudget.clip(payload, 800)

        assertEquals(
            "how do kotlin coroutines work",
            ToolResultPayload.stringField(clipped, "query"),
        )
        assertEquals("web_search", ToolResultPayload.stringField(clipped, "type"))
    }

    @Test
    fun `a field cut mid-value is reported as unrecoverable rather than truncated text`() {
        // The head fragment ends inside the query string, so no complete value can be read.
        val payload = buildJsonObject {
            put("type", "web_search")
            put("query", "a".repeat(2_000))
            put("results", buildJsonArray { add(buildJsonObject { put("url", "x") }) })
        }.toString()
        val clipped = ResultBudget.clip(payload, 300)

        assertTrue(ToolResultPayload.isTruncatedEnvelope(clipped))
        assertNull(ToolResultPayload.stringField(clipped, "query"))
    }

    @Test
    fun `escaped characters in a surviving field are decoded`() {
        val payload = buildJsonObject {
            put("type", "web_search")
            put("query", "say \"hello\"\tnow")
            put("results", buildJsonArray {
                repeat(40) { add(buildJsonObject { put("url", "https://example.com/$it") }) }
            })
        }.toString()
        val clipped = ResultBudget.clip(payload, 700)

        assertTrue(ToolResultPayload.isTruncatedEnvelope(clipped))
        assertEquals("say \"hello\"\tnow", ToolResultPayload.stringField(clipped, "query"))
    }

    @Test
    fun `unparseable and pending payloads are unknown, not zero`() {
        assertEquals(ToolResultPayload.Count.Unknown, ToolResultPayload.countArray("", "results"))
        assertEquals(
            ToolResultPayload.Count.Unknown,
            ToolResultPayload.countArray("Error: upstream refused", "results"),
        )
    }

    @Test
    fun `a missing array on an intact payload is a real zero`() {
        // The tool returned structured output that simply carries no results key.
        val payload = buildJsonObject {
            put("type", "web_search")
            put("query", "q")
        }.toString()

        assertEquals(
            ToolResultPayload.Count.Known(0),
            ToolResultPayload.countArray(payload, "results"),
        )
    }

    @Test
    fun `conversation search payloads use the same accounting`() {
        val payload = buildJsonObject {
            put("type", "search_conversations")
            put("query", "budget")
            put("results", buildJsonArray {
                repeat(30) { i ->
                    add(buildJsonObject {
                        put("title", "Conversation $i")
                        put("messages", buildJsonArray {
                            repeat(3) { m ->
                                add(buildJsonObject {
                                    put("participant", "USER")
                                    put("text", "A message $m discussing the budget in detail.")
                                })
                            }
                        })
                    })
                }
            })
        }.toString()

        assertEquals(
            ToolResultPayload.Count.Known(30),
            ToolResultPayload.countArray(payload, "results"),
        )
        assertEquals(
            ToolResultPayload.Count.Unknown,
            ToolResultPayload.countArray(ResultBudget.clip(payload, 900), "results"),
        )
    }
}
