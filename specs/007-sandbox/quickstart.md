# Quickstart: Sandbox 统一抽象与白名单校验

**Feature**: `007-sandbox`

## 1. 它解决什么问题

Agent 会自己决定读哪个文件、跑哪条命令、请求哪个域名。沙箱就是动手之前的**唯一一道裁决**：
允许就继续，拒绝就返回一条可读的失败（不可重试），并在审计表里留痕。

核心阶段只有**一档实现**：应用层白名单校验。它防的是模型犯傻误操作，**不是**强隔离——
不建议用它去跑完全不可信代码，也不建议靠它做多租户隔离。

## 2. 配置

改 `oryxos-boot/src/main/resources/application.yaml`：

```yaml
oryxos:
  sandbox:
    enabled: true                 # 默认 true；false = 三维度全放行（仅本地调试）
    allowed-paths:                # 文件类：目录前缀，按真实路径判定
      - ${user.dir}
    allowed-commands:             # 命令类：命令首 token，精确匹配
      - echo
      - git
    allowed-domains:              # 出站域名：支持 *. 通配
      - api.example.com
      - "*.example.com"
```

### 2.1 与 24 节文档的配置映射

源文档用三个独立前缀，本仓库统一收在 `oryxos.sandbox` 下（见 spec C-2）：

| 文档写法 | 仓库写法 |
|---|---|
| `file.allowed_paths` | `oryxos.sandbox.allowed-paths` |
| `shell.allowed_commands` | `oryxos.sandbox.allowed-commands` |
| `http.allowed_domains` | `oryxos.sandbox.allowed-domains` |

### 2.2 三条容易踩的规则

1. **空清单 = 全拒**。新建工作区不配白名单时，四个工具全部拒绝，原因只会是"不在白名单内"。
2. **命令大小写敏感**。白名单写 `echo`，模型给 `ECHO hi` 会被拒（fail-closed）。按需把常用写法都列出，
   或把命令写小写并接受偶发误拒。
3. **`notify` 也受域名白名单约束**。它的目标 URL 来自 Profile 的通知渠道配置——
   要推送就必须把该 webhook 域名加进 `allowed-domains`。

## 3. 行为速查

| 调用 | 结果 |
|---|---|
| `read_file` 白名单内路径 | 返回文件内容 |
| `write_file` 白名单外路径 | 失败：`文件路径不在白名单内: ...`，文件未创建 |
| `shell` 白名单内命令 | 返回命令输出 |
| `shell` 白名单外命令 | 失败：`Shell 命令不在白名单内: rm`，进程未启动 |
| `http_get` 白名单外域名 | 失败：`HTTP 域名不在白名单内: ...`，请求未发出 |
| `notify` 域名不在白名单 | 失败：`通知被安全校验拦截: ...`，未发送 |
| 任一拒绝 | 结果 `success=false`、`retryable=false`；`tool_invocations` 记为失败 |

## 4. 怎么验（人工）

```powershell
# 0) 环境（必须 JDK 21 + 固定 Maven）
$env:JAVA_HOME = "C:\Users\Administrator\.jdks\ms-21.0.12"
$mvn = "E:\software\apache-maven-3.9.9-bin\apache-maven-3.9.9\bin\mvn.cmd"

# 1) 模块回归
& $mvn -f g:\code\ORYXOS\pom.xml -pl oryxos-tool -am test

# 2) 全量回归
& $mvn -f g:\code\ORYXOS\pom.xml test
```

集成验证（真实链路）：

1. 配置只允许 `echo`（`allowed-commands: [echo]`），启动应用。
2. 让 Agent（或直接用工具）执行 `mkdir C:\temp\oryxos-probe`。
3. 期望：返回失败 `Shell 命令不在白名单内: mkdir`；目录未被创建；
   `tool_invocations` 中有一条 `success=0` 且 `error_message` 为该句原因。

接口中立性自查（思维练习，不是测试）：

> 换成一个 `MicroVmSandbox` 实现，需要给 `Sandbox` 加方法吗？不需要才算墙立住了。

## 5. 已知边界

- **不是强隔离**：白名单拦的是"你想到要拦的东西"，绕过方式（命令拼接、编码、未列入的危险命令）
  依然存在。要跑不可信代码，需要扩展阶段的容器 / microVM。
- **不覆盖资源维度**：CPU / 内存 / 磁盘配额不在本档范围。
- **不覆盖 Provider 出站**：模型调用走 Provider，不经过 `allowed-domains`。
- **总开关是显式动作**：`enabled=false` 会在启动日志打出多行醒目告警——它最怕"忘了改回来"。
