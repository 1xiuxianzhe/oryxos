---
description: "Tool 体系实施任务"
---

# Tasks: Tool 体系

**Input**: Design documents from `/specs/005-tool-system/`

**Prerequisites**: `plan.md`、`spec.md`、`research.md`、`data-model.md`、`contracts/`、`quickstart.md`

**Tests**: 本特性明确要求测试（各契约文件的 Required Verification 共 30 条），测试任务包含在各用户故事中。

**Organization**: 按用户故事分组，每个故事可独立实现与验证。

**Status legend**: `[X]` 已完成并验证；`[ ]` 未完成（附原因）。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 不同文件、无未完成依赖，可并行
- **[Story]**: 所属用户故事（US1 ~ US4）

## Path Conventions

单体 Maven 多模块仓库，路径均为仓库根相对路径。主体落在 `oryxos-tool`；
另有 `oryxos-core`（过滤点与注册表）、`oryxos-boot`（配置与装配）、
`oryxos-cli` 与 `oryxos-web`（清单入口）四处收口改动。

---

## Phase 1: Setup (Shared Infrastructure)

- [X] T001 [P] 在 `oryxos-tool/src/test/java/io/oryxos/tool/support/FakeMcpClientProvider.java` 新增可控的连接替身：
      支持"服务可连接 / 连接被拒 / 列出工具即失联 / 记录已建立连接"，并提供构造假客户端的便捷方法
- [X] T002 [P] 确认 `oryxos-tool` 与 `oryxos-core` 的测试依赖已就位（`spring-boot-starter-test` 均已在）

**Checkpoint**: `mvn -pl oryxos-tool,oryxos-core -am test` 可执行。✅

---

## Phase 2: Foundational (Blocking Prerequisites)

- [X] T003 在 `oryxos-core/.../tool/ToolRegistry.java` 补 `contains(String)`，并把 `register` 改为**重名拒绝 + 告警**
      （先注册者胜出），满足 `contracts/tool-registry.md` 第 2 节
      **实现方式**：用 `putIfAbsent` 一次完成"判重 + 登记"，避免"先查后写"的竞态
- [X] T004 新增 `oryxos-core/src/test/java/io/oryxos/core/tool/ToolRegistryTest.java`（4 例）：
      重名时首个保留、内置与外部撞名时内置胜出、`contains` 真假两侧、未登记名字取用返回空而不抛异常
- [X] T005 [P] 新增 `oryxos-tool/.../sandbox/SandboxProperties.java`，绑定 `oryxos.sandbox` 的三个清单
- [X] T006 新增 `oryxos-tool/.../config/ToolAutoConfiguration.java`：启用配置绑定并产出校验器 Bean；
      **空白条目在启动阶段报错**（空字符串路径会被规范化成当前目录，等于静默放宽白名单）
- [X] T007 在 `oryxos-boot/src/main/resources/application.yaml` 新增白名单配置段与注释，
      并注明"任一清单为空即全拒""notify 的 webhook 域名同样受约束"
- [X] T008 新增 `SandboxCheckerTest.java`（12 例）：三维度放行/拒绝、空清单一律拒绝、
      `..` 绕过被拒、通配不跨域匹配
- [X] T009 [P] 新增 `oryxos-core/.../tool/ToolResolver.java`：按 Agent 授权解析可用工具

**Checkpoint**: 注册表语义、白名单装配、授权解析三者就位。✅

---

## Phase 3: User Story 1 - Agent 能安全地使用内置工具 (Priority: P1)

- [X] T010 [P] [US1] `FileToolTest`（8 例）：三工具放行路径 + 越界路径；
      越界时断言目标文件**未被读取/未被写入**——读用例刻意用不存在的路径，
      报"白名单"而非"文件不存在"即证明校验早于读取
- [X] T011 [P] [US1] `ShellToolTest`（5 例）：白名单内命令返回输出；越界被拒**且无进程启动**
      （用"mkdir 一个不存在的目录"作副作用探针）；超时被终止；**越界结果为不可重试**
      **可测性改动**：`ShellTool` 增加包私有构造器注入更短超时，否则超时用例要真等 30 秒
- [X] T012 [P] [US1] `HttpToolTest`（7 例）：两工具放行路径（复用 19 节既有的本地假服务）；
      越界域名被拒**且假服务零命中**；5xx 可重试 / 4xx 不可重试
- [X] T013 [US1] 在 `ToolAutoConfiguration` 中用一个登记器 Bean 注册六个内置工具
      （不声明第二个 `ToolRegistry` 类型 Bean）
- [X] T014 [US1] `ToolAutoConfigurationTest`（6 例）：登记器产出 6 个工具、契约三件套非空、
      空清单仍能装配校验器、三类空白条目各自在启动阶段报错
- [X] T015 [US1] 修正失败语义：`ShellTool` 与 `HttpTool` 的白名单越界此前被归为"可重试"，
      与 `contracts/tool-invocation.md` 第 3 节冲突 → 改为不可重试；
      另修正 `HttpTool` 把**非 2xx 也当成功**返回的问题（等于把失败改写成成功）

**Checkpoint**: 启动后注册表含 7 个工具；越界调用被拦且副作用为零。✅ 已实机验证

---

## Phase 4: User Story 2 - 每个 Agent 只能用被授权的工具 (Priority: P1)

- [X] T016 [P] [US2] `ToolResolverTest`（8 例）：声明 N 个→恰好 N 个；两 Agent 交替组装互不污染；
      声明不存在的名字→被排除且不回退全量；未声明/为空→全量；混入外部工具后结论不变；重复声明不重复暴露
- [X] T017 [US2] 修改 `ReActLoop`：可用工具由 `toolRegistry.listAll()` 改为 `toolResolver.resolve(profile)`
      **取舍**：解析器在循环内由注册表构造，而非提升为构造参数——避免改动全部既有构造点
- [X] T018 [US2] `Profile.ToolConfig` 增加委托式创建器，容忍 `tools: [read_file, shell]` 简写；
      `ProfileToolDeclarationTest`（3 例）覆盖对象写法、简写写法、空声明
- [X] T019 [US2] 新增 `ReActLoopToolAuthorizationTest`（2 例）作为**接线回归**：
      若哪天有人把解析改回全量列举，用例立刻红

**Checkpoint**: 同一进程内两个 Agent 各自只看到自己被授权的工具。✅

---

## Phase 5: User Story 3 - 接入外部工具服务且失联不拖垮自己 (Priority: P2)

- [X] T020 [P] [US3] `McpServerConfigLoaderTest`（11 例）：文件缺失/列表为空、两种连接方式解析、
      环境变量占位、缺 name/transport/command/url 与非法 transport 各自被跳过、合法与非法共存时只留合法的
- [X] T021 [P] [US3] `McpToolAdapterTest`（7 例）：名字/描述映射、描述缺失退回工具名、
      结构化 schema 序列化为 JSON 文本、参数原样转发、成功拼接内容块、
      服务报告失败可重试、调用抛异常不冒给 Agent
- [X] T022 [P] [US3] `McpClientServiceTest`（10 例）：正常服务注册且形态与内置一致、
      单服务失联不阻断、一好一坏互不影响、列出工具时失联也被隔离、与内置撞名内置胜出、
      停机回收连接、失败的服务不被尝试关闭、装配后自动连接、一个服务多工具全部注册
- [X] T023 [US3] 新增 `mcp/McpServerDeclaration.java` 与 `mcp/McpServerConfigLoader.java`
      **复用**：占位替换复用 `ProfileLoader.substitute`（改为 public），避免第二份实现漏掉脱敏
- [X] T024 [US3] 新增 `mcp/McpClientProvider.java` 端口与 `mcp/SdkMcpClientProvider.java` 实现
      （本地子进程 / 远程两种传输，显式设置请求与握手超时；握手失败也回收连接）
- [X] T025 [US3] 新增 `mcp/McpToolAdapter.java`
- [X] T026 [US3] 新增 `mcp/McpClientService.java`（实现 `InitializingBean` + `AutoCloseable`）
- [X] T027 [US3] 在 `ToolAutoConfiguration` 装配外部服务组件；服务 Bean 用
      `@DependsOn("builtinToolRegistration")` 保证"内置先注册、撞名时内置胜出"的顺序不变式
- [X] T028 [US3] 装配级验证：声明文件为空时正常装配、外部工具数为 0（已实机验证）

**Checkpoint**: 登记一个真实外部服务后其工具出现在清单里；地址写错时应用照常启动。✅

---

## Phase 6: User Story 4 - 工具清单反映真实状态 (Priority: P3)

- [X] T029 [P] [US4] `RuntimeCommandTest` 增加 2 例：没有运行时时 `tool list` 明确失败
      （不退回目录列举）；有运行时时清单来自注册表
- [X] T030 [P] [US4] 新增 `oryxos-web/.../ToolApiControllerTest.java`（4 例）：
      返回注册表真实内容、含外部工具时一致、空注册表返回空数组、重名只出现一次
- [X] T031 [US4] 修改 `ToolCommand`：改读注册表；同步把 `tool` 登记为**重命令**
      （见下方「实现期决策」），并更新 `QueryCommandTest` 与 `CommandModesTest`
- [X] T032 [US4] 修改 `ToolApiController`：注入注册表并返回真实内容（去掉硬编码九项清单）；
      `RuntimeLauncher` 补 `registeredTools()`，`SpringRuntimeLauncher` 实现之

**Checkpoint**: 两个入口与注册表内容一致，差异为 0。✅ 已实机验证

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T033 [P] 契约三件套检查：`ToolAutoConfigurationTest` 以 `allSatisfy` 遍历注册表逐一断言
      名字/描述/schema 非空；`McpClientServiceTest` 对来自外部服务的工具做同样断言
- [X] T034 更新 `docs/class/第20节`：补「实现说明」，逐条列出示意代码与仓库实现的差异
- [X] T035 更新 `docs/TechnicalSolution.md` §6：把内置工具的注册现状、按 Agent 授权与
      外部服务接入的落地情况对齐实际
- [X] T036 使用 JDK 21 与固定 Maven 3.9.9 执行仓库根 `mvn test`，10 个模块 BUILD SUCCESS
- [X] T037 按 `quickstart.md` 第 7 节完成人工验收（真实白名单、真实外部服务、失联隔离）
      **已实测完成**：真实白名单三维度行为、真实 MCP 服务接入
      （官方参考实现，13 个工具注册 + `echo`/`get-sum` 真实调用成功）、
      失联隔离（真实不可达命令 + 非法 transport，启动不失败且正常服务照常接入）。
      **仍需使用方执行**：由真实模型驱动 Agent 自主调用这些工具完成一次任务（需模型凭证）。

---

## 实现期决策（计划外）

| # | 决策 | 理由 |
|---|---|---|
| 1 | **`tool list` 由轻命令改为重命令** | 工具清单必须来自真实注册表，而注册表只在运行时里存在（内置工具靠装配登记、外部工具靠启动期连接）。若留在轻命令路径上，就只能给出目录列举或硬编码清单——那正是本特性要消除的"影子清单"。**代价**：`oryxos tool list` 现在需要完整运行时（含模型凭证），已在 `quickstart.md` 标注 |
| 2 | `ShellTool` 增加包私有超时构造器 | 否则"超时被终止"这条契约要真等 30 秒才能验，实战中必然被跳过 |
| 3 | 修正 `HttpTool` 把非 2xx 当成功返回 | 原实现会把目标 5xx/4xx 包装成"成功 + 响应体"，等于**把失败改写成成功**，违反 `contracts/tool-invocation.md` 第 3 节 |
| 4 | `ProfileLoader.substitute` 改为 public | 声明文件也需要"配置不出现明文凭证"，占位替换只应有一份实现 |
| 5 | `oryxos-tool` 增加 `spring-boot` 依赖 | 配置绑定需要 `@ConfigurationProperties`；与 `oryxos-provider` 既有做法一致，非新增第三方依赖 |
| 6 | 清单接口去掉 `type` 字段 | 注册表**不记录工具来源**，无法如实给出 builtin/external。宁可少一个字段，也不返回编造的来源标记 |

## 验证记录

| 验证项 | 方式 | 结果 |
|---|---|---|
| 工具模块回归 | `mvn -pl oryxos-tool -am test` | 新增 41 例全绿（含既有 notify 用例不回归） |
| 核心模块回归 | `mvn -pl oryxos-core -am test` | 新增 17 例全绿 |
| 全量回归 | 仓库根 `mvn test` | **10 个模块 BUILD SUCCESS** |
| 实机装配 | 启动应用并读启动日志 | `白名单校验器装配完成（路径 1 条、命令 1 条、域名 1 条）`；注册表含 7 个工具：`http_post, write_file, list_dir, shell, http_get, notify, read_file`；`[Mcp] 未声明外部工具服务，外部工具数为 0` |
| 清单接口 | 实机 `GET /api/v1/tools` | 返回上述 7 个工具（真实注册表，非硬编码） |
| 真实外部服务 | 接入真实外部工具服务并调用 | ⏳ 待人工执行（T037） |

> 说明：**工具注册与白名单装配必须一起验证**——内置工具强依赖非空校验器，缺失即构造失败。
> 实机启动同时证明了这两点，这是纯单测覆盖不到的组合盲区。

## 交付后修正（2026-10-12）

对照 `contracts/tool-invocation.md` 的 FR-014（"路径校验 MUST 基于解析后的真实路径进行，
MUST NOT 被符号链接绕过"）复核实现时发现：**原实现不满足该要求**。

- **问题**：`SandboxChecker.checkFilePath` 只做 `Paths.get(path).normalize().toAbsolutePath()`
  字面归一化。`Path.normalize()` 是**纯字面运算，不访问文件系统、不解析符号链接**。
- **实测**：在白名单目录内建一个指向外部的符号链接，`白名单目录/link/密码文件` 通过了字面前缀比对，
  并**真的读出**了白名单外的文件内容（探针输出 `TOP SECRET CONTENT`）。
- **修正**：改为解析真实路径，且**白名单条目与待校验路径两侧口径一致**；
  目标不存在时（写入新文件）退回"最近的已存在祖先的真实路径 + 剩余段"，
  以同时覆盖"新建文件"与"中间层已有链接"两种情形；解析失败时保守退回字面路径，不因异常而放行。
- **测试**：新增 3 例（链接指向外部被拒、白名单目录自身是链接时仍按真实路径匹配、
  新建文件时识别中间层链接）。环境不支持创建链接时**中止**用例，
  `SandboxCheckerTest` 由 12 例增至 15 例，**0 跳过**，全量 10 模块 BUILD SUCCESS。

> 教训：`Path.normalize()` 挡得住 `..`（字面消除），但完全挡不住符号链接。
> 凡涉及"路径是否逃逸出目录"的判断，必须用真实路径；
> 只在注释与文档里写"已按真实路径判定"、代码里却用 `normalize()`，就是文档与实现脱节。

## 交付后新增：白名单总开关（2026-10-12）

**需求**：开发调试时不想逐个配白名单，希望一个开关关掉校验；上线再打开并配置。

**实现**：`oryxos.sandbox.enabled`，默认 `true`；`SandboxChecker` 增加带开关的构造器，
关闭时三个维度直接返回。

**三条设计纪律**（这是本次改动真正的价值所在）：

| # | 纪律 | 理由 |
|---|---|---|
| 1 | **默认 `true`** | 默认值决定"漏配时的后果"。默认关闭 = 一次疏忽就是边界敞开，这类默认值是安全事故的常见来源 |
| 2 | **关闭时打醒目告警**（多行 WARN + 分隔线） | 这条配置最怕的不是"没配"，而是"配了忘记改回来"；一行 INFO 会被淹没 |
| 3 | **配置格式校验始终执行**，不受开关影响 | 空白条目是配置缺陷，与是否启用无关。只在校验时才查，问题会留到"上线打开开关"那一刻才炸 |

**测试**：`SandboxCheckerTest` 由 15 例增至 18 例（关闭后三维度放行且空清单也放行、
默认启用时仍然拦截、关闭时越界路径也放行）；`ToolAutoConfigurationTest` 由 6 例增至 9 例
（默认启用、显式关闭后放行、关闭不影响格式校验）。

**契约同步**：`contracts/tool-invocation.md` 新增第 7 节与验证项 10~12。

## 交付后新增：真实 MCP 服务联调（2026-10-12）

为了让外部服务接入这条路"真的被跑过一次"，接入了官方参考实现
`@modelcontextprotocol/server-everything`（自带 `echo` / `get-sum` 等演示工具，
无参数、无副作用、工具名与内置 7 个不冲突）。

**实测结果**：

| 项 | 结果 |
|---|---|
| 声明读取 | `[Mcp] 已读取外部服务声明 1 条: [everything]` |
| 连接与注册 | `工具 13 个, 耗时 1814ms, 注册表共 20 个`（7 内置 + 13 外部） |
| 清单接口 | `GET /api/v1/tools` 返回全部 20 个 |
| **真实工具调用** | `echo` → `Echo: hello from oryxos`；`get-sum(a=17,b=25)` → `The sum of 17 and 25 is 42.` |
| schema 转换 | 结构化 schema 被正确序列化为 JSON 文本（含 `properties` / `required`） |
| 参数转发 | 入参原样送达（`a`/`b` 顺序与值均正确） |
| 停机回收 | 关闭后**无残留 node 子进程** |
| 失联隔离 | 真实不可达命令 + 非法 transport：启动不失败，成功 1/共 2，正常服务照常接入 |

**联调暴露出的两个真实问题，已修**：

1. **握手超时默认值过短**。原为 15 秒，而实测包管理器转发的命令冷启动需 **21.5 秒**
   ——按原值，所有新包在第一次连接时都必然超时，而使用者只会看到"连接失败"。
   已调整为 30 秒（附实测依据），预热后仅需 1.3 秒。
2. **失败原因不可诊断**。命令无法启动的真实原因（`Failed to start process with command`）
   被底层丢到后台线程打成 ERROR，主线程只拿到"握手超时"。
   已把等待耗时与三条排查提示加进失败告警，避免使用者面对拼错的命令无从下手。

**已知代价（未修，已记录）**：连接串行，每个失败服务都要等满超时，
N 个失败服务拖慢启动 N×30 秒。已写入 `contracts/mcp-client.md` 第 6 节。

## Dependencies & Execution Order

### Phase Dependencies

- Phase 1 Setup → 阻塞全部
- Phase 2 Foundational → 阻塞全部用户故事
- Phase 3 US1 → 依赖 Phase 2；是 US2/US3 的前置（没有工具就没有可授权的工具）
- Phase 4 US2 → 依赖 US1
- Phase 5 US3 → 依赖 US1（需要内置先注册，才有撞名与顺序语义）
- Phase 6 US4 → 依赖 US1、US3
- Phase 7 Polish → 依赖全部

### 不可拆分的约束

**T013 与 T006 必须一起交付**：内置工具强依赖非空的白名单校验器，缺失即构造失败
（`spec.md` A-003）。不存在"先注册工具、下轮再配校验器"的中间状态。

### Parallel Opportunities

- T001 / T002 可并行；T005 / T009 可并行；T010 / T011 / T012 可并行
- T020 / T021 / T022 可并行；T029 / T030 可并行；T033 / T034 / T035 可并行
- 注意：`ToolAutoConfiguration.java` 被 T006、T013、T027 先后修改，**这三条不得并行**

## Notes

- **不新增 Maven 模块**；未引入新的第三方运行期依赖（外部服务复用既有依赖坐标）
- **不使用**注解式自动注册与自动执行（宪法 III）；统一走"手写实现 + 装配处显式登记"
- 外部服务的连接与注册在**启动期**完成一次；运行期不检测服务增删
- 工具调用一律经既有执行器链路，审计落库不被绕过
- 命令工具的平台假设（Windows 命令解释器）**未在本特性修正**，仅在文档标注
- 未执行 Git commit（用户未要求）
