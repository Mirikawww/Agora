package com.newoether.agora.tool

import android.content.Context
import com.newoether.agora.viewmodel.GenerationContext
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class DeviceInfoToolProviderTest {

    private val context = mockk<Context>(relaxed = true) {
        every { applicationContext } returns this
    }

    private val provider = DeviceInfoToolProvider(context)

    private val ctx = GenerationContext()

    @Test
    fun definitions_returnsGetDeviceInfoTool() {
        val defs = provider.definitions(ctx)
        assertEquals(1, defs.size)
        assertEquals("get_device_info", defs[0].function.name)
        assertTrue(defs[0].function.description.contains("电池"))
        assertTrue(defs[0].function.description.contains("设备"))
    }

    @Test
    fun handles_recognizesGetDeviceInfo() {
        assertTrue(provider.handles("get_device_info"))
        assertFalse(provider.handles("unknown_tool"))
    }

    @Test
    fun execute_withUnknownTool_returnsError() = runTest {
        val result = provider.execute("unknown_tool", "{}", ctx)
        val json = Json.parseToJsonElement(result).jsonObject
        assertEquals("unknown_tool", json["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun execute_getDeviceInfo_returnsStructuredData() = runTest {
        val result = provider.execute("get_device_info", "{}", ctx)
        val json = Json.parseToJsonElement(result).jsonObject

        // 验证基本结构
        assertEquals("device_info", json["type"]?.jsonPrimitive?.content)
        assertNotNull(json["battery"])
        assertNotNull(json["device"])
        assertNotNull(json["system"])

        // 验证电池信息结构
        val battery = json["battery"]?.jsonObject
        assertNotNull(battery?.get("level"))
        assertNotNull(battery?.get("percentage"))
        assertNotNull(battery?.get("is_charging"))
        assertNotNull(battery?.get("status"))

        // 验证设备信息结构
        val device = json["device"]?.jsonObject
        assertNotNull(device?.get("manufacturer"))
        assertNotNull(device?.get("model"))
        assertNotNull(device?.get("display_name"))

        // 验证系统信息结构
        val system = json["system"]?.jsonObject
        assertNotNull(system?.get("android_version"))
        assertNotNull(system?.get("sdk_int"))
    }
}
