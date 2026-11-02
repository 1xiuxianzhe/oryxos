# Contract: Sandbox

**Feature**: `007-sandbox` | **Version**: 1.0 | **Date**: 2026-10-20

本文件是"墙"的契约。实现可以换，契约不能改。

## 1. 接口契约

```java
package io.oryxos.tool.sandbox;

public interface Sandbox {
    void enforce(SandboxAction action);
}
```

```java
public record SandboxAction(ActionType type, String target) { }

public enum ActionType { FILE_ACCESS, SHELL_EXEC, HTTP_REQUEST }
```

| # | 条款 |
|---|---|
| 1.1 | 对外**只有一个**编排入口 `enforce`；不得新增 `checkXxx` 之类的公开方法 |
| 1.2 | 签名中不得出现 `path` / `command` / `domain` / `whitelist` / `container` / `image` 等实现词（参数名只能用 `action`） |
| 1.3 | `SandboxAction` 只由 `type` + `target` 构成；`target` 是纯字符串，语义由 `type` 决定 |
| 1.4 | 放行 = 正常返回；拒绝 = 抛 `SandboxViolationException`（不返回布尔值，避免调用方忘记判断） |
| 1.5 | 新增隔离档 = 新增 `implements Sandbox` 的类 + 装配处换 Bean；**接口不得变化** |
| 1.6 | 实现不得使用 `SecurityManager` |

### 1.1 中立性检验（microVM 反向推演）

| 动作 | `MicroVmSandbox` 如何响应 | 需要改接口吗 |
|---|---|---|
| `FILE_ACCESS, "/workspace/a.txt"` | 把 `/workspace` 以只读挂载进 microVM；目标不在允许挂载集 → 抛拒绝 | 不需要 |
| `SHELL_EXEC, "ls -l"` | 在 microVM 内执行；镜像内无该命令 → 抛拒绝 | 不需要 |
| `HTTP_REQUEST, "https://api.x.com"` | 经 microVM 出站代理；出口策略不允许 → 抛拒绝 | 不需要 |

隔离强度、镜像、资源上限是**实现自己的配置**，不进接口——这是"接口表达意图，不表达实现"的落点。

## 2. 接入点契约

| 工具 | 动作类型 | enforce 位置 | 之后才允许发生的事 |
|---|---|---|---|
| `read_file` / `list_dir` | `FILE_ACCESS` | `execute` 首行（参数校验后） | 读文件 / 列目录 |
| `write_file` | `FILE_ACCESS` | 同上 | 写文件 |
| `shell` | `SHELL_EXEC` | 同上 | 启动进程 |
| `http_get` / `http_post` | `HTTP_REQUEST` | 同上 | 建立连接、发请求 |
| `notify` | `HTTP_REQUEST` | **目标解析之后、发送之前** | 调用渠道适配器发送 |

| # | 条款 |
|---|---|
| 2.1 | 四个工具持有的静态类型 MUST 是 `Sandbox`，不得是具体实现类 |
| 2.2 | 工具 MUST NOT 依赖"白名单"概念；拒绝原因由实现给出，工具只负责转成失败结果 |
| 2.3 | `Sandbox` MUST 是强制依赖；`notify` 的"未装配则放行"分支 MUST 被删除 |
| 2.4 | `notify` 的位置后移一位是**唯一**的例外，理由：URL 来自 Profile，首行无法取得 |

## 3. 拒绝语义契约

| # | 条款 |
|---|---|
| 3.1 | 消息含**维度 + 被拒值**：`文件路径不在白名单内: <path>`、`Shell 命令不在白名单内: <token>`、`HTTP 域名不在白名单内: <host>` |
| 3.2 | 工具 MUST 返回 `success=false` + `retryable=false` |
| 3.3 | 工具 MUST NOT 把拒绝改写成成功，MUST NOT 吞掉拒绝 |
| 3.4 | 审计 MUST 走既有执行链路（`tool_invocations`：`success=false`、`error_message`=拒绝原因），不得新增旁路 |
| 3.5 | 空目标（`null` / 空白）MUST 被拒绝且给出可读消息，MUST NOT 以 NPE 形式冒出 |

## 4. 配置契约

| 键 | 默认 | 契约 |
|---|---|---|
| `oryxos.sandbox.enabled` | `true` | `false` → 三维度放行 + 启动醒目告警；格式校验仍执行 |
| `oryxos.sandbox.allowed-paths` | `[]` | 空 = 全拒；按真实路径判定；空白条目启动即报错 |
| `oryxos.sandbox.allowed-commands` | `[]` | 空 = 全拒；首 token 精确匹配；空白条目启动即报错 |
| `oryxos.sandbox.allowed-domains` | `[]` | 空 = 全拒；`*.` 通配带标签边界；空白条目启动即报错 |

## 5. Required Verification

| # | 验证项 | 落点 |
|---|---|---|
| 1 | 白名单内路径放行 | `WhitelistSandboxTest` |
| 2 | 白名单外路径拒绝，消息含路径 | `WhitelistSandboxTest` |
| 3 | `..` 爬出白名单被拒 | `WhitelistSandboxTest` |
| 4 | 符号链接指向外部被拒 / 白名单目录本身是链接仍按真实路径匹配 / 新建文件识别中间层链接 | `WhitelistSandboxTest` |
| 5 | 路径清单为空 → 全拒 | `WhitelistSandboxTest` |
| 6 | 命令白名单内放行 / 外拒绝（消息含首 token）/ 清单为空全拒 | `WhitelistSandboxTest` |
| 7 | 命令前导空白放行；大小写变体拒绝 | `WhitelistSandboxTest` |
| 8 | 域名精确匹配放行 / 通配放行 / 外拒绝 / 空清单全拒 | `WhitelistSandboxTest` |
| 9 | `*.example.com` 命中 `api.example.com`，不命中 `evil-example.com` | `WhitelistSandboxTest` |
| 10 | 空目标被拒且消息可读 | `WhitelistSandboxTest` |
| 11 | `enabled=false` 三维度放行（含空清单）；默认启用仍拦截 | `WhitelistSandboxTest`、`ToolAutoConfigurationTest` |
| 12 | 文件工具越界 → 文件未创建/未读取（副作用为零） | `FileToolTest` |
| 13 | 命令工具越界 → 探针目录未创建（无进程启动） | `ShellToolTest` |
| 14 | HTTP 工具越界 → 假服务零命中 | `HttpToolTest` |
| 15 | `notify` 越界 → 适配器零调用，且 enforce 先于 send | `NotifyToolTest` |
| 16 | 装配后四个工具持有的依赖类型为 `Sandbox`；空白条目启动报错；关闭开关仍做格式校验 | `ToolAutoConfigurationTest` |
| 17 | `notify` 装配在缺少 `Sandbox` 时失败（强制依赖） | `NotifyAutoConfigurationTest` |
| 18 | 全仓 `mvn test` 通过 | 仓库根命令 |
