# 设备信息工具使用示例

## Agent 如何使用这个工具

AI Agent 现在可以通过调用 `get_device_info` 工具来获取设备信息。

### 示例对话 1：查询电池电量

**用户**：我的手机还有多少电？

**Agent 调用**：
```json
{
  "name": "get_device_info",
  "arguments": {}
}
```

**工具返回**：
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
  "device": {...},
  "system": {...}
}
```

**Agent 回复**：你的手机还有 85% 的电量，目前正在充电中，电池健康状况良好。

---

### 示例对话 2：检查设备型号

**用户**：我用的是什么手机？

**Agent 调用**：`get_device_info`

**工具返回**：
```json
{
  "battery": {...},
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

**Agent 回复**：你使用的是 Samsung SM-G998B（Galaxy S21 Ultra），运行 Android 14 系统。

---

### 示例对话 3：智能提醒

**用户**：帮我执行一个耗时的任务

**Agent 思考**：先检查设备状态...

**Agent 调用**：`get_device_info`

**工具返回**：电量 15%，未充电

**Agent 回复**：我注意到你的手机电量只有 15% 且未在充电。这个任务可能需要较长时间，建议你先给手机充电，或者我可以在电量充足时再执行。你想现在继续还是等充电后再做？

---

## 技术细节

### 调用方式
```kotlin
// Agent 通过工具系统调用
executeTool(
    name = "get_device_info",
    arguments = "{}",  // 无需参数
    ctx = generationContext
)
```

### 集成位置
- 工具已注册到 `GenerationManager.toolProviders`
- 始终启用，无需用户配置
- 与其他工具（如 web_search、memory）同级

### 性能考虑
- 读取电池信息非常快速（< 1ms）
- 无网络请求
- 无需权限确认
- 适合频繁调用

## 未来增强

可以考虑添加：
1. **电量变化监控**：通知 Agent 电量低于某个阈值
2. **充电完成通知**：电量充满时通知 Agent
3. **温度预警**：电池温度过高时提醒用户
4. **设备性能建议**：根据设备型号提供针对性建议
