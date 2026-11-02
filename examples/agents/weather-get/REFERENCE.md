# weather-get 参考资料

这个文件是**按需读取**的资源，不会每轮注入 prompt。模型需要细节时用 `read_file` 打开它，
所以可以写得长一些，但要有结构。

## 工具

- `get_weather(city?)`：返回 `city / condition / temperatureC / humidity / windKph / observedAt / source`。
  `source` 为 `mock` 表示演示数据（不联网、随机生成）。
- `save_memory(content, scope?)`：写入长期记忆。`scope=core` 常驻上下文，`scope=archival`（默认）可检索。
- `recall_memory(keyword)`：在归档区按关键词检索，核心记忆不需要检索。

## 输出约定

- 一句话结论：城市 + 天气 + 温度。
- 再给建议：穿衣、带伞、是否适合户外。
- 不输出整段 JSON；只挑用户关心的字段。

## 边界

- 演示数据不能当成真实预报，必须标注。
- 不承诺未来天气（工具只返回当前时刻）。
