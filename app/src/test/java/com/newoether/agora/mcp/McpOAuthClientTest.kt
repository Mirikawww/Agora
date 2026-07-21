package com.newoether.agora.mcp

import com.newoether.agora.data.McpOAuthState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class McpOAuthClientTest {
    @Test
    fun `canonical resource removes fragment and normalizes origin`() {
        assertEquals(
            "https://example.com/mcp?x=1",
            McpOAuthClient.canonicalResource("https://EXAMPLE.com:443/mcp?x=1#fragment"),
        )
    }

    @Test
    fun `oauth state toString redacts credentials and tokens`() {
        val rendered = McpOAuthState(
            enabled = true,
            clientSecret = "client-secret",
            accessToken = "access-token",
            refreshToken = "refresh-token",
        ).toString()

        assertFalse(rendered.contains("client-secret"))
        assertFalse(rendered.contains("access-token"))
        assertFalse(rendered.contains("refresh-token"))
    }
}
