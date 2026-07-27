package com.newoether.agora.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelsDevRepositoryTest {

    @Test
    fun `catalog parser detects reasoning and fast mode`() {
        val catalog = """
            {
              "models": {},
              "providers": {
                "openai": {
                  "models": {
                    "gpt-5.4": {
                      "reasoning": true,
                      "experimental": { "modes": { "fast": { "provider": {} } } }
                    },
                    "gpt-4.1": { "reasoning": false }
                  }
                }
              }
            }
        """.trimIndent()

        val parsed = parseModelsDevCapabilities(catalog)

        assertEquals(ModelCapabilities(reasoning = true, fast = true), parsed["*:gpt-5.4"])
        assertEquals(null, parsed["*:gpt-4.1"])
    }

    @Test
    fun `absent tool_call stays permissive so unlisted models keep their tools`() {
        val catalog = """
            {
              "providers": {
                "openai": {
                  "models": {
                    "gpt-5.4": { "reasoning": true }
                  }
                }
              }
            }
        """.trimIndent()

        val parsed = parseModelsDevCapabilities(catalog)

        // No `tool_call` key means "unknown", which must NOT strip tool support.
        assertEquals(true, parsed["*:gpt-5.4"]?.tools)
    }

    @Test
    fun `explicit tool_call false is recorded and survives alias merging`() {
        val catalog = """
            {
              "providers": {
                "a": {
                  "models": {
                    "embed-1": { "tool_call": false, "name": "Embed One" }
                  }
                },
                "b": {
                  "models": {
                    "embed-1": { "reasoning": true }
                  }
                }
              }
            }
        """.trimIndent()

        val parsed = parseModelsDevCapabilities(catalog)

        // Provider "b" lists the same id without tool_call (permissive default). The AND merge
        // must keep the restriction from "a" rather than letting the sibling re-enable tools.
        assertEquals(false, parsed["*:embed-1"]?.tools)
    }

    @Test
    fun `context token limit is parsed for the defer budget`() {
        val catalog = """
            {
              "providers": {
                "openai": {
                  "models": {
                    "gpt-5.4": { "limit": { "context": 200000, "output": 8192 } }
                  }
                }
              }
            }
        """.trimIndent()

        val parsed = parseModelsDevCapabilities(catalog)

        assertEquals(200000, parsed["*:gpt-5.4"]?.contextTokens)
    }

    @Test
    fun `catalog parser indexes display name for normalized API model lookup`() {
        val catalog = """
            {
              "models": {},
              "providers": {
                "openai": {
                  "models": {
                    "catalog-only-id": {
                      "name": "GPT-5.6 Sol",
                      "reasoning": true,
                      "experimental": { "modes": { "fast": {} } }
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val parsed = parseModelsDevCapabilities(catalog)

        assertEquals(
            ModelCapabilities(reasoning = true, fast = true),
            parsed["*:gpt56sol"]
        )
    }

    @Test
    fun `catalog parser exposes capabilities independently of custom Agora provider name`() {
        val catalog = """
            {
              "providers": {
                "openai": {
                  "models": {
                    "gpt-5.6-sol": {
                      "name": "GPT-5.6 Sol",
                      "reasoning": true,
                      "experimental": { "modes": { "fast": {} } }
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val parsed = parseModelsDevCapabilities(catalog)

        assertEquals(
            ModelCapabilities(reasoning = true, fast = true),
            findModelsDevCapabilities(parsed, "gpt-5.6-sol")
        )
    }

    @Test
    fun `explicit provider fast declaration overrides models dev while preserving reasoning`() {
        val catalog = mapOf(
            "*:model" to ModelCapabilities(reasoning = true, fast = true)
        )

        assertEquals(
            ModelCapabilities(reasoning = true, fast = false),
            findModelsDevCapabilities(catalog, "model", providerFast = false)
        )
        assertEquals(
            ModelCapabilities(reasoning = true, fast = true),
            findModelsDevCapabilities(catalog, "model", providerFast = true)
        )
    }
}
