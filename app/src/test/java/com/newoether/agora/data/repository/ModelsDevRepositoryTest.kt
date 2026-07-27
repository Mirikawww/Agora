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
    fun `tool restrictions stay scoped to the provider that declared them`() {
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

        assertEquals(false, parsed["a:embed-1"]?.tools)
        assertEquals(true, parsed["b:embed-1"]?.tools)
        assertEquals(
            false,
            findModelsDevCapabilities(parsed, "embed-1", providerName = "a").tools,
        )
        assertEquals(
            true,
            findModelsDevCapabilities(parsed, "embed-1", providerName = "b").tools,
        )
        // An unknown/custom relay must not inherit an unrelated provider's false restriction.
        assertEquals(
            true,
            findModelsDevCapabilities(parsed, "embed-1", providerName = "custom relay").tools,
        )
    }

    @Test
    fun `single provider false never leaks through provider agnostic fallback`() {
        val parsed = parseModelsDevCapabilities(
            """
            {
              "providers": {
                "embedding_vendor": {
                  "models": {
                    "shared-model-name": { "tool_call": false }
                  }
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals(false, parsed["embeddingvendor:shared-model-name"]?.tools)
        assertEquals(true, parsed["*:shared-model-name"]?.tools)
        assertEquals(
            true,
            findModelsDevCapabilities(
                parsed,
                "shared-model-name",
                providerName = "unrelated custom relay",
            ).tools,
        )
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
