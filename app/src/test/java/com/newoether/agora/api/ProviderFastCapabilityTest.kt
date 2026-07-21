package com.newoether.agora.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderFastCapabilityTest {
    private val json = Json

    private fun parse(raw: String) = explicitFastSupport(json.parseToJsonElement(raw).jsonObject)

    @Test
    fun `reads explicit provider fast declarations`() {
        assertEquals(true, parse("""{"fast":true}"""))
        assertEquals(false, parse("""{"supports_fast":false}"""))
        assertEquals(true, parse("""{"capabilities":{"fast":{"supported":true}}}"""))
        assertEquals(true, parse("""{"capabilities":{"fast":{}}}"""))
        assertEquals(false, parse("""{"features":{"fast":"unsupported"}}"""))
        assertEquals(true, parse("""{"experimental":{"modes":{"fast":{}}}}"""))
        assertEquals(true, parse("""{"supported_parameters":["temperature","service_tier"]}"""))
    }

    @Test
    fun `missing fast declaration remains unknown`() {
        assertEquals(null, parse("""{"capabilities":["completion","thinking"]}"""))
        assertEquals(null, parse("""{"supported_parameters":["temperature"]}"""))
        assertEquals(null, parse("""{"id":"model-with-no-feature-metadata"}"""))
    }
}
