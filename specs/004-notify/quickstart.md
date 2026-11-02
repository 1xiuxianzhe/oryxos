# Quickstart: Notify 出站通知模块

**Feature**: `004-notify`

## Prerequisites

- JDK 21
- 项目固定 Maven 3.9.9
- 默认测试**不需要**外网与 API key；只有"推到真实群机器人"这一步才需要真实 webhook 地址

## 1. 构建与测试

```powershell
$env:JAVA_HOME="C:\Users\Administrator\.jdks\ms-21.0.12"
& "E:\software\apache-maven-3.9.9-bin\apache-maven-3.9.9\bin\mvn.cmd" test
```

只跑本特性：

```powershell
& "E:\software\apache-maven-3.9.9-bin\apache-maven-3.9.9\bin\mvn.cmd" -pl oryxos-tool -am test
```

## 2. 配置通知渠道

在 `.oryxos/profiles/<name>.yaml` 里增加 `notify_channels` 段。

**注意结构**：`url` 位于嵌套的 `config` 下（与既有 `Profile.NotifyChannelConfig` 一致），
**不是**源文档示例里的平铺写法。

```yaml
name: demo
provider:
  name: deepseek
  model: deepseek-chat
  temperature: 0.7
tools: []
channels:
  - type: cli
notify_channels:
  - type: webhook
    config:
      url: ${TEAM_WEBHOOK_URL}
settings:
  max_iterations: 10
  max_history_turns: 20
```

凭证用 `${ENV_VAR}` 占位，由 ProfileLoader 在加载时从环境变量解析——**不要把地址明文写进 YAML**。

配置多个渠道时，缺省使用**第一个**：

```yaml
notify_channels:
  - type: webhook
    config:
      url: ${TEAM_WEBHOOK_URL}      # 缺省命中这个
  - type: webhook
    config:
      url: ${OPS_WEBHOOK_URL}
```

## 3. 运行

`.oryxos/` 不存在时先初始化，然后启动对话：

```powershell
oryxos init                       # 或 java -jar oryxos-boot-*.jar init
$env:TEAM_WEBHOOK_URL="<群机器人 webhook 地址>"
$env:DEEPSEEK_API_KEY="<模型 key>"
oryxos chat --profile demo
```

对话里直接验证：

```text
> 把"测试消息"推送一下
<Agent 调用 notify(content="测试消息")>
已推送
```

Agent 不需要人工指定渠道——它只传 `content`，渠道由 Profile 的 `notify_channels` 决定。

## 4. 与定时场景的关系

定时模块（后续特性）到点触发 Agent 跑完一轮后，结果就是通过这条路送出去的：

```text
用户：每天早上帮我看看天气，穿搭建议直接发到我们群里
Agent：好的（之后每天到点自动查天气 → 调 notify 推送到配置好的群）
```

本特性只交付"送出去"的能力，**不交付定时触发**。

## 5. 人工验收

自动化 harness 全绿后，剩下三步只能人工确认。

### ⚠️ 请求体格式：通用 webhook ≠ IM 官方机器人

本特性交付的是**通用 webhook**：POST `{"content": "<文本>"}`。而三家官方群机器人要求的结构**都不一样**：

| 渠道 | 必需请求体 |
|---|---|
| 企业微信 | `{"msgtype":"text","text":{"content":"..."}}` |
| 钉钉 | `{"msgtype":"text","text":{"content":"..."}}` |
| 飞书 | `{"msg_type":"text","content":{"text":"..."}}` |

因此**直接填企业微信/钉钉/飞书的官方群机器人地址是收不到消息的**。而且这三家在业务出错时
**仍返回 HTTP 200**，错误码放在响应体里（企业微信 `errcode` / 钉钉 `errcode` / 飞书 `code`），
所以当前"2xx 即成功"的判定还会**误报"已推送"**。

要接官方机器人，需要二者之一：

- 在机器人前面挂一层转发，把 `{"content":...}` 翻译成厂商格式（serverless / n8n / 自建中间层）；
- 或新增厂商专用适配器（属扩展阶段，源文档也把签名算法与 AccessToken 刷新划到扩展阶段）。

### 验收步骤

| # | 步骤 | 判定 |
|---|---|---|
| 1 | 配一个**接受 `{"content":"..."}` 的通用端点**（自建接收器 / n8n / 中间层），让 Agent 推一条消息 | 接收端**真的收到**该 JSON，且 `content` 字段与所说文本一致 |
| 2 | 故意不配 `notify_channels`，再让 Agent 推送 | Agent 明确回复失败原因，**不会**声称"已推送" |
| 3 | 查看 Profile 详情 | 输出里**看不到** webhook 地址 |
| 4 |（可选）如果配的是 IM 官方机器人 | 观察「收不到 + 却报已推送」这个已知行为，据此判断是否需要接中间层或专用适配器 |

**验收结果**：第 1、2、3 项已由使用方在真实环境执行并确认通过（2026-10-10）；
第 4 项为可选项，未执行（核心阶段不接 IM 专用 API，见第 7 节）。

## 6. 接口中立性自查（思维练习，无法自动化）

把 `WebhookNotifyAdapter` 换成"企业微信官方 SDK 实现"，问：

- `NotifyChannelAdapter.send(NotifyTarget, String)` 这个签名需要改吗？→ **不应该需要**。
- 调用方 `NotifyTool` 需要改吗？→ **不应该需要**，只是把注入的实现类换掉。

如果任何一处需要改，说明渠道细节泄漏进了抽象，本特性就没达标（SC-002）。

## 7. 已知边界（避免误读）

| 项 | 本特性状态 |
|---|---|
| 通用 webhook | ✅ 已支持 |
| 企业微信/飞书/钉钉**专用** API（签名、AccessToken） | ❌ 不支持，报"不支持的渠道类型" |
| 域名白名单 | ⚠️ **占位**：调用点已就位且位于发送之前；校验器未装配时放行并输出 WARN。统一 Sandbox 设计属后续特性 |
| 定时触发 | ❌ 不在本特性 |
| 推送重试编排 / 降级 | ❌ 不在本特性（核心阶段禁止 fallback/hedge racing） |
| 其它内置工具（File/Shell/Http）接入注册表 | ❌ 既有缺口，不在本特性 |
