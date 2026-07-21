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
