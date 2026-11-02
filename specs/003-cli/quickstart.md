# Quickstart: CLI 命令行入口与会话管理

## Prerequisites

- JDK 21
- 项目固定 Maven 3.9.9
- 需要真实模型调用时设置对应 Provider API key；默认测试不依赖网络或 API key

## Build and Test

在 PowerShell 中使用项目固定环境：

```powershell
$env:JAVA_HOME="C:\Users\Administrator\.jdks\ms-21.0.12"
& "E:\software\apache-maven-3.9.9-bin\apache-maven-3.9.9\bin\mvn.cmd" test
```

## Windows 终端中文编码（必读）

Windows 上「控制台代码页」和「系统 ANSI 代码页」是两套独立的东西，而 Java 的 stdio 编码
默认取 **ANSI 代码页**，不一定等于终端的代码页：

```text
控制台代码页        : 936 (GBK) 或 65001 (UTF-8)
Java stdout.encoding: 取自系统 ANSI 代码页（中文系统通常 936 / GBK）
Logback 控制台日志   : 按字节直接写 System.out，内容用 UTF-8 编码
```

两条输出路径的编码来源不同，因此历史上会出现「日志乱码但 CLI 文本正常」这种混合现象
（`System.out.println(String)` 走 PrintStream 自己的 charset，Logback 走裸字节）。
实测字节（`oryxos tool list`，控制台为 UTF-8 时）：

```text
不带参数                 : B9 A4 D7 F7 ...        （GBK   → 乱码）
-Dstdout.encoding=UTF-8  : E5 B7 A5 E4 BD 9C ...   （UTF-8 → 正常）
```

### 现状：应用已自行处理，无需任何参数、也无需 chcp

启动入口在 Spring **之前**接管标准流（`io.oryxos.boot.ConsoleEncoding`）：

- 检测到真实交互式控制台时，把 `System.out`/`System.err` 换成基于 `Console` 的输出，
  Windows 底层是 `WriteConsoleW` 宽字符 API，**不受代码页影响**；
- `CliChannel` 的交互式对话同样走 `System.console()`；
- `application.yaml` 固定 `logging.charset.console=UTF-8`，由宽字符通道保证落地正确；
- 无控制台（重定向、管道、IDE 捕获、Maven 测试）时**全部 no-op**，不改变任何行为。

所以正常使用**不需要** `-Dstdout.encoding` 系列参数，也**不需要** `chcp`。

### 兜底：手工指定 UTF-8（仅在遇到未覆盖的终端时使用）

PowerShell 5.1 会把 `-Dxxx=yyy` 拆坏，**每个 `-D` 参数必须单独加引号**：

```powershell
& "$env:JAVA_HOME\bin\java.exe" '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' '-Dstdin.encoding=UTF-8' -jar $jar chat --profile default
```

注意：仅 `-Dfile.encoding=UTF-8` 不改变 stdio 编码，必须用
`stdout.encoding`/`stderr.encoding`/`stdin.encoding`。

## Local CLI Flow

生产使用是 `java -jar`。先打包：

```powershell
$env:JAVA_HOME="C:\Users\Administrator\.jdks\ms-21.0.12"
& "E:\software\apache-maven-3.9.9-bin\apache-maven-3.9.9\bin\mvn.cmd" -DskipTests package
```

然后在你的工作目录（`.oryxos/` 会被建在当前目录）执行：

```powershell
$jar="g:\code\ORYXOS\oryxos-boot\target\oryxos-boot-0.1.0-SNAPSHOT.jar"

# 轻命令：不启动 Spring，秒回，不需要 API key
& "$env:JAVA_HOME\bin\java.exe" -jar $jar init
& "$env:JAVA_HOME\bin\java.exe" -jar $jar profile list
& "$env:JAVA_HOME\bin\java.exe" -jar $jar profile create demo
& "$env:JAVA_HOME\bin\java.exe" -jar $jar profile show demo
& "$env:JAVA_HOME\bin\java.exe" -jar $jar provider list
& "$env:JAVA_HOME\bin\java.exe" -jar $jar tool list
& "$env:JAVA_HOME\bin\java.exe" -jar $jar status
& "$env:JAVA_HOME\bin\java.exe" -jar $jar session list

# 重命令：启动 Spring，需要模型凭证
$env:DEEPSEEK_API_KEY="<你的 key>"
& "$env:JAVA_HOME\bin\java.exe" -jar $jar chat --profile demo
& "$env:JAVA_HOME\bin\java.exe" -jar $jar serve --port 8080
```

### 直接用 `oryxos` 命令

仓库自带包装脚本 `bin/oryxos.cmd`，内部固定 JDK 21 与 fat JAR 路径，
并原样转发所有参数和退出码。把所在目录加入 PATH：

```powershell
# 当前会话
$env:PATH = "g:\code\ORYXOS\bin;$env:PATH"

# 永久生效（用户级）
[Environment]::SetEnvironmentVariable('PATH',
    [Environment]::GetEnvironmentVariable('PATH','User') + ';g:\code\ORYXOS\bin', 'User')
```

之后可直接使用：

```powershell
oryxos init
oryxos status
oryxos profile list
oryxos chat --profile default
oryxos serve --port 8080
```

工作区按**当前目录**解析（`.oryxos/` 建在你执行命令的目录下），所以在哪个项目目录执行就在哪个目录生效。

脚本可覆盖项：

```powershell
$env:ORYXOS_JAVA_HOME = "D:\jdk\jdk-21"         # 换 JDK 21
$env:ORYXOS_JAR       = "D:\builds\oryxos.jar"  # 换 fat JAR
$env:ORYXOS_JAVA_OPTS = "-Xmx1g"                # 追加 JVM 参数
```

**维护约束（重要）**：`oryxos.cmd` 必须保持 **纯 ASCII + CRLF 换行 + UTF-8 无 BOM**。

`.cmd` 由 cmd.exe 按**控制台代码页**逐行读取，一旦混入非 ASCII 字节，在特定代码页与换行符
组合下会把一行拆成几截、逐截当命令执行。实测症状（把同一脚本改成 LF 换行即可复现）：

```text
'oryxos' is not recognized as an internal or external command
'VA_HOME" 1>&2' is not recognized as an internal or external command   ← ORYXOS_JAVA_HOME 被截断
'SNAPSHOT.jar"' is not recognized as an internal or external command
```

所以脚本里的提示信息一律用英文，不要为了"友好"改回中文。

### 备选：PowerShell 函数（完全不经过 cmd）

如果只在 PowerShell 里使用，函数方式更省事，也不受上面这类编码问题影响：

```powershell
function oryxos {
    & "C:\Users\Administrator\.jdks\ms-21.0.12\bin\java.exe" -jar `
      "g:\code\ORYXOS\oryxos-boot\target\oryxos-boot-0.1.0-SNAPSHOT.jar" @args
}
```

把它写入 `$PROFILE`（`notepad $PROFILE`）即可永久生效。

### 运行前置条件

- **先执行 `init`**：重命令按当前目录解析工作区，`.oryxos/` 不存在时 SQLite 数据目录也不存在。
- **必须设置 `DEEPSEEK_API_KEY`**：`application.yaml` 默认声明了 `deepseek` provider，
  占位符解析不到会在**启动阶段**明确报错 `provider 'deepseek' 的 api-key 未解析`，而不是等到第一次对话。

### 轻重分流

无 CLI 参数时 `java -jar` 退化为普通 Spring Boot 启动（起 Web 服务）；
带命令名时进入 CLI 分流——轻命令不启 Spring，重命令启 Spring。

### chat 交互

```text
> 第一条消息
<Agent 回复>
> 第二条消息
<Agent 回复>
> /quit
```

`/quit` 结束交互；Ctrl+C 直接终止进程也不会丢数据，因为会话在**每轮处理结束时**就已落库。

## Session Verification

1. 第一次使用 `chat --profile demo` 输入两条消息并退出（`/quit`）。
2. 再次使用同一 Profile 进入 `chat`，确认历史上下文仍属于同一 CLI Session。
3. 使用不同 Profile，确认不会读取 `demo` 的 Session。
4. 在新的应用上下文中执行 `session list` 或其他读取操作，确认历史仍存在。
5. 删除 `demo` Profile，确认原有 Session 仍可列出或恢复。

**已验证记录（手工端到端）**：

```text
oryxos init
设置 DEEPSEEK_API_KEY
oryxos chat                    → 进入交互模式
> 2+3等于多少                   → 回复 5
> 再加5等于多少                 → 回复 10（模型看到了上一轮）
Ctrl+C                          → 进程退出（每轮已落库，不丢）
oryxos chat                    → 同一会话被恢复（按 (cli, local, default) 三元组查库）
> 再减去5等于多少               → 回复 5（模型看到了前两轮）
```

第 5 步里 `sessionId` 与重启前一致，`messages` 带回了之前的 4 条历史——
这直接验证了「每轮持久化 + 三元组恢复」两条机制。

## 工作区结构（init 生成）

```text
.oryxos/
├── profiles/default.yaml     # Profile（provider 是对象、channels 是对象列表）
├── sessions/                 # 会话目录
├── skills/                   # SKILL.md
├── logs/
├── tools/                    # 自定义 Tool 配置
├── memory/
├── AGENTS.md                 # Bootstrap 三件套，由 ContextLoader 从工作区根读取
├── SOUL.md
├── USER.md
├── MEMORY.md                 # 长期记忆
├── mcp_servers.yaml
└── oryxos.db                 # SQLite（sessions / llm_calls / tool_invocations / agent_runs）
```

Provider 在 `application.yaml` 的 `oryxos.providers` 中全局声明；Profile 只引用 provider 名
（两者必须一致，否则 ProfileLoader 校验失败并跳过该 Profile）。

## Light/Heavy Verification

- 运行 `init`、`profile list`、`status` 和 `--help`，确认能快速返回且不要求 Provider API key。
- 运行 `chat`、`serve`、`gateway`，确认它们加载完整运行能力；启动日志能显示 Storage/JPA 组件可用。
- 使用错误 Profile、非法端口、损坏配置和不可写目录，确认命令返回非成功状态且错误信息不泄露凭证。

## Test Scope

| 覆盖点 | 测试类 | 状态 |
|---|---|---|
| 会话三元组幂等与隔离 | `SessionManagerTest` | ✅ |
| 按业务身份在新上下文中恢复 | `SessionManagerTest` | ✅ |
| Session JSON 序列化与读回（角色/顺序/时间戳） | `JpaSessionStoreTest` | ✅ |
| 读回后接续追加（upsert 而非新增） | `JpaSessionStoreTest` | ✅ |
| 会话持久化失败传播 | `JpaSessionStoreTest` | ⚠️ 部分（写入失败场景未覆盖） |
| `CliChannel` 多轮输入、空行、同一会话、AgentService 委托 | `CliChannelTest` | ✅ |
| Agent 失败不伪造回复 | `CliChannelTest` | ✅ |
| 12 个命令的注册与帮助 | `CommandHelpTest` | ✅ |
| 轻重命令分流 | `CommandModesTest` | ✅ |
| 重命令运行时边界与非法端口 | `RuntimeCommandTest` | ✅ |
| 工作区初始化与默认 Profile 可被 `ProfileLoader` 解析 | `InitCommandTest` | ✅ |
| Profile 管理成功/失败/脱敏 | `ProfileCommandTest` | ✅ |
| 轻命令空结果与不泄漏凭证 | `QueryCommandTest` | ✅ |
| 控制台编码接管边界（无控制台不得干预） | `ConsoleEncodingTest` | ✅ |
| 重命令复用共享 Agent/Session | `SpringRuntimeLauncherTest` | ✅ |
| 端到端流程（初始化→双轮对话→重启恢复） | 手工验证 | ✅ 手工 |
| 并发获取 / 重复保存回归 | — | ⚠️ 未覆盖 |

未覆盖项与建议优先级见 `tasks.md` 的「剩余未完成项」。

真实模型调用属于手动集成验证，不作为默认测试前置条件；
默认测试套件不依赖网络或 API key。
