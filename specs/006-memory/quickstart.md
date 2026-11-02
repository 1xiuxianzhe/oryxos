# Quickstart: Memory（怎么跑、怎么验）

## 0. 构建环境（固定，勿用系统默认 JDK）

| 项 | 值 |
|---|---|
| Maven | `E:\software\apache-maven-3.9.9-bin\apache-maven-3.9.9` |
| settings.xml | 同上 `conf/settings.xml`（阿里云镜像） |
| 本地仓库 | `E:\software\apache-maven-3.9.9-bin\apache-maven-3.9.9\mavenRepository` |
| JDK 21 | `C:\Users\Administrator\.jdks\ms-21.0.12` |

```powershell
$env:JAVA_HOME = 'C:\Users\Administrator\.jdks\ms-21.0.12'
& 'E:\software\apache-maven-3.9.9-bin\apache-maven-3.9.9\bin\mvn.cmd' -q test
```

系统默认 `JAVA_HOME` 指向 JDK 8，**必须先设 `JAVA_HOME`**，否则编译失败。

## 1. 跑完整测试

```powershell
mvn -q test                                  # 全量回归
mvn -q -pl oryxos-memory test                # 只跑本特性的 harness
mvn -q -pl oryxos-core,oryxos-cli,oryxos-memory test   # 受影响的三个模块
```

本特性的 harness（全单测，`@TempDir`，不依赖模型与外网）：

| 测试类 | 覆盖的验收点 |
|---|---|
| `LongTermMemoryTest` | 写后立读（无缓存）；截断只裁归档、核心一字不动；截断不落盘；scope 路由；多行内容不破结构；脏文件容错；并发写入 |
| `MemoryToolsTest` | `scope` 缺省写归档；非法 `scope` 失败且不可重试；未命中返回文本而非异常；缺参数失败；core 写入不进检索 |
| `DefaultMemoryServiceTest` | 门面只输出长期记忆；空文件/缺区块/IO 失败 → 空串且不抛；remember 后立即可见 |
| `MemoryFilesTest` | 骨架幂等；迁移四种场景；`.bak` 命名与不覆盖 |
| `MemoryAutoConfigurationTest` | 两个工具被登记进注册表；门面 Bean 可注入；启动后工具清单包含它们 |
| `PromptBuilderTest`（改造） | 第二部分 = 门面输出且**只出现一次**；门面为 `null` 时为空；会话历史仍由第三部分承担 |
| `InitCommandTest`（改造） | 规范路径有记忆文件、根目录不再新建；迁移场景 |

## 2. 手工验证（不依赖模型）

```powershell
# 在一个干净目录跑初始化
mvn -q -pl oryxos-boot -am -DskipTests package
java -jar oryxos-boot/target/oryxos-boot-0.1.0-SNAPSHOT.jar init     # 或对应 CLI 命令

# 看两个地方
type .oryxos\memory\MEMORY.md      # 应有 # OryxOS Memory + ## 核心记忆 + ## 归档记忆
dir  .oryxos\MEMORY.md             # 不应再被新建
```

**观察点**：

1. **启动日志**：应出现工具注册日志，且工具清单里包含 `save_memory`、`recall_memory`（并列在既有 7 个工具之后）。
2. **记忆装配日志**：`MemoryAutoConfiguration` 应打出记忆文件路径；若路径指向 `memory/MEMORY.md` 即正确。
3. **记忆段为空时**：应用照常启动、对话正常——记忆缺失不该有否决权。

## 3. 端到端验收（Demo 二：跨对话记偏好）

需要真实模型（DeepSeek key）：

1. 起服务，进入 `chat`，说一句值得记的事：*"我的项目用 Spring Boot，部署在 K8s 上"*。
2. Agent 应主动调用 `save_memory`（工具结果里能看到写入确认）。
3. 检查 `.oryxos/memory/MEMORY.md`：多了一条 `- [日期] ...`（默认落归档区）。
4. **重启进程**，开新会话，问：*"我的项目怎么部署的？"* → Agent 应直接答出来，不需要用户重述（SC-001）。
5. 再说一句用户身份/约束类信息并指定 `core`：*"记住：以后回答都用中文"*（scope=core）。
6. 检查文件：该条落在 `## 核心记忆` 下；随后把归档区灌到超过 4000 字符，再问一次，核心记忆仍在（SC-002）。

## 4. 常见故障排查

| 现象 | 排查方向 |
|---|---|
| Agent 完全不记得任何事 | ① `oryxos-boot` 是否依赖了 `oryxos-memory`（否则装配类根本没进 classpath）；② 启动日志里有没有记忆装配与工具注册；③ 该 Agent 的 Profile 是否**显式声明**了工具清单却没列 `save_memory` |
| 记忆写到了别处 | 检查 `oryxos.workspace` 属性（默认 `${user.dir}/.oryxos`）与 `memory/MEMORY.md` 路径 |
| 检索老是找不到 | 核心阶段是**关键词包含匹配**、只搜归档区；换一个更贴近原文的词再试（语义检索属扩展阶段） |
| 记忆段为空但文件有内容 | 检查文件里是否有 `## 核心记忆`/`## 归档记忆` 标题（缺标题的自由文本不注入，见文件格式契约） |

## 5. 验收对照表

| 成功标准 | 怎么验 |
|---|---|
| SC-001 跨会话、跨重启记得住 | 第 3 节步骤 1~4 |
| SC-002 核心记忆零丢失 | `LongTermMemoryTest` 的截断用例 + 第 3 节步骤 6 |
| SC-003 写入后立即可见 | `写入后立刻可读_不允许有缓存` 用例 |
| SC-004 未命中/文件缺失不中断 | `MemoryToolsTest` + `DefaultMemoryServiceTest` 相关用例 |
| SC-005 空记忆不影响对话 | `PromptBuilderTest` 空记忆用例 + 第 2 节观察点 3 |
| SC-006 既有测试全绿 | `mvn -q test` 全量回归 |
