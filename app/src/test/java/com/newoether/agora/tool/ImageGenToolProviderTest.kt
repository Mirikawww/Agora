package com.newoether.agora.tool

import android.app.Application
import com.newoether.agora.viewmodel.GenerationContext
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenToolProviderTest {

    private val provider = ImageGenToolProvider(mockk<Application>(relaxed = true))

    @Test
    fun `draining one generation never consumes another generation's images`() {
        val generationA = GenerationContext(capabilityRequestId = "generation-a")
        val generationB = GenerationContext(capabilityRequestId = "generation-b")

        assertTrue(provider.recordGeneratedImage(generationA, "/images/a-1.jpg"))
        assertTrue(provider.recordGeneratedImage(generationB, "/images/b-1.jpg"))
        assertTrue(provider.recordGeneratedImage(generationA, "/images/a-2.jpg"))

        assertEquals(
            listOf("/images/a-1.jpg", "/images/a-2.jpg"),
            provider.drainImages("generation-a"),
        )
        assertEquals(
            listOf("/images/b-1.jpg"),
            provider.drainImages("generation-b"),
        )
    }
}
