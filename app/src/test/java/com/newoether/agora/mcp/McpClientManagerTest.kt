package com.newoether.agora.mcp

import com.newoether.agora.api.ToolParameters
import com.newoether.agora.data.McpServerConfig
import com.newoether.agora.data.McpOAuthState
import com.newoether.agora.data.McpToolConfig
import com.newoether.agora.ui.settings.parseHeaders
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class McpClientManagerTest {
    @Test
    fun `tool metadata does not change connection key`() {
        val server = McpServerConfig(name = "demo", url = "https://example.com/mcp")
        val withTools = server.copy(
            tools = listOf(McpToolConfig(name = "search", enabled = false, confirmToolCall = true))
        )

        assertEquals(server.connectionKey(), withTools.connectionKey())
    }

    @Test
    fun `resolved authorization changes connection key but overridden oauth token does not`() {
        val server = McpServerConfig(name = "demo", url = "https://example.com/mcp")
        val oauth = server.copy(oauth = McpOAuthState(enabled = true, accessToken = "oauth-one"))
        assertNotEquals(server.connectionKey(), oauth.connectionKey())

        val manual = oauth.copy(headers = mapOf("Authorization" to "Bearer manual"))
        val rotatedOauth = manual.copy(oauth = manual.oauth?.copy(accessToken = "oauth-two"))
        assertEquals(manual.connectionKey(), rotatedOauth.connectionKey())
    }

    @Test
    fun `raw MCP JSON schema is preserved exactly`() {
        val schema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("mode", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add(JsonPrimitive("safe")); add(JsonPrimitive("fast")) })
                })
                put("value", buildJsonObject {
                    put("oneOf", buildJsonArray {
                        add(buildJsonObject { put("type", "string") })
                        add(buildJsonObject { put("type", "integer") })
                    })
                })
            })
            put("additionalProperties", false)
        }

        val encoded = Json.encodeToString(ToolParameters(properties = emptyMap(), rawSchema = schema))

        assertEquals(schema, Json.parseToJsonElement(encoded).jsonObject)
    }

    @Test
    fun `exposed tool names are stable valid and bounded`() {
        val server = McpServerConfig(
            id = "12345678-abcd",
            name = "My Production Server!",
            url = "https://example.com/mcp",
        )

        val first = McpClientManager.exposedToolName(server, "github/create pull request with a very long name")
        val second = McpClientManager.exposedToolName(server, "github/create pull request with a very long name")

        assertEquals(first, second)
        assertTrue(first.length <= 64)
        assertTrue(first.matches(Regex("^[a-z0-9_-]+$")))
    }

    @Test
    fun `custom header editor ignores malformed lines and keeps colons in values`() {
        assertEquals(
            mapOf("X-Team" to "Agora", "Authorization" to "Custom: token"),
            parseHeaders("X-Team: Agora\nmalformed\nAuthorization: Custom: token"),
        )
    }

    @Test
    fun `streamable HTTP handshake and paginated discovery work end to end`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/mcp") { exchange -> handleMcp(exchange) }
        server.start()
        val manager = McpClientManager()
        try {
            val status = manager.test(
                McpServerConfig(
                    name = "Test",
                    url = "http://127.0.0.1:${server.address.port}/mcp",
                    transport = "streamable_http",
                )
            )

            assertEquals("mock-mcp 1.0", status.implementation)
            assertEquals(2, status.toolCount)
            assertEquals(1, status.resourceCount)
            assertEquals(1, status.promptCount)
        } finally {
            manager.close()
            server.stop(0)
        }
    }

    private fun handleMcp(exchange: HttpExchange) {
        if (exchange.requestMethod == "GET") {
            exchange.sendResponseHeaders(405, -1)
            exchange.close()
            return
        }
        val request = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
        val method = request["method"]?.jsonPrimitive?.content
        if (request["id"] == null) {
            exchange.sendResponseHeaders(202, -1)
            exchange.close()
            return
        }
        val id = request.getValue("id")
        val params = request["params"] as? kotlinx.serialization.json.JsonObject
        val cursor = params?.get("cursor")?.jsonPrimitive?.content
        val result = when (method) {
            "initialize" -> buildJsonObject {
                put("protocolVersion", "2025-11-25")
                put("capabilities", buildJsonObject {
                    put("tools", buildJsonObject { put("listChanged", false) })
                    put("resources", buildJsonObject { put("listChanged", false) })
                    put("prompts", buildJsonObject { put("listChanged", false) })
                })
                put("serverInfo", buildJsonObject { put("name", "mock-mcp"); put("version", "1.0") })
            }
            "tools/list" -> buildJsonObject {
                put("tools", buildJsonArray {
                    add(tool(if (cursor == null) "first" else "second"))
                })
                if (cursor == null) put("nextCursor", "page-2")
            }
            "resources/list" -> buildJsonObject {
                put("resources", buildJsonArray {
                    add(buildJsonObject { put("uri", "test://one"); put("name", "one") })
                })
            }
            "prompts/list" -> buildJsonObject {
                put("prompts", buildJsonArray { add(buildJsonObject { put("name", "review") }) })
            }
            else -> buildJsonObject {}
        }
        val response = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }.toString().toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.responseHeaders.add("Mcp-Session-Id", "test-session")
        exchange.sendResponseHeaders(200, response.size.toLong())
        exchange.responseBody.use { it.write(response) }
    }

    private fun tool(name: String) = buildJsonObject {
        put("name", name)
        put("description", "Test tool")
        put("inputSchema", buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {})
        })
    }
}
