package com.newoether.agora.api

import com.newoether.agora.api.anthropic.AnthropicProvider
import com.newoether.agora.api.gemini.GeminiProvider
import com.newoether.agora.api.ollama.OllamaProvider
import com.newoether.agora.api.openai.OpenAiProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderFunctionToolTransportTest {
    @Test
    fun `provider family transport is intersected with model catalog support`() {
        assertFalse(supportsFunctionTools(true, FunctionToolTransport.NONE))
        assertFalse(supportsFunctionTools(false, FunctionToolTransport.NATIVE_CHOICE))
        assertTrue(supportsFunctionTools(true, FunctionToolTransport.NATIVE_AUTO))
    }

    @Test
    fun `remote families declare choice capability while Ollama is auto only`() {
        assertEquals(FunctionToolTransport.NATIVE_CHOICE, OpenAiProvider().functionToolTransport)
        assertEquals(FunctionToolTransport.NATIVE_CHOICE, AnthropicProvider().functionToolTransport)
        assertEquals(FunctionToolTransport.NATIVE_CHOICE, GeminiProvider().functionToolTransport)
        assertEquals(FunctionToolTransport.NATIVE_AUTO, OllamaProvider().functionToolTransport)
    }
}
