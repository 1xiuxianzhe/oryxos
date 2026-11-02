# Quickstart：一个目录定义一个 Agent

> 面向**配置 Agent 的人**（不是改底座的人）。三分钟能跑起来，全部内容都在一个目录里。

## 1. 最小可用的 Agent

```
.oryxos/agents/hello/
└── AGENT.md
```

```markdown
---
provider:
  name: deepseek
  model: deepseek-flash
---

你是我的助手，每次只回答一句话。
```

**目录名就是 Agent 名**（`hello`）。重启后它出现在 `GET /api/v1/profiles` 里，
`oryxos chat --profile hello "在吗"` 就能对话。

## 2. 让它自己跑

```yaml
schedules:
  - id: morning                 # Profile 内唯一
    cron: "0 0 9 * * *"         # 秒 分 时 日 月 周 → 每天 09:00
    timezone: Asia/Shanghai     # 不写则用系统时区
    message: 看一下上海现在的天气，记下来，然后推给 ljx。
```

`message` 写成**人话**即可：调哪个工具由工具描述 + 模型自己决定，
不需要在句子里点名工具、也不需要写步骤编号。

## 3. 它能用哪些工具

```yaml
tools: [get_weather, notify, save_memory]
```

- 这是**白名单**：没写进来的工具，模型**根本看不见**（所以"为什么没记住"多半是漏了 `save_memory`）。
- 整段不写 = 不限制，模型能看到注册表里全部工具（含 `read_file` / `write_file` / `shell`）。
- 写了不存在的工具名 → 启动告警，该工具被排除，Agent 仍正常注册。

## 4. 目录里还能放什么（都不会预先加载）

```
.oryxos/agents/daily-reconcile/
├── AGENT.md            # 配置 + 正文（正文每轮进 system prompt）
├── REFERENCE.md        # 参考：需要时用 read_file 读
├── skills/
│   └── report-format.md  # 子指令：需要时用 read_file 读
├── scripts/
│   └── reconcile.py    # 确定性计算：需要时用 shell 跑
├── samples/*.csv       # 数据
└── memory/MEMORY.md    # 运行时自动生成（这个 Agent 自己的记忆）
```

**渐进式披露**：只有 `AGENT.md` 正文常驻上下文；其余内容由模型按正文指引用工具去取。
正文里请写**绝对路径**（系统也会把资源目录的绝对路径注入 prompt）。

## 5. frontmatter 常用字段

| 字段 | 说明 |
| --- | --- |
| `provider` | **必填**。`name` 必须是全局层已声明的 provider，`model` 建议显式写 |
| `identity` | 人设（字符串；也接受 `{agent_name, prompt}` 对象形态） |
| `tools` | 工具白名单，见上 |
| `notify_channels` | 推送渠道，`{type: webhook, config: {url: ...}}` |
| `schedules` | 定时任务，见上 |
| `settings` | `max_iterations` / `max_history_turns` / `processing_timeout_seconds` |
| `mcp_servers` / `channels` / `bootstrap` | 与 legacy Profile 同义 |

**不要写**：`skills:`（目录里的 `skills/` 是按需读的资源，而 `Profile.skills` 是每轮全量注入，
两者同名不同义，写了会直接报错跳过）。`name` 会被忽略（以目录名为准）。

## 6. 脚本的边界（重要）

带 `scripts/` 的 Agent，`shell` 只允许一种形态：

```
<解释器> <本目录>/scripts/xxx.py [参数]
```

- 解释器白名单默认 `python / python3 / py / bash / sh / node`（`oryxos.sandbox.allowed-interpreters`）。
- 脚本必须落在**本 Agent 的 `scripts/` 内**（按真实路径判定，`..` 与符号链接无效）。
- 拒绝 `python -c "..."` / `python -m ...`，拒绝 `&&` `|` `;` `>` 等串联或重定向。
- 退出码非 0 = **失败**，错误里带退出码与输出尾部（≤2KB）。
- 输出超过 32KB 会截断并标注。

**信任边界**：脚本内容不受本项目限制——它能读写工作区文件、发起任意网络请求，
并且**会绕过内置 HTTP 工具的域名白名单**。装一个带脚本的 Agent = 信任它的作者。
启动日志会为每个带 `scripts/` 的 Agent 打一条醒目告警。

## 7. 记忆

- 每个目录 Agent 有自己的 `<agentDir>/memory/MEMORY.md`，互不可见。
- 只能通过 `save_memory` / `recall_memory` 操作（Agent 不直接碰文件）。
- `scope: core` 每轮全量注入；`scope: archival` 需 `recall_memory` 检索。
- legacy `profiles/*.yaml` 与"无处理上下文"（Web/CLI 直接查）回退到 `<workspace>/memory/MEMORY.md`。

## 8. 校验规则（错了会怎样）

| 情况 | 结果 |
| --- | --- |
| 目录名含大写/下划线/中文 | 跳过，原因"目录名不合法" |
| 缺 `AGENT.md` | 跳过 |
| 缺 `provider` 段 | 跳过（硬错） |
| `provider.name` 未在全局声明 | 跳过，消息与 legacy 完全一致 |
| frontmatter 有未知字段（如 `zone`） | 跳过并点名字段（提示该用 `timezone`） |
| 写了 `skills:` | 跳过并说明原因 |
| 正文为空 | 允许注册，启动告警 |
| 与 legacy Profile 同名 | **目录赢**，legacy 版本不注册（含它的 schedules） |

跳过项不会打断启动，但**必须**能在日志里逐条看到；后续会把清单透到接口上。

## 9. 验收清单

1. 放一个合法目录 → 重启 → 出现在 profile 列表。
2. 配 `schedules` → 到点自动跑 → `agent_runs` / `llm_calls` / `tool_invocations` 有记录。
3. 改 `AGENT.md` 正文 → **不重启**，下一轮即生效。
4. `REFERENCE.md` / `skills/*.md` 在没被读之前，不出现在 prompt 里。
5. 执行越界脚本 → 被拒绝，且没有进程被启动。
6. 两个 Agent 各记一条记忆 → 两个 `memory/MEMORY.md` 内容互不可见。
