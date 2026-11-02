# OryxOS 使用指南（从下载到跑通）

本文面向**第一次接触 OryxOS 的使用者**：从拿到源码到启动服务、配好模型、跑起第一个 Agent，
每一步都给可复制的命令。想先了解项目定位与架构，看 [README](../README.md)。

> 本文以 `0.1.0-SNAPSHOT` 为准。命令默认在**仓库根目录**执行；Windows 用 `bin\oryxos.cmd`，
> Linux / macOS / WSL 用 `./bin/oryxos.sh`。

---

## 0. 最快的路径

已经装好 JDK 21 与 Maven，且有 DeepSeek（或任意 OpenAI 兼容）API Key：

```bash
cp config/application.yml.example config/application.yml   # Windows: copy config\application.yml.example config\application.yml
# 编辑 config/application.yml，确认 provider 与 model
export DEEPSEEK_API_KEY=sk-xxxx                            # Windows PowerShell: $env:DEEPSEEK_API_KEY="sk-xxxx"

mvn -DskipTests package
./bin/oryxos.sh init
./bin/oryxos.sh serve --port 8080
```

浏览器打开 <http://localhost:8080/admin/>。下面逐段解释每一步，以及出问题时怎么查。

---

## 1. 前置条件

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| JDK | **21+** | 必需。Spring Boot 3.x 与虚拟线程都要求 21 |
| Maven | 3.9+ | 只用它构建；运行时不依赖 Maven |
| 模型 API Key | — | DeepSeek / Kimi / OpenAI 兼容端点 / 本地 Ollama 均可 |
| Node.js | 18+（可选） | **只有改管理台前端时才需要**；管理台产物已随仓库提供 |

检查版本：

```bash
java -version     # 必须是 21 或更高
mvn -v
```

---

## 2. 获取代码

```bash
git clone <你的仓库地址> oryxos
cd oryxos
```

目录里最重要的是：

```text
oryxos-core/ ... oryxos-boot/   9 个 Maven 模块（源码）
admin-web/                      管理台前端源码（React）
config/application.yml.example  本地配置模板
bin/oryxos.sh | oryxos.cmd      启动包装脚本
examples/agents/                可直接复制使用的示例 Agent
docs/                           设计文档与使用文档
```

---

## 3. 配置模型与密钥

### 3.1 生成本地配置

```bash
cp config/application.yml.example config/application.yml
# Windows
copy config\application.yml.example config\application.yml
```

`config/application.yml` 已在 `.gitignore` 中，**不会被提交**。
Spring Boot 会优先加载「工作目录/config/application.yml」，覆盖 JAR 内打包的默认配置，
所以**改配置不需要重新打包**。

### 3.2 设置密钥环境变量

密钥只以 `${DEEPSEEK_API_KEY}` 形式出现在配置里，进程启动时从环境变量读取。

```bash
# Linux / macOS
export DEEPSEEK_API_KEY=sk-xxxx

# Windows PowerShell（当前窗口有效）
$env:DEEPSEEK_API_KEY="sk-xxxx"

# Windows CMD（当前窗口有效）
set DEEPSEEK_API_KEY=sk-xxxx
```

要长期生效：Linux 写进 `~/.bashrc` / `~/.zshrc`；Windows 用「系统属性 → 环境变量」或
`setx DEEPSEEK_API_KEY "sk-xxxx"`（新开终端生效）。

### 3.3 关键配置项

```yaml
oryxos:
  providers:
    - name: deepseek                 # Agent 里 provider.name 引用这个名字
      base-url: https://api.deepseek.com
      api-key: ${DEEPSEEK_API_KEY}
  author:                            # 「一句话生成 AGENT.md」用的模型
    provider: deepseek
    model: deepseek-chat
  sandbox:
    enabled: true
    allowed-paths:
      - ${user.dir}                  # 文件工具只能读写这个目录下
    allowed-commands:
      - echo                         # shell 工具只允许这些命令
    allowed-domains:
      - localhost
      - 127.0.0.1                    # HTTP 工具/通知只允许这些 host
```

| 配置 | 必填 | 说明 |
| --- | --- | --- |
| `oryxos.providers[]` | 是 | 本实例接的模型服务。`name` 唯一，`base-url` 指向 OpenAI 兼容端点 |
| `oryxos.providers[].api-key` | 是 | 用 `${环境变量}` 占位，不要写明文 |
| `oryxos.author.provider/model` | 否 | 管理台「生成草稿」与新建 Agent 模板用；缺省时该功能返回 503，其余不受影响 |
| `oryxos.sandbox.allowed-paths` | 建议 | 文件工具白名单；**留空 = 文件类工具全部拒绝** |
| `oryxos.sandbox.allowed-commands` | 建议 | shell 命令白名单（首个 token 精确匹配，大小写敏感） |
| `oryxos.sandbox.allowed-domains` | 建议 | HTTP / 通知的出站 host 白名单，支持 `*` |
| `oryxos.workspace` | 否 | 工作区位置，默认 `<启动目录>/.oryxos` |
| `server.port` | 否 | HTTP 端口，默认 8080（也可用 `serve --port` 覆盖） |

> 沙箱任一清单为空时，该维度**一律拒绝**。这是刻意的默认值，不是 bug。

---

## 4. 构建

```bash
mvn -DskipTests package
```

产物：`oryxos-boot/target/oryxos-boot-0.1.0-SNAPSHOT.jar`（含所有依赖与管理台静态资源）。

- 第一次构建会下载依赖，耗时取决于网络。
- 想跑测试：`mvn test`。
- 只有改 `admin-web/` 前端时才需要 `npm install && npm run build`，产物会写进
  `oryxos-web/src/main/resources/static/admin/`，再重新打包。

---

## 5. 初始化工作区

```bash
./bin/oryxos.sh init
# Windows
bin\oryxos.cmd init
```

在启动目录下生成 `.oryxos/`：

```text
.oryxos/
  agents/             # 每个子目录 = 一个 Agent（首次创建 Agent 时出现）
  archive/            # 删除 Agent 时的归档去向（不物理删除）
  profiles/           # legacy Profile（旧方式，仍兼容）
  memory/MEMORY.md    # 工作区共享长期记忆
  skills/ sessions/ logs/ tools/
  mcp_servers.yaml    # MCP server 声明
  AGENTS.md SOUL.md USER.md
  oryxos.db           # SQLite：会话 + 审计
```

命令是幂等的：已存在的文件不会被覆盖。

---

## 6. 启动

```bash
# REST API + 管理台，同一个端口
./bin/oryxos.sh serve --port 8080
# Windows
bin\oryxos.cmd serve --port 8080
```

启动后：

| 地址 | 内容 |
| --- | --- |
| <http://localhost:8080/api/v1/health> | 健康检查，返回 `{"status":"UP"}` |
| <http://localhost:8080/api/v1/info> | 版本、Provider 状态、沙箱概要 |
| <http://localhost:8080/admin/> | Web 管理台 |
| <http://localhost:8080/swagger-ui/index.html> | OpenAPI 文档 |

前台运行时日志直接打在控制台；按 `Ctrl+C` 停止。

### 后台运行（Linux / macOS）

```bash
nohup ./bin/oryxos.sh serve --port 8080 > oryxos.out 2>&1 &
# 停止
kill $(pgrep -f 'oryxos-boot-.*\.jar')
```

### 只想先聊一句

```bash
./bin/oryxos.sh chat --profile default
# 或指定某个 Agent（前提是该 Agent 已存在）
./bin/oryxos.sh chat --profile weather-get
```

---

## 7. 第一个 Agent

### 7.1 复制示例

```bash
mkdir -p .oryxos/agents
cp -r examples/agents/weather-get .oryxos/agents/
# Windows
xcopy /E /I examples\agents\weather-get .oryxos\agents\weather-get
```

进程在跑时**不用重启**：`WorkspaceWatcher` 会监听到新目录并把 Agent 注册进来
（失败时不会顶掉旧 Agent，`GET /api/v1/agents` 的 `skipped` 会给出原因）。

> 注意：**启动时**扫描失败的目录不会出现在接口里，原因只写在启动日志
> （搜 `已跳过目录 Agent`）。最常见的失败原因是 `AGENT.md` 里引用的 `provider.name`
> 没有在 `oryxos.providers` 中声明。

### 7.2 用管理台试

1. 打开 <http://localhost:8080/admin/>；
2. 进入 Agents，能看到 `weather-get`；
3. 点开详情，在管理台会话里输入「杭州现在天气怎么样」；
4. 右侧可以看到每次提问对应的模型调用与工具调用明细。

### 7.3 用 curl 试

```bash
# 无状态单轮调用（每次新建一次性会话，适合业务系统集成）
curl -s -X POST http://localhost:8080/api/v1/agents/weather-get/invoke \
  -H "Content-Type: application/json" \
  -d "{\"message\":\"杭州现在天气怎么样？\"}"
```

返回里 `content` 是 Agent 的回答。`get_weather` 是**演示工具**：不联网，返回随机天气，
回答里会明确标注这是演示数据。

---

## 8. 接入业务系统（REST）

### 8.1 多轮会话

```bash
# 1) 创建（或复用）会话
curl -s -X POST http://localhost:8080/api/v1/sessions \
  -H "Content-Type: application/json" \
  -d "{\"profile\":\"weather-get\",\"userId\":\"alice\"}"
# → {"sessionId":"...","profileName":"weather-get",...}

# 2) 发消息（会话 ID 换成上一步返回值）
curl -s -X POST http://localhost:8080/api/v1/sessions/<sessionId>/messages \
  -H "Content-Type: application/json" \
  -d "{\"content\":\"那明天呢？\"}"

# 3) 看这次提问的完整链路：模型调用 + 工具调用 + 耗时 + token
curl -s http://localhost:8080/api/v1/sessions/<sessionId>/runs

# 4) 归档会话（数据保留，只是不能再发消息）
curl -s -X DELETE http://localhost:8080/api/v1/sessions/<sessionId>
```

### 8.2 响应格式

- 成功：直接返回 DTO（对象或数组），HTTP 2xx。
- 失败：非 2xx + `{"errorCode":"...","message":"...","timestamp":"..."}`。
- **没有鉴权**：核心阶段假定内网部署，不要直接暴露到公网。

### 8.3 其他常用端点

| 需求 | 端点 |
| --- | --- |
| 列出 Agent（含加载失败原因） | `GET /api/v1/agents` |
| 新建 Agent | `POST /api/v1/agents`，body `{"name":"my-agent","description":"..."}` |
| 一句话生成草稿 | `POST /api/v1/agents/my-agent/generate-files`，body `{"description":"..."}` |
| 覆盖 `AGENT.md` | `PUT /api/v1/agents/my-agent`，body `{"markdown":"..."}` |
| 看 Provider 装配状态 | `GET /api/v1/providers` |
| 看可用工具 | `GET /api/v1/tools` |
| 看定时任务 | `GET /api/v1/schedules` |

完整清单见 [README 的 REST API 表](../README.md#rest-api)。

---

## 9. 配置参数速查

| 键 | 默认 | 作用 |
| --- | --- | --- |
| `server.port` | `8080` | HTTP 端口 |
| `oryxos.workspace` | `${user.dir}/.oryxos` | 工作区根目录 |
| `oryxos.providers[].name` | — | Provider 名，Agent 用它引用 |
| `oryxos.providers[].base-url` | — | OpenAI 兼容端点 |
| `oryxos.providers[].api-key` | — | 建议 `${ENV}` |
| `oryxos.author.provider/model` | `deepseek` / `deepseek-flash` | 生成草稿用的模型 |
| `oryxos.sandbox.enabled` | `true` | `false` = 四维白名单全放行（仅调试） |
| `oryxos.sandbox.allowed-paths` | `[]` | 文件工具可访问目录 |
| `oryxos.sandbox.allowed-commands` | `[]` | shell 允许的命令 |
| `oryxos.sandbox.allowed-domains` | `[]` | HTTP/通知允许的 host |
| `oryxos.sandbox.allowed-interpreters` | `python,python3,py,bash,sh,node` | Agent `scripts/` 允许的解释器 |
| `oryxos.scheduler.pool-size` | `2` | 定时任务调度线程数 |

Agent 级参数（写在 `AGENT.md` 的 `settings`）：

| 键 | 默认 | 作用 |
| --- | --- | --- |
| `max_iterations` | `10` | ReAct 最大轮数 |
| `max_history_turns` | `20` | 会话历史保留轮数 |
| `processing_timeout_seconds` | `60` | 单次请求整体超时 |
| `tool_timeout_seconds` | `30` | 单次工具执行超时 |

---

## 10. 常见问题

**启动报「未配置任何 provider」/ Agent 报模型不可用**
先看 `GET /api/v1/providers`：状态 `not-assembled` 说明配置里有、但没装配成功（通常是
`api-key` 环境变量没设或拼错）。日志里会写明读不到哪个环境变量。

**管理台里 Agent 列表是空的**
先看启动日志。最常见原因是 `AGENT.md` 里的 `provider.name` 没在 `oryxos.providers` 中声明，
日志会写 `Provider 'xxx' 未在全局层 oryxos.providers 中声明`。其它原因：目录名不合法
（只允许小写字母、数字、中划线，且以字母或数字开头）、缺 `AGENT.md`、frontmatter 里有未知字段。

**工具调用被拒绝，提示「不在白名单内」**
沙箱拦的。把目标目录加进 `allowed-paths`、命令加进 `allowed-commands`、
host 加进 `allowed-domains`，重启进程生效。

**端口被占用**
`serve --port 9000`，或先停掉占用 8080 的进程。

**新建 Agent 时报 503**
没配 `oryxos.author.provider/model`。补上配置并重启；不影响已有 Agent 运行。

**改了 `AGENT.md` 没生效**
确认改的是 `.oryxos/agents/<name>/AGENT.md`。目录 Agent 会热加载；
`.oryxos/profiles/*.yaml`（legacy）不监听，改完要重启。

**数据存在哪 / 怎么重置**
会话、审计在 `.oryxos/oryxos.db`；长期记忆在 `.oryxos/memory/MEMORY.md`（目录 Agent 另有
`.oryxos/agents/<name>/memory/MEMORY.md`）。重置 = 停进程 + 删掉 `.oryxos/` + 重新 `init`
（会丢历史与记忆，谨慎）。

**管理台打不开 / 页面空白**
确认访问的是 `/admin/`（带斜杠）；管理台资源随 JAR 提供，不需要单独起前端。
改前端时用 `cd admin-web && npm run dev`，Vite 在 5173 端口代理 `/api` 到 8080。

**想换模型 / 加第二家 Provider**
在 `config/application.yml` 的 `oryxos.providers` 里加一项（`name` 不重复），
Agent 的 `provider.name` 改成它，重启生效。代码零改动。

---

## 11. 安全提示

- 核心阶段**没有认证鉴权**，只适合内网或本机；要公网暴露请自行加反向代理 + 认证。
- 密钥通过环境变量注入，`config/application.yml` 已被 gitignore，不要提交。
- 沙箱是应用层白名单，不是容器级隔离；执行不可信代码请放到容器 / 虚拟机里。
- 审计从第一天落库（`agent_runs` / `llm_calls` / `tool_invocations`），可以直接查库核对。

---

## 12. 接下来

- 项目定位、架构、能力边界：[README](../README.md)
- 各模块原理与代码讲解：[docs/class/](class/)
- 开发约束（给 AI 助手与贡献者）：[AGENTS.md](../AGENTS.md)
