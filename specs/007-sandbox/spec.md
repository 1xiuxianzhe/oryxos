# Feature Specification: Sandbox 统一抽象与白名单校验

**Feature Branch**: `007-sandbox`

**Created**: 2026-10-20

**Status**: Draft

**Input**: User description: "docs/class/第24节：Sandbox 实现与代码讲解.md —— 把 23 节那道墙砌出来，墙后面填上白名单校验这一档实现，再把它接进已存在的 Tool"

**Source documents**:

- `docs/class/第23节：Sandbox 原理解析、业界方案与 OryxOS 设计评审.md`（设计与边界）
- `docs/class/第24节：Sandbox 实现与代码讲解.md`（实现与验收 harness）

## 背景与定位

Tool 体系（`005-tool-system`）已经让 Agent 能动手：文件、命令、HTTP、通知四个工具真实可执行，
执行前也有白名单校验。但那一轮的校验是**按维度散落的三个公开方法**（`checkFilePath` /
`checkShellCommand` / `checkHttpUrl`），`005` 的 `A-008` 明确把它记为"统一沙箱抽象属后续 Sandbox 特性"。

本特性补的就是这道墙：**定义一个表达意图、不表达实现的 `Sandbox` 抽象**，把白名单校验降级成它的
**唯一一个实现细节**（`WhitelistSandbox`），并让四个工具只依赖抽象。做完之后：

- 上层（Tool）只知道"执行前要在受控环境里过一下这个动作"，不知道"白名单"这三个字；
- 未来上容器、上 microVM 时，**新增一个实现类**即可，Tool 侧调用代码一行不改；
- 被拒的调用仍然走既有失败路径（不可重试失败 + 审计落库），不为安全单独开一条链路。

**边界声明**：

- 本特性**只交付第一档**（应用层白名单校验）。不写容器实现、不写 microVM 实现、不做资源配额。
- 不引入 Java `SecurityManager`（JDK 21 已不可用，见宪法约束 4）。
- 不改 `ToolExecutor` 的审计逻辑：Sandbox 拒绝就是一次普通的工具执行失败，复用既有 try/catch 与落库。
- 不新增 Maven 模块、不新增第三方依赖。

## 与既有实现的冲突及收敛结论

沙箱在 `005` 中已落地一部分，与 23/24 节的设计存在七处冲突。此处逐条给出收敛结论，**这些结论即本轮实现的契约**：

| # | 冲突点 | 文档要求 | 仓库现状 | 本轮结论 |
|---|---|---|---|---|
| **C-1** | 抽象形态 | `Sandbox.enforce(SandboxAction)` 唯一入口，三个校验方法必须 `private` | `SandboxChecker` 暴露三个公开校验方法，Tool 直接按维度调用 | **采纳文档**。新增 `Sandbox` 接口与值对象；把 `SandboxChecker` 的逻辑并入 `WhitelistSandbox` 并设为私有；**删除 `SandboxChecker`**，避免留下绕过抽象的第二条调用路径。既有行为（真实路径判定、通配匹配、总开关、空白条目启动报错）**逐条保留** |
| **C-2** | 配置键形状 | `file.allowed_paths` / `shell.allowed_commands` / `http.allowed_domains` 三段，各一个 `record` | 单一前缀 `oryxos.sandbox.{allowed-paths,allowed-commands,allowed-domains,enabled}`，已写入 `application.yaml` 与测试 | **保留仓库形态**。三段式会向顶层命名空间塞 `file/shell/http` 三个通用词，且属破坏性配置变更；统一前缀更符合"一个沙箱策略"的语义。配置在装配处解析后注入 `WhitelistSandbox`，不采用三个 `record` |
| **C-3** | Tool 接入方式 | `@Tool` 注解标注方法、Spring 扫描注册 | 手写实现 `OryxTool` + 装配处显式登记（宪法 III：不用 Spring AI 自动执行） | **沿用仓库形态**。`@Tool` 注解会牵出 Spring AI 自动 tool 执行，属宪法明令禁止项；只在 `execute` 首行加 `enforce` |
| **C-4** | 失败传播 | 异常抛给 `ToolExecutor`，由它的 try/catch 落库 | 工具自身 `catch` 后返回 `ToolResult.fail(msg, false)` | **沿用仓库形态**。两者最终都落进同一审计路径，但"不可重试"标记必须在工具层给出；保留 catch 不改变可审计性 |
| **C-5** | Notify 接入位置 | 四个 Tool 一律"执行首行 enforce" | `notify` 的目标 URL 来自 Profile 配置，首行拿不到 | **语义对齐、位置微调**。`notify` 在**目标解析之后、发送之前** enforce；仍是"校验严格早于副作用"。同时取消 `005` 留下的"未装配校验器则放行 + 告警一次"占位状态：`Sandbox` 成为**强制依赖** |
| **C-6** | 强制启用 | 23 节：核心阶段强制启用 | `005` 交付了 `oryxos.sandbox.enabled` 运维开关（默认 `true`） | **拆成两件事**：①"强制装配"= 运行期不存在"未装配沙箱"的状态（C-5）；②保留显式总开关（用户已明确要求的能力，默认收紧、关闭告警、格式校验始终执行）。删开关属破坏性回退，不在本轮 |
| **C-7** | 命令大小写 | 文档要求"有明确处理" | 首 token 精确匹配（大小写敏感） | **维持精确匹配**并写测试固定：`ECHO` 不等于 `echo`，按"不在白名单"拒绝。理由：宁可误拒（可诊断、可配置），不可误放（静默绕过） |

## 现状与缺口

| 缺口 | 当前表现 |
|---|---|
| 没有中立抽象 | Tool 依赖的是"白名单校验器"这一档实现本身，换隔离档必须改四个 Tool |
| 校验入口不止一个 | 三个公开方法任意一个都能被外部直接调用，绕过"动作"语义 |
| `notify` 的安全校验是可选件 | 未装配校验器时静默放行（仅一条 WARN），链路存在"没有边界"的状态 |
| 无绕过场景的回归防线 | 已有 `..` 与符号链接用例，但缺域名形似（`evil-example.com`）、命令前导空白/大小写等固定行为的用例 |
| 接口中立性缺乏可验证证据 | "microVM 能不能套进来"只停留在口头判断，没有落成契约文字 |

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 四个内置工具统一经过一道沙箱墙 (Priority: P1)

Agent 调用 `read_file` / `write_file` / `list_dir` / `shell` / `http_get` / `http_post` / `notify` 时，
执行链路里只有一个动作语义："在受控环境里执行这个动作"。工具不认识"白名单"，只认识 `Sandbox`。
拒绝时得到**不可重试**的失败结果与可读原因；放行时行为与改造前完全一致。

**Why this priority**: 这是本节的地基，也是"未来换隔离档不改上层"的唯一保证。
没有它，后续上容器就是一次涉及四个 Tool 的重构。

**Independent Test**: 不依赖模型与外网。用真实临时目录 / 真实本地假服务，
对每个工具各跑"放行 + 越界"两条路径，越界时断言副作用为零（文件未创建、进程未启动、请求零命中、消息未发送）。

**Acceptance Scenarios**:

1. **Given** 一个白名单内的路径，**When** 调用文件工具，**Then** 返回文件内容/写入成功。
2. **Given** 一个白名单外的路径，**When** 调用文件工具，**Then** 返回失败且原因为"不在白名单内"，**且没有发生读写**。
3. **Given** 白名单内的命令，**When** 调用 `shell`，**Then** 返回命令输出。
4. **Given** 白名单外的命令，**When** 调用 `shell`，**Then** 返回失败且**没有进程被启动**。
5. **Given** 白名单内的域名，**When** 调用 HTTP 工具，**Then** 返回响应体。
6. **Given** 白名单外的域名，**When** 调用 HTTP 工具，**Then** 返回失败且**没有请求发出**。
7. **Given** Profile 里配置的 webhook 域名不在白名单，**When** 调用 `notify`，**Then** 返回失败且**一次都没有发送**。
8. **Given** 任一工具被沙箱拒绝，**When** 查看结果，**Then** 该结果标记为**不可重试**（配置问题，重试无意义）。

---

### User Story 2 - 常见的绕过手法被固定行为拦住 (Priority: P1)

白名单只是第一层劝阻，但"想到要拦的却没拦"是最难堪的失守。路径穿越、符号链接、域名形似、
命令前导空白与大小写变体这几类经典绕过，必须有**固定行为 + 回归用例**，行为一旦被改坏立刻变红。

**Why this priority**: 安全模块的验收重点不是"放行对不对"，而是"绕得过绕不过"。

**Independent Test**: 纯单元测试，构造穿越路径、符号链接、形似域名、带空白/大写的命令，逐条断言拒绝。

**Acceptance Scenarios**:

1. **Given** 白名单目录 `A`，**When** 校验 `A/../../outside/secret.txt`，**Then** 拒绝。
2. **Given** 白名单目录内一个指向外部的符号链接，**When** 校验链接下的文件，**Then** 拒绝（按真实路径判定）。
3. **Given** 白名单目录本身是符号链接，**When** 校验其真实目录下的文件，**Then** 放行（两侧口径一致）。
4. **Given** 白名单 `*.example.com`，**When** 校验 `https://api.example.com/x`，**Then** 放行。
5. **Given** 白名单 `*.example.com`，**When** 校验 `https://evil-example.com/x`，**Then** 拒绝（必须有标签边界）。
6. **Given** 白名单 `echo`，**When** 校验 `"  echo hi"`，**Then** 放行（前导空白不影响取首 token）。
7. **Given** 白名单 `echo`，**When** 校验 `ECHO hi`，**Then** 拒绝（大小写变体不放行）。

---

### User Story 3 - 拒绝原因可读、可审计、不假装成功 (Priority: P2)

被拒绝时，模型在下一轮能看到"哪个维度、哪个值被拒"，从而改道；运维在 `tool_invocations`
里能看到 `success=false` 与同一句原因。任何情况下都不得把拒绝包装成成功。

**Why this priority**: 安全拦截如果不可诊断，模型会反复踩同一堵墙，体验与排障成本都会失控。

**Independent Test**: 断言异常消息包含被拒值；工具结果为失败且不可重试；既有审计路径未被绕过。

**Acceptance Scenarios**:

1. **Given** 命令 `rm -rf /`，**When** 被拒，**Then** 失败原因包含 `rm`。
2. **Given** 域名 `evil.example.net`，**When** 被拒，**Then** 失败原因包含该域名。
3. **Given** 任意一次沙箱拒绝，**When** 调用方拿到结果，**Then** `success=false` 且 `retryable=false`，不会被改写成成功。

---

### User Story 4 - 接口中立性可被复核（为下一代隔离档留路）(Priority: P3)

把接口拿给"最重的那一档"反向校验：一个 microVM 实现能否**只新增一个类、不改接口**？
契约中必须写明判断依据，而不是靠口头保证。

**Why this priority**: 这是本特性"多花的成本"的回报所在；不做也不影响当前功能，但决定未来是否要重构。

**Independent Test**: 契约文档中给出"microVM 实现如何套用 `enforce(SandboxAction)`"的推演，且接口签名中不含任何白名单/容器/镜像/路径/命令/域名等实现词。

**Acceptance Scenarios**:

1. **Given** `Sandbox` 接口，**When** 检查其方法签名与参数类型，**Then** 只出现"动作（type + target）"，不出现任何一档实现的概念。
2. **Given** 一个假设的 `MicroVmSandbox`，**When** 按契约实现 `enforce`，**Then** 无需给接口加方法。

---

### Edge Cases

- **白名单清单为空**：该维度一律拒绝（fail-closed），不是放行。
- **待校验目标为空/空白**：拒绝并给出可读原因，不得以 NPE 形式冒出去。
- **配置条目为空白字符串**：启动阶段即报错（空字符串路径会被规范化成当前目录，等于静默放宽）。
- **目标文件尚不存在（写新文件）**：按"最近的已存在祖先的真实路径 + 剩余段"判定，既支持新建也能识别中间层链接。
- **真实路径解析失败（权限/异常）**：保守退回字面绝对路径，不因解析异常而放行。
- **`enabled=false`**：三个维度一律放行，但**配置格式校验仍执行**（配置缺陷与是否启用无关）。

## Requirements *(mandatory)*

### Functional Requirements

**抽象与实现**

- **FR-001**: 系统 MUST 提供唯一的安全边界入口 `Sandbox.enforce(SandboxAction)`；除该方法外 MUST NOT 暴露任何按维度划分的公开校验方法。
- **FR-002**: `SandboxAction` MUST 只由"动作类型 + 目标字符串"构成，MUST NOT 出现白名单、容器、镜像、路径前缀、域名后缀等任何一档实现特有的概念。
- **FR-003**: 核心阶段 MUST 只有一个实现 `WhitelistSandbox`；该实现 MUST NOT 引入容器 / microVM / 资源配额代码。
- **FR-004**: `WhitelistSandbox` MUST NOT 使用 Java `SecurityManager`。

**接入点与顺序**

- **FR-005**: `read_file` / `write_file` / `list_dir` / `shell` / `http_get` / `http_post` MUST 在执行任何副作用前调用 `enforce`。
- **FR-006**: `notify` MUST 在解析出目标之后、发送之前调用 `enforce`；其目标 URL 同样受域名维度约束。
- **FR-007**: 上述四个工具 MUST 只依赖 `Sandbox` 抽象，MUST NOT 依赖任何具体实现类型。
- **FR-008**: 沙箱 MUST 是强制依赖：运行期 MUST NOT 存在"未装配沙箱、因而放行"的状态。

**校验规则**

- **FR-009**: 文件维度 MUST 基于**解析后的真实路径**判定，MUST NOT 被 `..` 或符号链接绕过；白名单条目与待校验路径 MUST 使用同一套解析口径。
- **FR-010**: 命令维度 MUST 取命令首个 token 与白名单精确匹配（大小写敏感），前导空白不影响取词。
- **FR-011**: 域名维度 MUST 支持精确匹配与 `*.` 前缀通配；通配 MUST 带标签边界，MUST NOT 让 `evil-example.com` 命中 `*.example.com`。
- **FR-012**: 任一清单为空时，该维度 MUST 一律拒绝（fail-closed）。
- **FR-013**: 配置 MUST 来自单一前缀 `oryxos.sandbox`；空白条目 MUST 在启动阶段报错。

**失败语义与审计**

- **FR-014**: 拒绝 MUST 抛 `SandboxViolationException`，消息 MUST 包含维度与被拒的具体值。
- **FR-015**: 工具 MUST 把拒绝转换为 `success=false` + `retryable=false` 的结果，MUST NOT 改写成成功。
- **FR-016**: 沙箱拒绝 MUST 经既有工具执行链路落库（`tool_invocations.success=false`、`error_message` 为拒绝原因），本特性 MUST NOT 新增独立审计路径。

**配置与运维**

- **FR-017**: `oryxos.sandbox.enabled` MUST 默认 `true`；置为 `false` 时三个维度全部放行并输出醒目告警；关闭 MUST NOT 跳过配置格式校验。

### Key Entities

- **动作（SandboxAction）**：一次待校验的副作用意图。属性：`type`（文件访问 / 命令执行 / HTTP 请求）、`target`（纯字符串，由 `type` 解释）。
- **沙箱（Sandbox）**：安全边界抽象。行为：对一个动作做裁决（放行或抛拒绝异常）。
- **白名单实现（WhitelistSandbox）**：唯一实现。内部持有三份清单（路径前缀、命令名、域名模式）与总开关。
- **拒绝异常（SandboxViolationException）**：越界的统一信号，消息面向模型与运维可读。
- **沙箱策略配置（SandboxProperties）**：`oryxos.sandbox` 下的三份清单 + 总开关。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 越界调用（路径 / 命令 / 域名各若干用例）被拒绝比例 **100%**，且每次拒绝前的副作用次数为 **0**。
- **SC-002**: 绕过用例（`..` 穿越、符号链接、形似域名、大小写变体）全部被拦，通过率 **100%**。
- **SC-003**: 白名单内的调用行为与改造前**完全一致**，既有 4 个 Tool 的测试全绿、0 跳过。
- **SC-004**: 沙箱相关的公开入口数量为 **1**（`enforce`）；代码中不存在 `checkFilePath` / `checkShellCommand` / `checkHttpUrl` 的公开方法。
- **SC-005**: 接口签名中实现特有词汇出现次数为 **0**（白名单 / 容器 / 镜像 / microVM / 路径 / 命令 / 域名）。
- **SC-006**: 全仓构建 **BUILD SUCCESS**，测试失败数 **0**。

## Assumptions

- **A-001（现状：校验逻辑已存在且更成熟）**：`005` 已交付真实可用的白名单校验（真实路径解析、通配转正则、总开关、空白条目校验）。本特性**迁移其逻辑而非重写**，避免把已验证的行为改坏。
- **A-002（决策：配置键保留单一前缀）**：见 C-2。代价：与源文档的三段式配置不一致，已在 `quickstart.md` 显式说明映射关系。
- **A-003（决策：删除 `SandboxChecker`）**：见 C-1。代价：`005` 的 `SandboxCheckerTest` 需改写为 `WhitelistSandboxTest`（用例逐条保留并扩充），属测试资产迁移而非功能回退。
- **A-004（决策：`notify` 的 enforce 位置后移一位）**：见 C-5。目标 URL 由 Profile 决定，首行无法拿到，强行在首行 enforce 只能校验空值——那是"看起来有校验"的假安全。
- **A-005（决策：保留总开关）**：见 C-6。关闭开关属于显式运维动作且有醒目告警；"没有沙箱"与"沙箱被显式关闭"是两种状态，本特性只消灭前者。
- **A-006（决策：命令大小写敏感）**：见 C-7。当前运行时固定走 Windows 命令解释器，大小写不敏感更贴近实际执行语义；但放宽匹配等于扩大白名单，方向与"fail-closed"相反，故维持精确匹配并在文档中标明代价（`ECHO` 会被拒）。
- **A-007（范围：不实现容器/microVM）**：仅以"microVM 能否只新增实现类"做接口中立性推演，不写任何调度或隔离代码。
- **A-008（不变式：副作用只经工具产生）**：本特性不新增任何绕过工具执行链路的副作用路径。
