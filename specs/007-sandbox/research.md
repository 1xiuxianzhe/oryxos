# Research: Sandbox 统一抽象与白名单校验

**Feature**: `007-sandbox` | **Date**: 2026-10-20

本文件记录实现前的方案比选。凡是"看起来只是写法不同"的取舍，都在这里给出判据，
避免实现期凭手感决定。

## R-01 抽象形态：动作对象 vs 分维度方法

| 方案 | 说明 | 判定 |
|---|---|---|
| A. 分维度方法（现状 `SandboxChecker`） | `checkFilePath` / `checkShellCommand` / `checkHttpUrl` | ❌ 方法名即实现名。换容器/microVM 时，"检查路径"根本不对应它的语义（容器是"提供一个隔离的文件系统"），接口必然要改 |
| B. 统一动作对象（选定） | `enforce(SandboxAction(actionType, target))` | ✅ 上层表达"要在受控环境里执行这个动作"；新增实现类即可，调用点零改动 |
| C. 传入策略对象（23 节曾讨论） | `execute(action, policy)` | ⚠️ 策略里会自然长出"允许路径/域名/资源上限/隔离等级"，一旦落地，策略本身就变成实现细节的集合；核心阶段只有一档实现，策略的每个字段都只服务白名单这一档。留到真正需要按 Agent 选隔离档时再引入 |

**结论**：取 B。`SandboxAction` 只放 `type` + `target`，`target` 是纯字符串、由 `type` 解释——
这样它既不带"路径/命令/域名"的实现色彩，也不会因为未来新增动作类型而改结构。

## R-02 是否保留 `SandboxChecker`

| 方案 | 代价 | 判定 |
|---|---|---|
| 保留，`WhitelistSandbox` 委托它 | 改动最小 | ❌ 公开三方法仍在，工具可以直接调用绕过 `enforce`；两个类名表示同一概念，违反"唯一入口" |
| 保留，只把三方法改 `private` | 中等 | ❌ `private` 后 `WhitelistSandbox` 无法复用其逻辑，除非同包/继承——为可见性写继承是更坏的耦合 |
| 逻辑迁入 `WhitelistSandbox`，删除旧类（选定） | 测试类需迁移 | ✅ 墙唯一、概念唯一；迁移的是逻辑而非重写，既有行为逐条保留 |

**结论**：迁移并删除（D-01 / D-02）。迁移清单：真实路径解析（含"最近已存在祖先"）、
白名单两侧同口径、通配转正则、`enabled` 短路、空白条目检查（留在装配层）。

## R-03 配置形状

| 方案 | 说明 | 判定 |
|---|---|---|
| A. `file.allowed_paths` / `shell.allowed_commands` / `http.allowed_domains` | 文档写法，三个 `record` | ❌ 向顶层命名空间塞 `file` / `shell` / `http` 三个泛词，会和别的配置撞；三段配置分别绑定，很难表达"这是同一个沙箱策略"；且破坏既有 `oryxos.sandbox.*` |
| B. 单一前缀 `oryxos.sandbox.*`（选定） | 一个 `SandboxProperties`，装配时拆成三份传给实现 | ✅ 语义聚合正确（一份策略），键已在 `application.yaml`、测试与文档中出现 |

**结论**：取 B。三份字段最终仍以三个构造参数进入 `WhitelistSandbox`，
所以"实现只认三份清单"这一点不受配置形状影响。

## R-04 失败如何传播

三个事实需要同时成立：①结果必须标"不可重试"；②审计必须落库；③拒绝原因必须可读。

- 若让异常直接冒到 `ToolExecutor`：①能标（执行器会兜住），但 005 已明确"越界不可重试"的标记点在工具层，
  执行器无法区分"配置错"与"网络错"。
- 若工具内 `catch` 后返回 `ToolResult.fail(msg,false)`（选定）：①②③全部满足，且与既有四个工具的写法一致。

**结论**：沿用工具内 catch（C-4）。审计路径不变——`ToolExecutor` 写入的是 `ToolResult`，不是异常类型。

## R-05 notify 的接入位置

`notify` 的目标 URL 来自当前 Profile 的通知渠道配置，`execute` 首行拿不到。三条路：

1. 首行 enforce 一个空值 → ❌ 假校验，最坏的一种"看起来安全"。
2. 把 URL 提为工具入参 → ❌ 改变 `notify` 的既有契约（渠道由 Profile 决定），是功能变更。
3. 解析目标后、发送前 enforce（选定）→ ✅ 语义仍是"校验严格早于副作用"，且与 004 的
   "校验先于发送"不变式一致。

同时取消"未装配校验器则放行"的占位分支：那是 005 明确标注为"属后续 Sandbox 特性"的临时状态。

## R-06 命令维度大小写

- 大小写不敏感：更贴近 `cmd.exe` 实际语义，但等于"配一个名字允许一族写法"，是**放宽**白名单。
- 大小写敏感（选定）：`ECHO` 被拒，使用者会看到"命令不在白名单内: ECHO"——可诊断、可改配置，属误拒而非误放。

**结论**：fail-closed 优先。代价写入 quickstart；若后续有真实需求，应作为独立变更评估（不是顺手改）。

## R-07 域名匹配实现

`endsWith("example.com")` 会让 `evil-example.com` 通过。现有实现把通配转成正则、`*` → `.*`、
`.` → `\.`，`*.example.com` 得到 `^.*\.example\.com$`，天然要求点号边界。

**结论**：保留正则方案，补 `evil-example.com` 用例把这个性质固定下来。

## R-08 测试策略：为什么必须断言"副作用为零"

只断言"抛了异常/返回失败"有两种可能：校验在前，或执行完才发现不允许。
因此每类接入回归都要有**副作用探针**：

| 维度 | 探针 | 期望 |
|---|---|---|
| 文件 | 目标文件是否存在 | 不存在 |
| 命令 | 让命令去 `mkdir` 一个不存在的目录 | 目录不存在 |
| HTTP | 本地假服务的请求计数 | 0 |
| notify | mock 适配器的 `send` 调用次数 | 0 |

`notify` 另需断言调用顺序（inOrder：enforce 先于 send）；顺序错但"恰好在发送前抛出"是测不出来的。

## R-09 接口中立性自查（microVM 反向检验）

假设实现 `class KataMicroVmSandbox implements Sandbox`：

- 它收到 `SandboxAction(FILE_ACCESS, "/workspace/a.txt")` → 把该路径挂载进 microVM 的只读层，
  动作在 microVM 内执行；目标不在允许挂载集 → 抛 `SandboxViolationException`。
- 收到 `SHELL_EXEC` → 在 microVM 内启动进程；`HTTP_REQUEST` → 走 microVM 的出站代理。
- **需要给接口加方法吗？** 不需要。动作类型与目标足以表达意图；隔离强度、镜像、资源上限属于**实现的选择**，
  由实现类自己的配置决定，不必进接口。

结论：接口立得住。反之，若接口写作 `checkFilePath(String)`，microVM 实现将无处安放——
因为它不是"检查路径"，而是"提供一个隔离的文件系统"。
