# Quickstart: Web Service 与第一版管理平台

**Feature**: `009-web-admin`

## 1. 它是什么

`oryxos serve` 起来后：

- `http://localhost:8080/api/v1/*` —— 业务系统用的 REST API（13 个端点）；
- `http://localhost:8080/admin` —— 只读管理平台（8 个页面，数据全部来自上面的 API）；
- `http://localhost:8080/swagger-ui.html` —— 自动生成的接口文档。

管理平台没有独立后端，也没有写操作：它存在的意义是"把 API 的真实状态摆出来"，
同时反向验收"API 是否完备"。

## 2. 起服务

```powershell
# 环境（必须 JDK 21 + 固定 Maven）
$env:JAVA_HOME = "C:\Users\Administrator\.jdks\ms-21.0.12"
$mvn = "E:\software\apache-maven-3.9.9-bin\apache-maven-3.9.9\bin\mvn.cmd"

# 打包（fat JAR）
& $mvn -f g:\code\ORYXOS\pom.xml -DskipTests package

# 启动（先设置模型密钥；不配 provider 也能起，但 /info.canBuildAgents=false）
$env:DEEPSEEK_API_KEY = "sk-..."
java -jar g:\code\ORYXOS\oryxos-boot\target\oryxos-boot-0.1.0-SNAPSHOT.jar serve
```

## 3. 十个端点速查（curl）

```bash
# 建会话
curl -X POST localhost:8080/api/v1/sessions -H 'Content-Type: application/json' \
     -d '{"profile":"default","userId":"u-1"}'

# 发消息（同步返回）
curl -X POST localhost:8080/api/v1/sessions/<id>/messages -H 'Content-Type: application/json' \
     -d '{"content":"今天北京天气怎么样"}'

# 会话列表 / 详情（最近 100 条消息）/ 归档
curl localhost:8080/api/v1/sessions
curl localhost:8080/api/v1/sessions/<id>
curl -X DELETE localhost:8080/api/v1/sessions/<id>

# 无状态调用
curl -X POST localhost:8080/api/v1/agents/default/invoke -H 'Content-Type: application/json' \
     -d '{"message":"用一句话介绍你自己"}'

# 查询
curl localhost:8080/api/v1/profiles
curl localhost:8080/api/v1/profiles/default
curl localhost:8080/api/v1/memory
curl localhost:8080/api/v1/tools
curl localhost:8080/api/v1/providers

# 状态
curl localhost:8080/api/v1/health
curl localhost:8080/api/v1/info
```

## 4. 管理平台逐页核对

| 页面 | 该看到什么 |
|---|---|
| 概览 | Agent / Tool / 活跃会话 / Provider 四个数字；五大核心能力；技术栈 |
| 会话 | 会话表；点一行展开最近 100 条消息 |
| Agent | 每个 Profile 一张卡：描述、provider/model、工具/技能/定时数量 |
| Provider | 名称、base-url、状态（`configured` / `not-assembled`） |
| Tool | 当前注册表中的工具与描述（空注册表就是"暂无数据"） |
| Sandbox 白名单 | 开关状态 + 三类清单**条数**（不显示路径/域名） |
| 长期记忆 | `MEMORY.md` 注入视角文本 + 字符数 |
| 运行状态 | `UP`、版本、Provider 状态、端点清单 |

## 5. 错误语义怎么验

```bash
# 400：超 32KB（用 40000 个 a 试）
curl -i -X POST localhost:8080/api/v1/sessions/<id>/messages \
     -H 'Content-Type: application/json' -d "{\"content\":\"$(printf 'a%.0s' {1..40000})\"}"

# 404：不存在的会话
curl -i -X POST localhost:8080/api/v1/sessions/nope/messages \
     -H 'Content-Type: application/json' -d '{"content":"hi"}'

# 404：不存在的 Agent
curl -i -X POST localhost:8080/api/v1/agents/nope/invoke \
     -H 'Content-Type: application/json' -d '{"message":"hi"}'
```

三个响应都应是 `errorCode` / `message` / `timestamp` 三字段。
500 的验证靠测试（`GlobalExceptionHandlerTest` 断言不含内部串），手工很难安全构造。

## 6. 当前明确不做

- **认证**：内网假设，API 与 `/admin` 同级暴露。上线到不可信网络前必须先补 API Key/JWT。
- **流式响应**（SSE/WebSocket）：一律同步返回。
- **写操作**：不改 Profile、不写记忆、不触发 Webhook —— 那些端点在 29/30 节之后。
- **限流 / RBAC / Prometheus**：扩展阶段。
