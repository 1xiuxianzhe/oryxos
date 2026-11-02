# Quickstart: Tool 体系

**Feature**: `005-tool-system`

## Prerequisites

- JDK 21
- 项目固定 Maven 3.9.9
- 默认测试**不需要**外网、不需要 API key、不需要真实的工具服务进程

## 1. 构建与测试

```powershell
$env:JAVA_HOME="C:\Users\Administrator\.jdks\ms-21.0.12"
& "E:\software\apache-maven-3.9.9-bin\apache-maven-3.9.9\bin\mvn.cmd" test
```

只跑本特性涉及的模块：

```powershell
& "E:\software\apache-maven-3.9.9-bin\apache-maven-3.9.9\bin\mvn.cmd" -pl oryxos-core,oryxos-tool -am test
```

## 2. 配置白名单（必读，不配则工具全不可用）

**这是最容易踩的坑**：白名单是**默认拒绝**的。新建工作区如果不在配置里给出白名单，
六个内置工具会**全部拒绝**，且原因只有一个"不在白名单内"。

在 `application.yaml` 增加（按需裁剪）：

```yaml
oryxos:
  sandbox:
    allowed-paths:
      - ${user.dir}                 # 本项目目录下的文件才允许读写
    allowed-commands:
      - echo                        # 只允许这些命令（取命令首个词精确匹配）
      - git
    allowed-domains:
      - api.deepseek.com
      - "*.weather.com"             # 支持通配
```

三条规则：

- **清单为空 = 该维度一律拒绝**，不是一律放行。
- 路径按**解析后的真实路径**判定，`..` 与符号链接绕不过去。
- 配置里不要写明文凭证，用 `${ENV_VAR}` 占位。

### 开发调试：想省掉配置，就把总开关关掉

改配置太麻烦时，可以一键关掉整个校验：

```yaml
oryxos:
  sandbox:
    enabled: false          # 三个维度全部放行
```

或者不改文件，用 JVM 参数：

```powershell
& "$env:JAVA_HOME\bin\java.exe" '-Doryxos.sandbox.enabled=false' -jar $jar chat --profile default
```

关闭后启动日志会打出醒目告警，确认自己确实跑在"无校验"状态：

```text
================================================================
[启动] 出站白名单【已关闭】(oryxos.sandbox.enabled=false)
[启动] 文件读写、命令执行、HTTP 请求不做任何校验，一律放行。
[启动] 该状态仅适用于本地开发与调试，切勿带到生产环境。
================================================================
```

⚠️ **三条纪律**：

1. **默认值是 `true`**。刻意如此——漏配导致"边界敞着"的代价，远高于开发时多配几行。
2. **它是唯一的"关闭安全边界"入口**，只服务于本地开发与调试，不是将来某个特性的预留。
3. **别把开发配置带上线**。生产环境的配置里建议**显式写死 `enabled: true`**，
   而不是依赖默认值——这样即使有人复制了开发配置，改动也是可见的。
   另外注意：**关掉开关并不会跳过配置格式校验**，空白条目照样启动即报错，
   避免"上线才打开开关"的那一刻才发现配置本来是坏的。

## 3. 给 Agent 声明可用工具

Agent 配置（`.oryxos/profiles/<name>.yaml`）：

```yaml
tools:
  - name: read_file
  - name: list_dir
  - name: http_get
```

- 声明的工具集合**恰好**是该 Agent 能用的那批。
- **不写 `tools`（或写空列表）= 不限制**，即该 Agent 可用注册表中的全部工具。
  这是刻意的兼容选择：默认模板就是 `tools: []`，若把空当成"无工具"，
  升级后默认 Agent 会立刻失去全部工具能力。
- 写了一个不存在的工具名 → 会给出提示，而不是静默忽略、也不是回退成全量。

也可以写简写形式（两者等价）：

```yaml
tools: [read_file, list_dir]
```

## 4. 接入外部工具服务

在**工作区**声明文件里登记（不在 Agent 配置里写连接信息）：

```yaml
servers:
  - name: weather
    transport: stdio
    command: npx
    args: ["-y", "@acme/weather-mcp"]
    env:
      ACME_API_KEY: ${ACME_API_KEY}

  - name: ops
    transport: sse
    url: ${OPS_MCP_URL}
```

### 想先验证一下？用官方参考实现

`@modelcontextprotocol/server-everything` 是官方为"给 MCP 客户端做联调"而做的演示服务：
自带 `echo` / `get-sum` 等工具，不需要参数、不产生副作用，工具名与内置的 7 个也不冲突。

**首次运行 npx 需要下载包（实测约 21 秒），建议先手动预热一次：**

```powershell
npx -y @modelcontextprotocol/server-everything
# 看到 "Starting default (STDIO) server..." 就说明包已就绪，Ctrl+C 退出即可
```

然后写进工作区声明文件（Windows 下 `npx` 是 `npx.cmd`）：

```yaml
servers:
  - name: everything
    transport: stdio
    command: npx.cmd
    args: ["-y", "@modelcontextprotocol/server-everything"]
```

启动后应当看到（实测数据）：

```text
[Mcp] 已读取外部服务声明 1 条: [everything]
[Mcp] 已接入外部工具服务: name=everything, 工具 13 个, 耗时 1814ms, 注册表共 20 个
[Mcp] 外部工具服务接入完成: 成功 1 / 共 1
```

注册表从 7 个涨到 **20 个**（7 内置 + 13 外部），`GET /api/v1/tools` 也会列出这些外部工具。
预热后接入约 1.3 秒。

**注意事项**：

- 连接是**串行**的，每个失败的服务要等满握手超时（30 秒）。
  一个拼错的命令会让启动白白多花 30 秒——这也是上面建议先预热的原因。
- 失败告警里拿到的是"握手超时"，**不是真实原因**。命令不可执行的真实原因
  （`Failed to start process with command`）在日志的 ERROR 行里，按告警附带的提示去搜即可。

### 通用行为
- **某个服务连不上不会拖垮启动**：只产生一条告警，它的工具不出现，其它工具照常可用。
- 如果外部服务报出的工具名和内建工具**撞名**，内建工具胜出，外部那个被拒绝并告警。

Agent 侧按名字引用要用的服务；不写 = 不限制。

## 5. 运行与确认

```powershell
oryxos init
oryxos tool list            # 列出注册表里的真实工具
oryxos chat --profile default
```

> **注意 `tool list` 是重命令**：工具清单的真相来源是注册表，而注册表只在运行时里存在
> （内置工具靠装配登记、外部工具靠启动期连接），所以它需要完整运行时，也就需要模型凭证。
> 这是刻意取舍：宁可让它慢一点、需要运行时，也不退回"列工作区目录"这种与实际状态无关的答案。

对话里直接验证：

```text
> 看一下当前目录有哪些文件
<Agent 调用 list_dir(path=".")>
...

> 读一下 README.md
<Agent 调用 read_file(path="README.md")>
...

> 读一下 C:\Windows\win.ini
<Agent 调用 read_file(...) → 失败：文件路径不在白名单内>
```

HTTP 入口：

```powershell
curl.exe -s http://localhost:8080/api/v1/tools
```

两个入口返回的都是**注册表的真实内容**，不再有硬编码或目录列举。

## 6. Plugin Tool 三档接入（怎么选）

```text
方式一（零代码）：在 .oryxos/skills/ 写一份 SKILL.md，Agent 自己组织调用已接入的工具
方式二（轻代码）：在工作区声明文件里登记你自己的外部工具服务，启动时自动接入
方式三（重代码）：写一个实现统一工具接口的 Java 类，在装配处登记
```

选择标准：**能用方式一就不用方式二，能用方式二就不用方式三。**

## 7. 人工验收

自动化 harness 全绿后，这几步只能在真实环境确认：

| # | 步骤 | 判定 |
|---|---|---|
| 1 | 按第 2 节配好白名单，让 Agent 读一个白名单内的文件 | 真的读到内容 |
| 2 | 让 Agent 读一个白名单外的文件 | 明确失败并说明路径不在白名单，文件未被读取 |
| 3 | 让 Agent 跑一条白名单外的命令 | 明确失败，且没有进程被启动 |
| 4 | 让 Agent 请求一个白名单外的域名 | 明确失败，且没有请求发出 |
| 5 | 给 Agent 声明 2 个工具，观察它是否只看到这 2 个 | 只出现声明的 2 个 |
| 6 | 登记一个真实的外部工具服务，启动后看工具清单 | 该服务的工具出现，Agent 能调通 |
| 7 | 把外部服务地址改错再启动 | 应用**正常启动**，只有一条告警，其余工具可用 |

## 8. 已知边界（避免误读）

| 项 | 本特性状态 |
|---|---|
| 文件 / 命令 / HTTP 内置工具 | ✅ 可用（需配白名单） |
| 按 Agent 限定可用工具 | ✅ 可用 |
| 外部工具服务（本地子进程 / 远程） | ✅ 可用 |
| 工具清单真实可查 | ✅ 可用 |
| **Memory 的两个工具** | ❌ 属 Memory 特性，本特性不接入 |
| 白名单的**统一沙箱抽象**（动作对象、强制启用、容器级隔离） | ❌ 后续 Sandbox 特性 |
| 命令工具的平台假设 | ⚠️ 固定走 Windows 命令解释器，**不是** `bash`；与源文档不一致，属既有独立缺口 |
| Tool Policy（allow/deny 细粒度规则）、工具按需加载 | ❌ 扩展阶段 |
| 外部服务的运行期增删、工具列表变更通知 | ❌ 不检测；启动时确定一次 |
| 一次响应内多工具并行调用 | ❌ 核心阶段不做 |
