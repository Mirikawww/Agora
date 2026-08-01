package com.newoether.agora.tool

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 提供设备信息工具，允许 Agent 查询设备的基本信息。
 *
 * 当前支持：
 * - 电池电量和充电状态
 * - 设备型号和制造商
 */
class DeviceInfoToolProvider(
    private val context: Context
) : ToolProvider {

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        return listOf(
            ToolDefinition(
                function = ToolFunction(
                    name = "get_device_info",
                    description = "获取设备信息，包括电池电量、充电状态、设备型号和制造商。这是一个始终可用的工具，不需要任何参数。",
                    parameters = ToolParameters(
                        properties = emptyMap(),
                        required = emptyList()
                    )
                )
            )
        )
    }

    override fun handles(name: String): Boolean = name == "get_device_info"

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (name != "get_device_info") {
            return buildJsonObject {
                put("error", "unknown_tool")
            }.toString()
        }

        return try {
            val batteryInfo = getBatteryInfo()
            val deviceInfo = getDeviceInfo()

            buildJsonObject {
                put("type", "device_info")

                // 电池信息
                put("battery", buildJsonObject {
                    put("level", batteryInfo.level)
                    put("scale", batteryInfo.scale)
                    put("percentage", batteryInfo.percentage)
                    put("is_charging", batteryInfo.isCharging)
                    put("status", batteryInfo.status)
                    put("health", batteryInfo.health)
                    if (batteryInfo.temperature != null) {
                        put("temperature_celsius", batteryInfo.temperature)
                    }
                })

                // 设备信息
                put("device", buildJsonObject {
                    put("manufacturer", deviceInfo.manufacturer)
                    put("model", deviceInfo.model)
                    put("brand", deviceInfo.brand)
                    put("device", deviceInfo.device)
                    put("display_name", deviceInfo.displayName)
                })

                // 系统信息
                put("system", buildJsonObject {
                    put("android_version", deviceInfo.androidVersion)
                    put("sdk_int", deviceInfo.sdkInt)
                })
            }.toString()
        } catch (e: Exception) {
            buildJsonObject {
                put("error", "execution_error")
                put("message", e.message ?: "未知错误")
            }.toString()
        }
    }

    private data class BatteryInfo(
        val level: Int,
        val scale: Int,
        val percentage: Int,
        val isCharging: Boolean,
        val status: String,
        val health: String,
        val temperature: Float?
    )

    private data class DeviceInfo(
        val manufacturer: String,
        val model: String,
        val brand: String,
        val device: String,
        val displayName: String,
        val androidVersion: String,
        val sdkInt: Int
    )

    private fun getBatteryInfo(): BatteryInfo {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

        // 获取电池状态
        val batteryStatus: Intent? = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percentage = if (level >= 0 && scale > 0) {
            (level * 100 / scale)
        } else {
            -1
        }

        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        val statusText = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
            else -> "unknown"
        }

        val health = batteryStatus?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        val healthText = when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "cold"
            else -> "unknown"
        }

        val temperature = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.let {
            if (it > 0) it / 10f else null
        }

        return BatteryInfo(
            level = level,
            scale = scale,
            percentage = percentage,
            isCharging = isCharging,
            status = statusText,
            health = healthText,
            temperature = temperature
        )
    }

    private fun getDeviceInfo(): DeviceInfo {
        return DeviceInfo(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            brand = Build.BRAND,
            device = Build.DEVICE,
            displayName = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT
        )
    }
}
