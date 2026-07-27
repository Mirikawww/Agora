package com.newoether.agora.api.gemini

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiToolSchemaTest {
    private val json = Json { explicitNulls = false }

    @Test
    fun `exact MCP schema uses parametersJsonSchema`() {
        val exact = buildJsonObject {
            put("type", "object")
            put("\$defs", buildJsonObject {
                put("mode", buildJsonObject {
                    put("oneOf", buildJsonArray {
                        add(buildJsonObject { put("type", "string") })
                        add(buildJsonObject { put("type", "integer") })
                    })
                })
            })
        }
        val declaration = ToolDefinition(
            function = ToolFunction(
                name = "mcp_exact",
                description = "Exact MCP tool.",
                parameters = ToolParameters(properties = emptyMap(), rawSchema = exact),
            ),
        ).toGeminiFunctionDeclaration()

        val encoded = json.parseToJsonElement(
            json.encodeToString(GeminiFunctionDeclaration.serializer(), declaration),
        ).jsonObject

        assertTrue(encoded.containsKey("parametersJsonSchema"))
        assertFalse(encoded.containsKey("parameters"))
    }

    @Test
    fun `simple Agora schema keeps Gemini OpenAPI parameters field`() {
        val declaration = ToolDefinition(
            function = ToolFunction(
                name = "simple",
                description = "Simple tool.",
                parameters = ToolParameters(
                    properties = mapOf("query" to ToolProperty("string", "Query.")),
                ),
            ),
        ).toGeminiFunctionDeclaration()

        val encoded = json.parseToJsonElement(
            json.encodeToString(GeminiFunctionDeclaration.serializer(), declaration),
        ).jsonObject

        assertTrue(encoded.containsKey("parameters"))
        assertFalse(encoded.containsKey("parametersJsonSchema"))
    }
}
