package com.newoether.agora.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsMcpPageTest {
    @Test
    fun `imports standard remote mcp server configuration`() {
        val servers = parseMcpServers(
            """
            {
              "mcpServers": {
                "search": {
                  "type": "streamable_http",
                  "url": "https://example.com/mcp",
                  "headers": { "X-API-Key": "secret" }
                },
                "legacy": {
                  "type": "sse",
                  "url": "https://example.com/sse"
                },
                "local": {
                  "command": "node",
                  "args": ["server.js"]
                }
              }
            }
            """.trimIndent()
        )

        assertEquals(listOf("search", "legacy"), servers.map { it.name })
        assertEquals("streamable_http", servers[0].transport)
        assertEquals("secret", servers[0].headers["X-API-Key"])
        assertEquals("sse", servers[1].transport)
        assertTrue(servers.all { it.enabled })
    }
}
