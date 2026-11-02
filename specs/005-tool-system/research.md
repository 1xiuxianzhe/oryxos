# Phase 0 Research: Tool 体系

**Feature**: `005-tool-system` | **Date**: 2026-10-10

本阶段的目标是把 plan 阶段需要拍板的技术问题**用仓库里的事实**回答掉，而不是凭源文档的示意代码推断。
每条结论都附证据来源。

## R1. 既有抽象的真实签名（决定"改什么、不改什么"）

**结论**：抽象层完整，本特性不重写；但有 3 处与源文档不同，必须以仓库为准。

| 项 | 源文档示意 | 仓库真实 | 影响 |
|---|---|---|---|
| 参数 schema 的类型 | `JsonSchema` 对象 | `String`（原始 JSON 文本） | 外部工具适配时需把外部服务的 schema **序列化成 JSON 字符串**，而不是直接赋值 |
| 执行入参的类型 | `JsonNode` | `Map<String, Object>` | 外部工具调用时把该映射**原样传给外部服务** |
| 结果工厂方法 | `success(...)` / `failure(...)` | `ok(String)` / `fail(String, boolean)` | 适配器按后者构造结果 |

**证据**：`oryxos-core/.../tool/OryxTool.java`、`ToolResult.java`；
`ToolRegistry` 提供 `register` / `get` / `all` / `listAll`，**没有** `contains`，也没有 Spring 自动收集。

## R2. 外部工具服务 SDK 的真实能力（本特性最大的未知项）

**结论**：既有依赖**足以覆盖两种连接方式**，无需自实现协议转发，也无需引入新依赖。

用 `javap` 实际反射本地仓库中的该版本产物，得到的确切 API：

```java
// 客户端（同步）
McpSyncClient client = McpClient.sync(transport)
        .requestTimeout(Duration)
        .initializationTimeout(Duration)
        .build();
client.initialize();                          // → InitializeResult
ListToolsResult tools = client.listTools();   // → tools()
CallToolResult r = client.callTool(new CallToolRequest(name, argumentsMap));
client.close();                               // 同步关闭；另有 closeGracefully()
```

```java
// 工具模型
McpSchema.Tool(String name, String description, McpSchema.JsonSchema inputSchema)
McpSchema.CallToolResult(List<McpSchema.Content> content, Boolean isError)
McpSchema.ListToolsResult(List<McpSchema.Tool> tools, String nextCursor)

// 两种传输
new StdioClientTransport(ServerParameters.builder(command).args(...).env(...).build())
new HttpClientSseClientTransport(String baseUri)
```

**证据**：`javap -cp <local-repo>/io/modelcontextprotocol/sdk/mcp/0.8.1/mcp-0.8.1.jar`，
包结构为 `client` / `client.transport` / `spec` / `server` / `util`。

**由此确定的适配规则**：

- 外部工具的 `name` / `description` 直接映射；
- `inputSchema`（结构化对象）**序列化为 JSON 字符串**后作为统一抽象的参数 schema（R1 的类型差异）；
- 调用时把入参映射**原样**放进 `CallToolRequest`；
- 返回的 `content` 列表拼接为文本；`isError` 为真时产出**不可重试**？——**不**：
  按 `spec.md` FR-021，外部调用失败（含服务中途挂掉、超时）标记为**可重试**；
  `isError=true` 属于"外部服务明确报告本次执行失败"，归为可重试（服务仍在，可能是瞬态）。
  工具级参数错误（如入参缺失）则由既有执行器在调用前拦截，标记不可重试。

## R3. 工具授权声明的真实结构（决定过滤怎么读）

**结论**：`Profile.tools` 是**对象列表**，不是字符串列表；判据取其中的名字字段。

```yaml
tools:
  - name: http_get
    description: 发起 HTTP GET 请求
    parameters:
      - name: url
        type: string
        required: true
```

**证据**：`oryxos-core/.../profile/Profile.java`（`List<ToolConfig>`，`ToolConfig` 含
`name`/`description`/`parameters`）；`ProfileLoaderTest` 已有该结构 + 下划线字段名的解析断言。

**决策**：过滤判据是 `ToolConfig.getName()`；`description`/`parameters` 当前**不参与**过滤
（保留为后续"内联工具定义"的扩展点）。
为兼顾手写习惯，**容忍**纯字符串写法（`tools: [http_get, shell]`）作为兼容增强；
若两种形式在解析层无法共存，则以既有对象形式为准并在文档中写明（见 plan D-05）。

## R4. 白名单配置的绑定方式

**结论**：新增一个配置绑定类 + 一个装配处，配置项落在 `application.yaml`；
**空清单 = 全拒**（fail-closed）。

**证据**：既有校验类的构造为
`SandboxChecker(List<String> allowedPaths, List<String> allowedCommands, List<String> allowedDomains)`，
三个清单均为必填；全仓无任何生产者、无任何配置项定义。

**取舍**：默认收紧（A-005）。因此"启动后工具直接可用"这一预期**必须**先配白名单；
`quickstart.md` 会给出最小可用配置，避免使用者拿到"一律被拒"却不知为何。

## R5. 外部服务声明文件的读取方式

**结论**：新增一个独立的读取组件，**不复用** Profile 的解析路径。

**证据**：

- 既有 `ProfileLoader` 负责 Profile 目录下的 YAML，且带"provider 名必须已注册"的校验，
  与外部服务声明无关；
- 工作区声明文件目前**只被创建**（内容为空的列表占位），**没有任何代码读取**；
- `Profile.mcp_servers` 是**配置里**的字段，已有解析测试，语义是"这个 Agent 引用了哪些外部服务"。

**决策**：连接信息的唯一真相来源是**工作区声明文件**；Agent 配置里的字段语义收敛为"引用"
（plan D-07）。两个来源都支持环境变量占位——解析器沿用既有的占位替换能力，
避免外部服务凭证出现明文。

## R6. 失联隔离的实现方式（可测性优先）

**结论**：把"建立连接"抽成一个可注入的端口，测试用替身覆盖，**不需要真实的 MCP 进程或网络**。

**理由**：`spec.md` US3 要求验证"失联只告警、不阻断、其余照常注册"，
这要求测试能精确控制"某个服务连接成功、另一个失败"。
若连接逻辑写死在服务类里，就只能靠起真进程来测，既慢又不稳。

**取舍**：被替换的替身返回……**可用的客户端替身**（列出工具 / 调用工具可控），
因此适配器与注册流程都被真实覆盖，只有"如何建立连接"这一层被替换。

## R7. 重名工具的处理

**结论**：注册表新增"重名拒绝 + 告警"，**先注册者胜出**。

**证据**：既有注册表底层是名字到工具的映射，直接覆盖会让"来源覆盖来源"静默发生；
`spec.md` FR-003 要求确定性 + 可见提示。

**取舍**：内置工具先于外部工具注册，因此外部服务与内置工具撞名时外部工具不可用——
这是刻意的选择（内置能力是地基，不应被外部依赖顶掉），但必须有告警，否则难以排查。

## R8. 测试策略与不引入的新依赖

**结论**：全部新增测试为纯单测，不引入 MockWebServer / WireMock / 真 MCP 进程。

| 被测对象 | 替身策略 |
|---|---|
| 文件 / 命令 / HTTP 内置工具 | 真实临时目录；HTTP 用 JDK `HttpServer` 起本地假服务（沿用 19 节已建的做法）；"越界"用副作用探针断言零发生 |
| 白名单校验 | 直接构造校验器，断言放行与拒绝两侧 |
| 工具授权过滤 | 构造不同声明的 Agent 配置，断言子集**恰好等于**声明 |
| 外部服务连接 | 注入连接端口的替身：一个正常、一个抛连接异常 |
| 外部工具适配 | 用客户端替身返回可控的工具列表与调用结果 |
| 契约三件套 | 参数化遍历注册表，对每个工具断言名字/描述/schema 非空 |

**理由**：与 19 节的既有做法一致（当时也刻意不引入 MockWebServer，改用 JDK `HttpServer`）。
