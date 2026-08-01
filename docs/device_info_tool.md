# Device Info Tool 设备信息工具

## 概述

`DeviceInfoToolProvider` 是一个新增的工具提供者，允许 AI Agent 查询 Android 设备的基本信息，包括：

- **电池信息**：电量百分比、充电状态、电池健康度、温度等
- **设备信息**：制造商、型号、品牌等
- **系统信息**：Android 版本、SDK 版本等

## 功能特点

- ✅ **始终可用**：无需配置，始终启用
- ✅ **无需参数**：调用时不需要提供任何参数
- ✅ **结构化输出**：返回格式化的 JSON 数据
- ✅ **全面的电池信息**：包括电量、充电状态、健康度、温度等
- ✅ **完整的设备标识**：制造商、型号、品牌等信息

## 工具定义

### `get_device_info`

获取设备信息，包括电池电量、充电状态、设备型号和制造商。

**参数**：无需参数

**返回值示例**：

```json
{
  "type": "device_info",
  "battery": {
    "level": 85,
    "scale": 100,
    "percentage": 85,
    "is_charging": true,
    "status": "charging",
    "health": "good",
    "temperature_celsius": 28.5
  },
  "device": {
    "manufacturer": "Samsung",
    "model": "SM-G998B",
    "brand": "samsung",
    "device": "p3s",
    "display_name": "Samsung SM-G998B"
  },
  "system": {
    "android_version": "14",
    "sdk_int": 34
  }
}
```

## 电池状态说明

### `status` 字段可能的值：
- `charging`: 正在充电
- `discharging`: 正在放电
- `full`: 电量充满
- `not_charging`: 未充电
- `unknown`: 未知状态

### `health` 字段可能的值：
- `good`: 健康良好
- `overheat`: 过热
- `dead`: 电池损坏
- `over_voltage`: 过压
- `cold`: 温度过低
- `unknown`: 未知状态

## 使用场景

1. **电量监控**：Agent 可以主动查询电量，在电量低时提醒用户
2. **设备诊断**：帮助用户了解设备型号和系统版本
3. **个性化响应**：根据设备信息提供针对性的建议
4. **充电状态感知**：在执行耗电任务前检查充电状态

## 实现细节

### 文件位置
- **工具提供者**：`app/src/main/java/com/newoether/agora/tool/DeviceInfoToolProvider.kt`
- **测试文件**：`app/src/test/java/com/newoether/agora/tool/DeviceInfoToolProviderTest.kt`

### 集成点
在 `GenerationManager` 中注册：
```kotlin
private val deviceInfoToolProvider = DeviceInfoToolProvider(context.applicationContext)
```

添加到工具提供者列表：
```kotlin
private val toolProviders: List<ToolProvider> = listOf(
    // ... 其他工具 ...
    deviceInfoToolProvider,
)
```

## 权限需求

无需额外权限。使用的是 Android 系统公开的 API：
- `BatteryManager` - 获取电池信息
- `Build` 类 - 获取设备信息

## 测试

运行单元测试：
```bash
./gradlew test --tests DeviceInfoToolProviderTest
```

## 未来扩展可能

1. **网络信息**：WiFi 状态、网络类型、信号强度
2. **存储信息**：可用空间、总空间
3. **内存信息**：可用内存、总内存
4. **传感器信息**：陀螺仪、加速度计等传感器状态
5. **屏幕信息**：分辨率、DPI、亮度等

## 注意事项

- 温度值单位为摄氏度（从原始值除以 10）
- 某些设备可能不提供温度信息（返回 null）
- `percentage` 是计算出来的值（level * 100 / scale）
